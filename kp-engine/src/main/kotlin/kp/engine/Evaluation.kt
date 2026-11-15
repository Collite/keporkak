package kp.engine

import kp.model.utc
import kp.rules.Comparison
import kp.rules.FactCall
import kp.rules.Literal
import kp.rules.MessageField
import kp.rules.Operator
import kp.rules.Outcome
import kp.rules.PoolFile
import kp.rules.ReplyField
import kp.rules.Requirement
import kp.rules.RuleSet
import kp.rules.Scope
import kp.rules.Source
import kp.rules.Value
import java.math.BigDecimal
import kotlin.time.Duration

/**
 * One judgement's reading of the rules (contracts.md §4.1): requirements and values against the context, the state
 * and the table of facts. It remembers every fact it read and which the table did not supply, and the counters the
 * judgement has moved so far.
 */
internal class Evaluation(val rules: RuleSet, val state: State, val context: Context, private val facts: Facts) {
    val observed = LinkedHashMap<String, Datum>()
    val unsupplied = LinkedHashSet<FactRef>()
    /** Counter → its new value, for every counter this judgement moved. */
    val moved = LinkedHashMap<String, Long>()

    /** The first of [requirements] that fails, or null when all hold — evaluated in order. */
    fun firstFailure(requirements: List<Requirement>): Requirement? = requirements.firstOrNull { !holds(it) }

    fun holds(requirement: Requirement, blocks: Boolean = true): Boolean = when (requirement) {
        is Requirement.Fact -> compare(fact(requirement.fact), requirement.comparison)
        is Requirement.Arg -> compare(arg(requirement.name), requirement.comparison)
        is Requirement.Message -> compare(field(requirement.field), requirement.comparison)
        is Requirement.Reply -> compare(reply(requirement.field), requirement.comparison)
        is Requirement.Counter -> compare(Datum.of(counter(requirement.name)), requirement.comparison)
        is Requirement.Attest -> requirement.name in attested()
        is Requirement.Signed -> context.signed && context.message?.from == requirement.signer
        is Requirement.Operation -> context.operation == requirement.operation && context.ok == (requirement.outcome == Outcome.OK)
        is Requirement.Any -> requirement.items.any { holds(it, blocks) }
        is Requirement.Not -> !holds(requirement.item, blocks)
        is Requirement.Always -> requirement.holds
        // A block that names a block is refused when the rules load (Amendment 9); here it is never followed.
        is Requirement.Holds -> blocks && rules.engagement.blocks[requirement.block]?.all { holds(it, blocks = false) } == true
    }

    /** What the sender attested: `args.attest`. */
    fun attested(): List<String> = (context.args.fields["attest"] as? Datum.Items)?.items?.mapNotNull { (it as? Datum.Text)?.value }.orEmpty()

    /** No comparison: "is true" (an arg: "present and not empty"). A value that is unknown never compares. */
    private fun compare(actual: Datum?, comparison: Comparison?): Boolean {
        if (actual == null) return false
        if (comparison == null) return truthy(actual)
        val expected = value(comparison.value) ?: return false
        return when (comparison.operator) {
            Operator.EQ -> same(actual, expected)
            Operator.NE -> !same(actual, expected)
            Operator.IN -> member(actual, expected)
            Operator.GT -> order(actual, expected)?.let { it > 0 } == true
            Operator.GTE -> order(actual, expected)?.let { it >= 0 } == true
            Operator.LT -> order(actual, expected)?.let { it < 0 } == true
            Operator.LTE -> order(actual, expected)?.let { it <= 0 } == true
        }
    }

    /** A value (contracts.md §4.1, §4.8), or null when it is unknown. */
    fun value(value: Value): Datum? = when (value) {
        is Value.Constant -> literal(value.literal)
        is Value.Items -> value.items.map { value(it) ?: return null }.let(Datum::Items)
        is Value.Fields -> value.fields.mapValues { value(it.value) ?: return null }.let(Datum::Fields)
        is Value.Cap -> rules.engagement.caps[value.name]?.let(::literal)
        is Value.Arg -> arg(value.path)
        is Value.Fact -> fact(value.fact)
        is Value.Message -> field(value.field)
        is Value.Reply -> reply(value.field)
        is Value.Pool -> pool(value.file, value.path)
        is Value.Engagement -> rules.engagement.values[value.path]?.let(::literal)
        is Value.Robot -> senderRobot()?.thresholds?.get(value.threshold)?.let(::literal)
        // A field of what triggered a robot's rule: robots are S1.4's.
        is Value.Trigger -> null
    }

    private fun arg(path: String): Datum? = path.split('.').fold(context.args as Datum?) { at, key -> (at as? Datum.Fields)?.fields?.get(key) }

    private fun field(field: MessageField): Datum? = context.message?.let {
        when (field) {
            MessageField.BY -> Datum.Text(it.by)
            MessageField.FROM -> Datum.Text(it.from)
            MessageField.TO -> Datum.Text(it.to)
            MessageField.STAGE -> it.stage?.let(Datum::Text)
        }
    }

