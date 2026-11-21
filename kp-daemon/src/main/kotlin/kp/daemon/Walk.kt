package kp.daemon

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.FactSource
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.time.toJavaDuration
import kp.rules.duration

/**
 * A walk of `rules/walks/` (contracts.md §4.8): a stage, its seats, a table of facts and the steps — who sends which
 * kind with which args, which operation ends how, which facts change. A walk may name more stages (`stages:`), each with
 * the state it starts in, its seat's state and facts of its own; it ends in [final] — one stage's state, or each named
 * stage's — and may count the deliveries of some kinds ([expectedCounts]). Scalars are read as YAML 1.2 reads them.
 */
class Walk(
    val name: String,
    val stage: String,
    val seats: Map<String, String>,
    val facts: Map<String, Datum>,
    val steps: List<Step>,
    /** The state the walk's stages start in, unless a stage says otherwise. */
    val initial: String = PLANNED,
    val stages: Map<String, Setting> = emptyMap(),
    val final: Datum? = null,
    val expectedCounts: Map<String, Int> = emptyMap(),
    /** The seats a rehearsal with live seats leaves to the seats themselves (S2.5): `claude_seats:`, when the walk names them. */
    val claudeSeats: ClaudeSeats? = null,
    /** The folder beside the walk's file named as the file (`thin-thread/` beside `thin-thread.yaml`), when there is one. */
    val fixtures: Path? = null,
    /** The file the walk was read from. */
    val file: Path? = null,
) {
    /** One step, as the walk writes it; [fields] holds every key. */
    class Step(val fields: Map<String, Datum>) {
        fun text(key: String): String? = (fields[key] as? Datum.Text)?.value
        fun fields(key: String): Map<String, Datum> = (fields[key] as? Datum.Fields)?.fields.orEmpty()
        val id: String? get() = text("id")
        val who: String? get() = text("who")
        val kind: String? get() = text("kind")
        val operation: String? get() = text("operation")
        val facts: Map<String, Datum> get() = fields("facts")
        val expect: Map<String, Datum> get() = fields("expect")

        /** This step with [key] set to [value]: what a spec adds to a walk that leaves it out. */
        fun with(key: String, value: Datum): Step = Step(fields + (key to value))
    }

    /** One order of the events the walk's steps mark as unordered (S3.6 T3): their labels, in order, and the walk played so. */
    class Order(val labels: List<String>, val walk: Walk)

    /**
     * Every order of the events the walk's steps mark as unordered (`unordered:`, S3.6 T3), as `check-rules.py` plays them:
     * each step's events in each of their orders — the first as written — and the steps after it in each. In an order's walk
     * an unordered step is its events, one after another, each labelled by its `id` (else `<the step's id>.<n>`) and marked
     * with the step's id ([UNORDERED_IN]). A walk that marks none has one order: itself, with no labels.
     */
    fun orders(): List<Order> {
        var orders = listOf(emptyList<String>() to emptyList<Step>())
        steps.forEachIndexed { at, step ->
            val events = (step.fields[UNORDERED] as? Datum.Items)?.items
            orders = if (events == null) orders.map { (labels, played) -> labels to played + step } else {
                val group = step.id ?: "step-${at + 1}"
                val labelled = events.mapIndexed { number, event ->
                    val each = Step((event as Datum.Fields).fields)
                    val label = each.id ?: "$group.${number + 1}"
                    label to each.with("id", Datum.Text(label)).with(UNORDERED_IN, Datum.Text(group))
                }
                orders.flatMap { (labels, played) -> permutations(labelled.indices.toList()).map { order -> labels + order.map { labelled[it].first } to played + order.map { labelled[it].second } } }
            }
        }
        return orders.map { (labels, played) -> Order(labels, withSteps(played)) }
    }

    /** This walk with its steps replaced by [steps]. */
    fun withSteps(steps: List<Step>): Walk = Walk(name, stage, seats, facts, steps, initial, stages, final, expectedCounts, claudeSeats, fixtures, file)

    /**
     * The walk's Claude seats (S2.5; contracts.md §12, Amendment 27): with live seats, a step whose `who` is one of [seats]
     * is the seat's own — waited for, at most [wait] each, not sent; the seats together may use [tokens] (`seat.usage`).
     */
    class ClaudeSeats(val seats: Set<String>, val wait: java.time.Duration, val tokens: Long)

    /** A stage the walk names besides its own: the state it starts in, its companions' states (`machines:`), its facts. */
    class Setting(val initial: String?, val machines: Map<String, String>, val facts: Map<String, Datum>)

    /** Every stage the walk names, its own first, then those of `stages:`, then those its steps switch to, in order. */
    val stageNames: List<String>
        get() = (listOf(stage) + stages.keys + steps.mapNotNull { it.text("stage") }).distinct()

    /** The state [name] starts in. */
    fun initialOf(name: String): String = stages[name]?.initial ?: initial

    /** The facts [name] starts with: the walk's, then its own. */
    fun factsOf(name: String): Map<String, Datum> = facts + stages[name]?.facts.orEmpty()

    companion object {
        const val PLANNED = "planned"
        /** A step whose events the world does not fix the order of (S3.6 T3). */
        const val UNORDERED = "unordered"
        /** On a step of an order's walk: the id of the unordered step it is an event of. */
        const val UNORDERED_IN = "unordered_in"

        /** Every order of [items], lexicographic by their places — the first as given — as Python's `itertools.permutations`. */
        fun <T> permutations(items: List<T>): List<List<T>> =
            if (items.size <= 1) listOf(items) else items.indices.flatMap { at -> permutations(items.take(at) + items.drop(at + 1)).map { listOf(items[at]) + it } }

        fun read(path: Path): Walk {
            val walk = (datum(Yaml.default.parseToYamlNode(path.readText())) as Datum.Fields).fields
            fun text(key: String) = (walk[key] as? Datum.Text)?.value
            fun fields(datum: Datum?) = (datum as? Datum.Fields)?.fields.orEmpty()
            return Walk(
                text("name") ?: path.fileName.toString(),
                text("stage") ?: error("$path names no stage"),
                fields(walk["seats"]).mapValues { (it.value as Datum.Text).value },
                fields(walk["facts"]),
                (walk["steps"] as? Datum.Items)?.items.orEmpty().map { Step((it as Datum.Fields).fields) },
                text("initial") ?: PLANNED,
                fields(walk["stages"]).mapValues { (_, setting) ->
                    val values = fields(setting)
                    Setting((values["initial"] as? Datum.Text)?.value, fields(values["machines"]).mapValues { (it.value as Datum.Text).value }, fields(values["facts"]))
                },
                walk["final"],
                fields(walk["expected_counts"]).mapValues { (it.value as Datum.Number).value.toInt() },
                (walk["claude_seats"] as? Datum.Fields)?.fields?.let { claude ->
                    ClaudeSeats(
                        (claude["seats"] as? Datum.Items)?.items.orEmpty().map { (it as Datum.Text).value }.toSet(),
                        (claude["wait"] as? Datum.Text)?.value?.let(::duration)?.toJavaDuration() ?: error("$path: claude_seats names no wait"),
                        (claude["tokens"] as? Datum.Number)?.value?.toLong() ?: error("$path: claude_seats names no tokens"),
                    )
                },
                path.resolveSibling(path.fileName.toString().removeSuffix(".yaml")).takeIf { it.isDirectory() },
                path.toAbsolutePath().normalize(),
            )
        }

        private val integer = Regex("-?(0|[1-9][0-9]*)")
        private val decimal = Regex("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][-+]?[0-9]+)?")

        /** A YAML text as data, its scalars read as YAML 1.2 reads them. */
        fun parse(text: String): Datum = datum(Yaml.default.parseToYamlNode(text))

        private fun datum(node: YamlNode): Datum = when (node) {
            is YamlMap -> Datum.Fields(node.entries.entries.associate { (key, value) -> key.content to datum(value) })
            is YamlList -> Datum.Items(node.items.map(::datum))
            is YamlNull -> Datum.Null
            is YamlTaggedNode -> datum(node.innerNode)
            is YamlScalar -> scalar(node.content)
        }

        private fun scalar(content: String): Datum = when {
            content in setOf("~", "null", "Null", "NULL") -> Datum.Null
            content.equals("true", ignoreCase = true) -> Datum.Bool(true)
            content.equals("false", ignoreCase = true) -> Datum.Bool(false)
            integer.matches(content) || decimal.matches(content) -> Datum.Number(BigDecimal(content))
            else -> Datum.Text(content)
        }
    }
}

