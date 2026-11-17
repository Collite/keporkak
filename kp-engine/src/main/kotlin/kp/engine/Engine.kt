package kp.engine

import kp.model.utc
import kp.rules.ArgSpec
import kp.rules.ArgType
import kp.rules.Destination
import kp.rules.PARTIES
import kp.rules.Effect
import kp.rules.Event
import kp.rules.FactCall
import kp.rules.Kind
import kp.rules.Literal
import kp.rules.MessageClass
import kp.rules.Requirement
import kp.rules.Robot
import kp.rules.RuleSet
import kp.rules.Transition
import kp.rules.Value
import kp.rules.Window
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The mailroom's guard (architecture.md §1): the rule set applied to a trigger. Pure — it reads no file, no clock
 * and no world; "now" is a fact like any other. The autonomy profile in force is the rule set's (`engagement.yaml:
 * profile`, contracts.md §4.3).
 */
class Engine(private val rules: RuleSet) {
    /**
     * The facts [judge] will read that the table must supply (contracts.md §4.4): every fact its requirements, class
     * cases and effects may read, but those of source L, which the engine answers from the state — and `now`.
     */
    fun needs(state: State, trigger: Trigger): List<Need> {
        val context = Context.of(trigger, state) ?: return emptyList()
        return Needs(rules, state, context).collect()
    }

    /** What is to happen: deliver or refuse, which transitions fire, which counters move, which effects are requested, under which class. */
    fun judge(state: State, trigger: Trigger, facts: Facts): Decision {
        val context = Context.of(trigger, state) ?: return Decision.create(Outcome.NOTHING)
        return Judgement(rules, state, context, facts).run()
    }

    /**
     * What holds for every kind (contracts.md §4.1), for an arriving message a robot keeps instead of its addressee: the
     * refusal when the sender's role, the `args`, a cap, the thread, the body, the addressee or a ticket answered once
     * breaks it — null when it may be kept. The kind's own requirements are judged when it is let go.
     */
    fun admit(state: State, trigger: Trigger.Arrival, facts: Facts): Decision? {
        val context = Context.of(trigger, state) ?: return null
        return Judgement(rules, state, context, facts).admission()
    }

    /** The facts [react] will read that the table must supply: as [needs], for the robots' files. */
    fun hears(state: State, trigger: Trigger): List<Need> = RobotNeeds(rules, state, trigger).collect()

    /**
     * The robots' half (contracts.md §8, architecture.md §1): what each active robot's rules do with [trigger] — a
     * fact, a delivery, an arrival, an expiry, presence, an operation's result — and, for an arrival, whether a robot
     * holds it; for a [Trigger.Release], what is released.
     */
    fun react(state: State, trigger: Trigger, facts: Facts): Reaction = Reacting(rules, state, trigger, facts).run()

    /** The robots that keep messages now, each as the [Trigger.Release] that asks what of it is released. */
    fun holders(state: State): List<Trigger.Release> = holders(rules, state)

    /** The facts [turnEnds] will read for [seat] that the table must supply (contracts.md §4.4): none of source L. */
    fun turnNeeds(state: State, seat: String): List<Need> = TurnGating(rules, state, seat).needs()

    /**
     * The gate at a turn's end (contracts.md §4.3, §11): given the state, the seat whose turn is about to end and the
     * blocks so far, end the turn, or block it with the gate's rule in its prose. The cap is the rules'.
     */
    fun turnEnds(state: State, seat: String, blocks: Int, facts: Facts): Gate = TurnGating(rules, state, seat).judge(blocks, facts)

    /**
     * What [seat] may send about [stage] at [now] (contracts.md §5, `whoami`): every kind its role owns, in the rules'
     * order — to whom, what answers it, its deadline, its window and how many firings are left in it, read from the
     * rules and [state]. It decides nothing: a send is judged when it arrives.
     */
    fun allowed(state: State, seat: String, stage: String?, now: String): List<Allowance> =
        rules.kinds.values.filter { kind -> kind.from.any { roleOwns(it, seat) } }.map { kind ->
            val left = maximum(rules, kind)?.let { (it.toLong() - fires(state, kind, seat, stage) { now }).coerceAtLeast(0) }
            Allowance(kind.name, describe(kind.to), kind.replies, kind.deadline?.let(::written), kind.window.key, left)
        }
}

/**
 * One kind a seat may send (contracts.md §5, `whoami`'s `allowed`): its addressee as the rules write it, the kinds that
 * answer it, its deadline as the rules write a duration, its window, and how many firings are left in it — null when
 * nothing counts it.
 */
data class Allowance(val kind: String, val to: String, val replies: List<String>, val deadline: String?, val window: String, val left: Long?)

/** What the gate at a turn's end says (contracts.md §4.3, §11). */
sealed interface Gate {
    /**
     * The turn may end. [capped]: a requirement failed, but the blocks so far had reached the cap — the turn ends, and
     * that is what the fact the cap writes records.
     */
    data class End(val capped: Boolean) : Gate

    /** The turn may not end yet; [reason] is the gate's `rule`, in the rules' own prose. */
    data class Block(val reason: String) : Gate
}

/**
 * The gate of `engagement.yaml` at a turn's end, applied to one seat (contracts.md §4.3): its requirements are read with
 * `{trigger: seat}` as the seat whose turn is ending; a failed requirement blocks while the blocks so far are under the
 * cap, and at the cap the turn ends. The cap is the rules' — no number is written here. A gate whose cap cannot be
 * read holds no one: the turn ends.
 */
private class TurnGating(private val rules: RuleSet, private val state: State, seat: String) {
    private val context = Context(Event.FACT, null, null, null, trigger = Datum.Fields(mapOf(SEAT_FIELD to Datum.Text(seat))))

    fun needs(): List<Need> {
        val gate = rules.engagement.turnGate ?: return emptyList()
        return Needs(rules, state, context).apply { gate.require.forEach(::requirement); value(gate.maxConsecutiveBlocks) }.needs.toList()
    }

    fun judge(blocks: Int, facts: Facts): Gate {
        val gate = rules.engagement.turnGate ?: return Gate.End(capped = false)
        val evaluation = Evaluation(rules, state, context, facts)
        val failed = evaluation.firstFailure(gate.require) ?: return Gate.End(capped = false)
        val cap = (evaluation.value(gate.maxConsecutiveBlocks) as? Datum.Number)?.value
        if (cap == null || blocks.toBigDecimal() >= cap) return Gate.End(capped = true)
        return Gate.Block(gate.rule ?: render(failed))
    }
}

/** What `{trigger: seat}` reads at a turn's end. */
private const val SEAT_FIELD = "seat"

/** A duration as the rules write one (contracts.md §4.4): whole hours, else whole minutes, else seconds. */
private fun written(duration: Duration): String = when {
    duration.inWholeHours > 0 && duration.inWholeHours.hours == duration -> "${duration.inWholeHours}h"
    duration.inWholeMinutes > 0 && duration.inWholeMinutes.minutes == duration -> "${duration.inWholeMinutes}m"
    else -> "${duration.inWholeSeconds}s"
}

/** The caps the engine enforces for every kind, by their names in `engagement.yaml:caps` (contracts.md §2.1, §4.1). */
private const val BODY_LINES = "body_lines"
private const val HOPS = "hops_per_thread"
private const val MAX_FIRES_DEFAULT = "max_fires_default"

private const val MAILROOM = "mailroom"

/** The `by` of an emit the rules declare: a transition's effect, a kind's expiry (contracts.md §2.1). */
private val DECLARED = listOf("effect:", "expiry:")

private class Judgement(private val rules: RuleSet, private val state: State, private val context: Context, facts: Facts) {
    private val evaluation = Evaluation(rules, state, context, facts)
    private val machines = Machines(rules, state)

    fun run(): Decision = if (context.event == Event.MESSAGE) arrival() else fire()

    private fun refuse(rule: String, requirement: String, failed: Requirement? = null) = Decision.create(
        Outcome.REFUSE, rule = rule, requirement = requirement, failed = failed?.let(::form),
        unsupplied = evaluation.unsupplied.toList(), observed = evaluation.observed.toMap(), attested = evaluation.attested(),
    )

    /** What holds for every kind, and a ticket answered once: the refusal, or null. */
    fun admission(): Decision? {
        val message = context.message!!
        val kind = rules.kinds[message.kind] ?: return refuse(message.kind, "kinds: no '${message.kind}'")
        everyKind(kind, message)?.let { (requirement, failed) -> return refuse(kind.name, requirement, failed) }
        answeredTicket(message)?.let { ticket -> return refuse(kind.name, "ticket #$ticket: answered once") }
        return null
    }

    /** `on: message` — what holds for every kind, the kind's own requirements, then the candidates of every machine. */
    private fun arrival(): Decision {
        val message = context.message!!
        admission()?.let { return it }
        val kind = rules.kinds.getValue(message.kind)
        evaluation.firstFailure(kind.require)?.let { return refuse(kind.name, kind.guard ?: render(it), it) }
        val fired = fireMachines(kind)
        if (fired.moves.isEmpty() && rules.lifecycle.machines.values.any { machine -> machine.transitions.any { it.kind == kind.name && it.on == Event.MESSAGE } }) {
            val (candidate, _) = fired.first ?: return refuse(kind.name, kind.fires ?: "fires: -")
            val failure = evaluation.firstFailure(candidate.require)
            return refuse(candidate.id, candidate.guard ?: failure?.let(::render).orEmpty(), failure)
        }
        val messageClass = classify(kind)
        // What waits for Bora (contracts.md §2.2, Amendment 15): a class-B item — but Bora's own message never waits for Bora.
        val deadline = kind.deadline?.let { duration -> now()?.let { plus(it, duration) } ?: return refuse(kind.name, "deadline: $duration") }
        return Decision.create(
            Outcome.DELIVER, rule = fired.rule(), unsupplied = evaluation.unsupplied.toList(), moves = fired.moves, counted = evaluation.moved.toMap(),
            effects = fired.effects, messageClass = messageClass, waitsForBora = messageClass == MessageClass.B && message.from != BORA, deadline = deadline,
            observed = evaluation.observed.toMap(), attested = evaluation.attested(), answers = answers(kind, message),
        )
    }

    /** The waiting message this delivery answers (contracts.md §4.2): the one its `re` names, when it waits and its kind lists this one in `replies`. */
    private fun answers(kind: Kind, message: View): String? = message.re?.takeIf { re ->
        state.deadlines[re]?.let { wait -> kind.name in rules.kinds[wait.kind]?.replies.orEmpty() } == true
    }

    /**
     * What the engine enforces for every kind with no requirement written (contracts.md §4.1, §2.1): the sender's
     * role owns the kind, `args` against the declaration, the body's line cap, the thread's hop cap, `max_fires`
     * within the window — `null` is `caps.max_fires_default`, and no count on a kind only the mailroom sends — and the addressee, when a seat, a robot or Bora names
     * one: an emit the rules declare is addressed by the rule that declares it. A refusal quotes the rule file's field.
     */
    private fun everyKind(kind: Kind, message: View): Pair<String, Requirement?>? {
        if (kind.from.none { roleOwns(it, message.from) }) return "from: ${kind.from.joinToString(" | ")}" to null
        arguments(kind, message.args)?.let { return it to null }
        val bodyLines = cap(BODY_LINES)
        if (bodyLines != null && lines(message.body).toBigDecimal() > bodyLines) return "caps.$BODY_LINES: ${bodyLines.toPlainString()}" to null
        val hops = cap(HOPS)
        if (hops != null && state.hops(message.thread).toBigDecimal() >= hops) return "caps.$HOPS: ${hops.toPlainString()}" to null
        val maximum = maximum(rules, kind)
        if (maximum != null && fires(state, kind, message.from, message.stage, ::now).toBigDecimal() >= maximum) {
            val written = if (kind.maxFires != null) "max_fires: ${kind.maxFires}" else "caps.$MAX_FIRES_DEFAULT: ${maximum.toPlainString()}"
            return "$written, window: ${kind.window.key}" to null
        }
        if (!declared(message) && !addressed(kind.to, message)) return "to: ${describe(kind.to)}" to null
        return null
    }

    /**
     * A ticket is answered once (contracts.md §2.2, §6): a signed message whose `re` names an item every ticket of which
     * is answered is a second answer. Its number — the last raised for the item — or null when this is no second answer.
     */
    private fun answeredTicket(message: View): Long? {
        val item = message.re?.takeIf { message.signed } ?: return null
        val tickets = state.tickets.filterValues { it.item == item }
        return tickets.keys.maxOrNull()?.takeIf { tickets.values.all { it.answeredBy != null } }
    }

    /**
     * An addressee no seat, robot or Bora named (contracts.md §4.1, §2.1): an emit the rules declare — `by` a
     * transition's effect or a kind's expiry — or the one message the mailroom sends of itself, `by: mailroom`.
     */
    private fun declared(message: View): Boolean = message.by == MAILROOM || DECLARED.any(message.by::startsWith)

    /**
     * The addressee is a party the kind's `to` names (contracts.md §4.1): a seat of one of its roles, or one of its
     * parties; the sender of the message this one answers, for `{re: from}`; any seat — not a party — for `a seat`.
     */
    private fun addressed(to: Destination, message: View): Boolean = when (to) {
        is Destination.Parties -> to.names.any { roleOwns(it, message.to) }
        is Destination.Reply -> (evaluation.value(Value.Reply(to.field)) as? Datum.Text)?.value == message.to
        Destination.AnySeat -> message.to !in PARTIES && rules.engagement.roles.keys.any { roleOwns(it, message.to) }
    }

    private fun cap(name: String) = (rules.engagement.caps[name] as? Literal.Number)?.value

    /** A body's lines, as a reader counts them: a last line end opens no line. */
    private fun lines(body: String?): Int = body?.lines()?.let { if (it.last().isEmpty()) it.size.dec() else it.size } ?: 0

    /** `args` against the kind's declaration (contracts.md §2.1): an unknown key is refused, then each declared one. */
    private fun arguments(kind: Kind, args: Datum.Fields): String? {
        args.fields.keys.firstOrNull { it !in kind.args }?.let { return "args: '$it' is not declared" }
        kind.args.forEach { (name, spec) ->
            val value = args.fields[name]
            if (value == null && spec.required) return "args: '$name' is required"
            if (value != null && !typed(value, spec)) return "args: '$name' is not ${describe(spec)}"
        }
        return null
    }

    private fun typed(value: Datum, spec: ArgSpec): Boolean {
        if (spec.enum != null && spec.enum!!.map(::literal).none { it == value }) return false
        val type = when (spec.type) {
            null -> true
            ArgType.STRING -> value is Datum.Text
            ArgType.ARRAY -> value is Datum.Items
            ArgType.OBJECT -> value is Datum.Fields
            ArgType.BOOLEAN -> value is Datum.Bool
            ArgType.INTEGER -> value is Datum.Number && value.value.stripTrailingZeros().scale() <= 0
            ArgType.NUMBER -> value is Datum.Number
        }
        val items = spec.items?.let { item -> (value as? Datum.Items)?.items?.all { typed(it, item) } } ?: true
        val fields = spec.requiredFields.all { it in ((value as? Datum.Fields)?.fields ?: return spec.requiredFields.isEmpty()) }
        return type && items && fields
    }

    private fun describe(spec: ArgSpec): String = spec.enum?.joinToString(" | ", "one of ") { render(it) } ?: spec.type?.key ?: "valid"

    private fun classify(kind: Kind): MessageClass = classOf(rules, kind, evaluation)

    /**
     * `on: op-result`, `on: expiry`, `on: fact` — the same evaluation; an expiry adds its kind's `expiry` effects, and
     * says whether the wait goes on: its new deadline, or null when it is over.
     */
    private fun fire(): Decision {
        val kind = context.kind?.let(rules.kinds::get)
        val fired = fireMachines(kind)
        val expiry = if (context.event == Event.EXPIRY && kind != null) effects(kind.expiry.filter { it !is Effect.Transition && it !is Effect.Wait }, kind.name) else emptyList()
        val deadline = kind?.takeIf { context.event == Event.EXPIRY }?.let { waitAgain(it, fired.moves) }
        val effects = fired.effects + expiry
        val reported = expiry.filterIsInstance<Requested.Report>().firstOrNull()
        return Decision.create(
            if (fired.moves.isEmpty() && effects.isEmpty()) Outcome.NOTHING else Outcome.FIRE,
            rule = fired.rule() ?: kind?.name?.takeIf { effects.isNotEmpty() }, unsupplied = evaluation.unsupplied.toList(),
            moves = fired.moves, counted = evaluation.moved.toMap(), effects = effects, messageClass = reported?.messageClass,
            waitsForBora = reported != null, deadline = deadline, observed = evaluation.observed.toMap(),
        )
    }

    /**
     * `{wait: again}` (contracts.md §4.2, Amendment 13): when one of the kind's holds, read after the expiry's
     * transitions moved their counters, the wait starts again for the kind's deadline, counted from now. A wait holds
     * only when this expiry fired a transition that moved a counter its `if` reads: an expiry that counts nothing —
     * the stage in no state the transition starts from — ends the wait, as does one with no `now`.
     */
    private fun waitAgain(kind: Kind, moves: List<Move>): String? {
        val counted = moves.flatMap { it.counted.keys }.toSet()
        val again = kind.expiry.filterIsInstance<Effect.Wait>().any { wait ->
            val condition = wait.condition ?: return@any false
            countersRead(condition).any { it in counted } && evaluation.holds(condition)
        }
        if (!again) return null
        return kind.deadline?.let { duration -> now()?.let { plus(it, duration) } }
    }

    /** The counters a requirement reads, wherever it reads one. */
    private fun countersRead(requirement: Requirement): Set<String> = when (requirement) {
        is Requirement.Counter -> setOf(requirement.name)
        is Requirement.Any -> requirement.items.flatMap(::countersRead).toSet()
        is Requirement.Not -> countersRead(requirement.item)
        else -> emptySet()
    }

    private class Fired(val moves: List<Move>, val effects: List<Requested>, val first: Pair<Transition, String>?) {
        /** The rule a `delivered` record names: the stage machine's transition, else the first that fired. */
        fun rule(): String? = (moves.firstOrNull { it.machine == STAGE_MACHINE } ?: moves.firstOrNull())?.rule
    }

    /**
     * Each machine in turn (contracts.md §4.1, choosing among transitions): its candidates for this trigger, from its
     * subject's state, in file order; the first whose requirements are all met fires, and its effects apply before the next
     * machine is read. An expiry's `{transition: …, if: …}` keeps a candidate only while its `if` holds.
     */
    private fun fireMachines(kind: Kind?): Fired {
        val gated = kind?.takeIf { context.event == Event.EXPIRY }?.expiry?.filterIsInstance<Effect.Transition>()?.associateBy { it.id }.orEmpty()
        val moves = ArrayList<Move>()
        val effects = ArrayList<Requested>()
        var first: Pair<Transition, String>? = null
        rules.lifecycle.machines.values.forEach { machine ->
            val subject = machines.subject(machine, context) ?: return@forEach
            val candidates = machines.candidates(machine, subject, context).filter { gated[it.id]?.condition?.let(evaluation::holds) ?: true }
            if (first == null) first = candidates.firstOrNull()?.let { it to subject }
            val selected = candidates.firstOrNull { evaluation.firstFailure(it.require) == null } ?: return@forEach
            val from = machines.state(machines.recordName(machine.name), subject) ?: return@forEach
            val to = machines.destination(selected, machine, subject) ?: return@forEach
            val before = evaluation.moved.toMap()
            effects += effects(selected.effects, selected.id)
            moves += Move(machines.recordName(machine.name), subject, from, to, selected.id, evaluation.moved.filter { before[it.key] != it.value })
        }
        return Fired(moves, effects, first)
    }

    /** Effects in order, each `if` decided as it is reached; a count moves its counter for the effects after it. */
    private fun effects(effects: List<Effect>, rule: String): List<Requested> = effects.mapNotNull { effect ->
        if (effect.condition?.let(evaluation::holds) == false) return@mapNotNull null
        when (effect) {
            is Effect.Count -> null.also { evaluation.count(effect.counter) }
            is Effect.Emit -> Requested.Emit(rule, effect.kind, (evaluation.value(effect.to) as? Datum.Text)?.value, resolved(effect.args))
            is Effect.Do -> Requested.Do(rule, effect.operation, resolved(effect.with))
            is Effect.Report -> Requested.Report(rule, effect.to, effect.messageClass)
            is Effect.Transition, is Effect.Wait -> null
        }
    }

    private fun resolved(values: Map<String, Value>): Map<String, Datum> = values.mapValues { evaluation.value(it.value) ?: Datum.Null }

    private fun now(): String? = (evaluation.fact(FactCall(NOW, emptyMap())) as? Datum.Text)?.value
}

private fun day(at: String): String = utc(at).toLocalDate().toString()
private fun before(at: String, since: String): Boolean = utc(at).isBefore(utc(since))
private fun plus(at: String, duration: Duration): String =
    utc(at).plusSeconds(duration.inWholeSeconds).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z")

/**
 * How many times [kind] may fire in its window (contracts.md §4.2): its `max_fires`; `null` is `caps.max_fires_default`
 * — but on a kind only the mailroom sends, each of its emits is bounded by the rule that declares it (Amendment 16),
 * and there is no count.
 */
internal fun maximum(rules: RuleSet, kind: Kind): java.math.BigDecimal? =
    kind.maxFires?.toBigDecimal() ?: (rules.engagement.caps[MAX_FIRES_DEFAULT] as? Literal.Number)?.value?.takeUnless { kind.from == listOf(MAILROOM) }

/**
 * [kind]'s deliveries in the window a message [from] about [stage] falls in — the stage's, or the sender's own. [now]
 * is read only for a window of a day: a judgement observes what it reads.
 */
internal fun fires(state: State, kind: Kind, from: String, stage: String?, now: () -> String?): Int {
    val earlier = state.delivered.values.filter { it.kind == kind.name }
    val bySender = earlier.filter { it.from == from }
    return when (kind.window) {
        Window.STAGE -> earlier.count { it.stage == stage }
        Window.DAY -> now()?.let { day(it) }?.let { today -> bySender.count { day(it.at) == today } } ?: bySender.size
        Window.RETURN -> state.machine(PRESENCE_MACHINE, BORA)?.since?.let { since -> bySender.count { !before(it.at, since) } } ?: bySender.size
        Window.SESSION -> state.sessions[from]?.let { since -> bySender.count { !before(it.at, since) } } ?: bySender.size
    }
}

/** A kind's `to`, as a refusal quotes it and `whoami` shows it (contracts.md §4.1, §5). */
internal fun describe(to: Destination): String = when (to) {
    is Destination.Parties -> to.names.joinToString(" | ")
    is Destination.Reply -> "{re: ${to.field.key}}"
    Destination.AnySeat -> "a seat"
}

/**
 * The facts a judgement may read and the table must supply, found by reading the rules, not by judging. With a
 * [robot], `{robot: …}` reads its thresholds (contracts.md §8).
 */
internal class Needs(private val rules: RuleSet, private val state: State, private val context: Context, robot: Robot? = null) {
    private val evaluation = Evaluation(rules, state, context, Facts.NONE, robot)
    private val machines = Machines(rules, state)
    val needs = LinkedHashSet<Need>()

    fun collect(): List<Need> {
        needs += Need(NOW, emptyMap())
        val kind = context.kind?.let(rules.kinds::get)
        if (context.event == Event.MESSAGE && kind != null) {
            kind.require.forEach(::requirement)
            classCases(kind)
            heldClass()
        }
        if (context.event == Event.EXPIRY && kind != null) kind.expiry.forEach(::effect)
        rules.lifecycle.machines.values.forEach { machine ->
            val subject = machines.subject(machine, context) ?: return@forEach
            machines.candidates(machine, subject, context).forEach { transition ->
                transition.require.forEach(::requirement)
                transition.effects.forEach(::effect)
            }
        }
        return needs.toList()
    }

    /** What deciding the kind's class reads. */
    fun classCases(kind: Kind) = kind.classRule.cases.forEach { requirement(it.condition) }

    /** What a `{re: class}` of a held message reads: its kind's class cases, in its own context. */
    fun heldClass() {
        val held = context.replyTo?.takeIf { context.replyHeld } ?: return
        val kind = rules.kinds[held.kind] ?: return
        needs += Needs(rules, state, evaluation.heldContext(held)).apply { classCases(kind) }.needs
    }

    fun effect(effect: Effect) {
        effect.condition?.let(::requirement)
        when (effect) {
            is Effect.Emit -> (effect.args.values + effect.to).forEach(::value)
            is Effect.Do -> effect.with.values.forEach(::value)
            else -> Unit
        }
    }

    fun requirement(requirement: Requirement) {
        when (requirement) {
            is Requirement.Fact -> { call(requirement.fact); requirement.comparison?.value?.let(::value) }
            is Requirement.Arg -> requirement.comparison?.value?.let(::value)
            is Requirement.Message -> requirement.comparison?.value?.let(::value)
            is Requirement.Reply -> requirement.comparison?.value?.let(::value)
            is Requirement.Counter -> requirement.comparison?.value?.let(::value)
            is Requirement.Any -> requirement.items.forEach(::requirement)
            is Requirement.Not -> requirement(requirement.item)
            is Requirement.Holds -> rules.engagement.blocks[requirement.block]?.filter { it !is Requirement.Holds }?.forEach(::requirement)
            else -> Unit
        }
    }

    fun value(value: Value) {
        when (value) {
            is Value.Fact -> call(value.fact)
            is Value.Items -> value.items.forEach(::value)
            is Value.Fields -> value.fields.values.forEach(::value)
            else -> Unit
        }
    }

    /** A fact the table must supply becomes a need; one the engine answers is not, but the facts in its parameters are. */
    private fun call(call: FactCall): Need? {
        call.params.values.forEach(::value)
        if (evaluation.answeredByEngine(call.name)) return null
        val params = call.params.mapValues { (_, param) ->
            when (param) {
                is Value.Fact -> if (evaluation.answeredByEngine(param.fact.name)) evaluation.value(param)?.let { Need.Param.Known(it) } else call(param.fact)?.let { Need.Param.Of(it) }
                else -> evaluation.value(param)?.let { Need.Param.Known(it) }
            } ?: return null
        }
        return Need(call.name, params).also { needs += it }
    }
}