    private fun reply(field: ReplyField): Datum? = context.replyTo?.let {
        Datum.Text(when (field) {
            ReplyField.KIND -> it.kind
            ReplyField.CLASS -> it.messageClass
            ReplyField.FROM -> it.from
            ReplyField.TO -> it.to
        })
    }

    /** `{roster: …}` and `{budget: …}` from their one file; `{office: …}` from this office's entry — the office in the message's id. */
    private fun pool(file: PoolFile, path: String): Datum? {
        val entries = rules.pool.values[file].orEmpty()
        val entry = if (file != PoolFile.OFFICE) entries.singleOrNull() else {
            val office = context.message?.id?.removePrefix("m-")?.substringBefore('-')
            entries.getOrNull(rules.pool.offices.keys.indexOf(office))
        }
        return entry?.get(path)?.let(::literal)
    }

    /** A `{robot: …}` outside a robot names a threshold of the robot file of the kind's own sender (contracts.md §4.1). */
    private fun senderRobot() = context.message?.from?.let { from -> rules.robots.values.firstOrNull { roleOwns(it.role, from) } }

    /** A counter of the kind's scope, as the state has it plus what this judgement moved. */
    fun counter(name: String): Long = moved[name] ?: state.counter(counterSubject(name) ?: "", name)

    fun count(name: String) { moved[name] = counter(name).inc() }

    private fun counterSubject(name: String): String? = when (rules.lifecycle.counters[name]?.scope) {
        Scope.SEAT -> (context.args.fields["seat"] as? Datum.Text)?.value ?: context.message?.from
        Scope.EFFORT -> context.stage?.substringBefore('/')
        else -> context.stage
    }

    /** A fact's value: from the state when its source is exactly L, from the table otherwise; null when unknown. */
    fun fact(call: FactCall): Datum? {
        val params = call.params.mapValues { (_, param) -> value(param) }
        val ref = FactRef(call.name, params.mapValues { it.value ?: Datum.Null })
        val known = params.values.none { it == null }
        val value = when {
            !known -> null
            answeredByEngine(call.name) -> Answers(this).answer(ref)
            else -> facts[ref]
        }
        if (value == null) unsupplied += ref
        observed.putIfAbsent(ref.toString(), value ?: UNKNOWN_FACT)
        return value
    }

    fun answeredByEngine(name: String): Boolean = rules.vocabulary.facts[name]?.sources == setOf(Source.L)
}

/** A role owns a sender when the sender is the role, or a seat of it: `<role>-<n or office>` (contracts.md §1). */
internal fun roleOwns(role: String, sender: String): Boolean = sender == role || sender.startsWith("$role-")

internal fun literal(literal: Literal): Datum = when (literal) {
    is Literal.Text -> Datum.Text(literal.value)
    is Literal.Number -> Datum.Number(literal.value)
    is Literal.Bool -> Datum.Bool(literal.value)
    Literal.Null -> Datum.Null
}

internal fun truthy(value: Datum): Boolean = when (value) {
    is Datum.Bool -> value.value
    is Datum.Number -> value.value.signum() != 0
    is Datum.Text -> value.value.isNotEmpty()
    is Datum.Items -> value.items.isNotEmpty()
    is Datum.Fields -> value.fields.isNotEmpty()
    Datum.Null -> false
}

/** Two sets are the same set (contracts.md §4.1); anything else is the same value. */
private fun same(actual: Datum, expected: Datum): Boolean =
    if (actual is Datum.Items && expected is Datum.Items) actual.items.toSet() == expected.items.toSet() else actual == expected

/** For a set, every member is in the value; for one value, it is one of the value's. */
private fun member(actual: Datum, expected: Datum): Boolean {
    val values = (expected as? Datum.Items)?.items ?: return false
    return if (actual is Datum.Items) actual.items.all { it in values } else actual in values
}

/** Numbers as numbers, durations as durations (`24h`, a number is seconds), times as times, text as text; else no order. */
private fun order(actual: Datum, expected: Datum): Int? {
    if (actual is Datum.Number && expected is Datum.Number) return actual.value.compareTo(expected.value)
    val durations = listOf(actual, expected).map(::seconds)
    if (durations.none { it == null } && (actual is Datum.Text || expected is Datum.Text)) return durations[0]!!.compareTo(durations[1]!!)
    if (actual is Datum.Text && expected is Datum.Text) {
        val times = listOf(actual.value, expected.value).map { runCatching { utc(it) }.getOrNull() }
        if (times.none { it == null }) return times[0]!!.compareTo(times[1]!!)
        return actual.value.compareTo(expected.value)
    }
    return null
}

private fun seconds(value: Datum): BigDecimal? = when (value) {
    is Datum.Number -> value.value
    is Datum.Text -> Duration.parseOrNull(value.value)?.let { BigDecimal.valueOf(it.inWholeSeconds) }
    else -> null
}