/**
 * Facts from a walk's table, looked up the walker's way (`check-rules.py`): a plain value, or a map keyed by the first
 * parameter the fact is asked with of `of`, `seat`, `id`, `dimension`, `subscription`, `pointer`. A fact not in the
 * table is unknown.
 */
class TableFacts(initial: Map<String, Datum> = emptyMap()) : FactSource {
    private val table = ConcurrentHashMap(initial)

    fun set(values: Map<String, Datum>) = table.putAll(values)

    /** The value the table holds for [name], as written. */
    operator fun get(name: String): Datum? = table[name]

    override fun observe(fact: FactRef, stage: String?): Datum? = lookup(table[fact.name], fact)

    companion object {
        private val KEYS = listOf("of", "seat", "id", "dimension", "subscription", "pointer")

        /** [value] as [fact] reads it: plain, or keyed by the first of its parameters the walker keys by. */
        fun lookup(value: Datum?, fact: FactRef): Datum? {
            if (value !is Datum.Fields) return value
            val key = KEYS.firstNotNullOfOrNull { fact.params[it] } ?: return null
            return value.fields[(key as? Datum.Text)?.value ?: key.json()]
        }
    }
}

/**
 * The facts of a walk that names several stages (`stages:`): each stage has a table of its own — the walk's facts,
 * then the stage's — and a step's facts change the table of the stage it is about, as the walker's contexts do. A fact
 * asked about no stage, or a stage the walk does not name, is read from the stage the walk is at.
 */
class WalkFacts(private val walk: Walk, private val ids: Map<String, String>) : FactSource {
    private val tables = ConcurrentHashMap<String, TableFacts>()
    @Volatile var current: String = walk.stage

    /** The table of the stage named [name] in the walk. */
    fun table(name: String): TableFacts = tables.getOrPut(name) { TableFacts(walk.factsOf(name)) }

    override fun observe(fact: FactRef, stage: String?): Datum? {
        val name = ids.entries.firstOrNull { it.value == stage }?.key ?: current
        return table(name).observe(fact, stage)
    }
}
