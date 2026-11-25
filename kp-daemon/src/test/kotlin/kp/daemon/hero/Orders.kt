package kp.daemon.hero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.daemon.Walk
import kp.daemon.checkout
import kp.engine.Datum
import kp.model.Delivered
import kp.model.Message
import kp.model.Op
import kp.model.Refused
import kp.rules.Effect
import kp.rules.RuleSet
import kp.rules.Scope
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A walk played once per order of the events its steps mark as unordered (S3.6 T3), each order through the mailroom on a
 * daemon of its own, and the walker's end of the same order beside it — `check-rules.py --trace`'s `END` line.
 */
class Orders(val walk: String, val played: List<Played>) {
    /**
     * An end, as the walker's `END` holds it (S3.10 T5; Collite/keporkak#56): each stage's state, by its name in the walk,
     * and each of the walk's seats'; the counters that are not zero, by stage name — an effort's under `effort`; the
     * messages delivered and refused, counted by kind; every operation the mailroom requested, `<op> (<kind> <seat>)` by
     * the message that asked it, whatever kind that is (S3.11 T8, Collite/keporkak#67, 2) — but the mailroom's own, which
     * no walk's step asks: `notify.bora`, a robot's wake of its own seat in a mode, and a seat's wake for a delivery or a
     * turn's end, a `seat.wake` no transition of its cause's kind names — and those of them still waiting for their result.
     *
     * What the walker's end holds beside, and this leaves out: a stage's flag thread, the state it returns to, its Found
     * lines and its facts; the seats' bindings; Bora's presence and the phase; how often a kind fired in its window; each
     * record whole (its args, its class, its tick) and an operation's result; what a robot sent that no step delivered.
     * The walker keeps them to judge the walk's own steps by; the mailroom keeps them in its own words, or not at all
     * (`unsent`: what the mailroom sends, it delivers or refuses). Each record is counted by its kind here, the operations
     * named as the walker names them.
     */
    data class End(
        val stages: Map<String, String>,
        val seats: Map<String, String>,
        val counters: Map<String, Map<String, Int>> = emptyMap(),
        val delivered: Map<String, Int> = emptyMap(),
        val refused: Map<String, Int> = emptyMap(),
        val operations: List<String> = emptyList(),
        val waiting: List<String> = emptyList(),
    ) {
        /** Each part in which this end and [other] differ: `<part> <this>, the walker's <other>`. */
        fun against(other: End?): List<String> {
            if (other == null) return listOf("the walker has no end of this order")
            return listOf(
                "stages" to (stages to other.stages), "seats" to (seats to other.seats), "counters" to (counters to other.counters),
                "delivered" to (delivered to other.delivered), "refused" to (refused to other.refused),
                "operations" to (operations to other.operations), "waiting" to (waiting to other.waiting),
            ).filter { (_, both) -> both.first != both.second }.map { (part, both) -> "$part ${both.first}, the walker's ${both.second}" }
        }
    }

    /** One order: the labels of its events, in the order played; how it ended through the mailroom, and in the walker. */
    data class Played(val labels: List<String>, val end: End, val walkers: End?)

    /** What `just hero` prints of it: with an order that ends otherwise than the walker's, the first, and what differs. */
    fun report(): String {
        val otherwise = played.filter { it.end != it.walkers }
        return "$walk: ${played.size} order${if (played.size == 1) "" else "s"} through the mailroom, " +
            if (otherwise.isEmpty()) "each ending as the walker's"
            else "${otherwise.size} ending otherwise than the walker's — in the order ${otherwise.first().labels.joinToString(", ")}: " +
                otherwise.first().end.against(otherwise.first().walkers).joinToString("; ")
    }

    companion object {
        private const val NOTIFY_BORA = "notify.bora"
        private const val SEAT_WAKE = "seat.wake"

        /** [name] — a walk of `rules/walks/`, or of the specs' own walks — in each of its orders. */
        fun of(name: String): Orders = of(Hero.walkFile(name))

        /** [file] in each of its orders; [walker] the root whose `scripts/check-rules.py` and `rules/` give the walker's ends. */
        fun of(file: Path, walker: Path = checkout): Orders {
            val walk = Walk.read(file)
            val walkers = walker(file, walker)
            val played = walk.orders().map { order ->
                val hero = Hero(file.name.removeSuffix(".yaml"), amend = { order.walk })
                hero.use {
                    hero.rest()
                    Played(order.labels, ended(hero, walk), walkers[order.labels])
                }
            }
            return Orders(walk.name, played)
        }

        /** The mailroom's end of a walk [hero] played, as [End] holds it. */
        private fun ended(hero: Hero, walk: Walk): End {
            val rules = hero.office.rules.ruleSet
            val state = hero.office.state
            val lines = hero.office.counted()
            val messages = lines.filterIsInstance<Message>().associateBy { it.id.value }
            // A step the walk expects refused leaves nothing in the walker's end — it puts its state back — so its refusal is
            // not counted here either.
            val expectedRefused = hero.player.played.filter { it.step.expect["refused"] == Datum.Bool(true) }.mapNotNull { it.message }.toSet()
            val asked = asked(rules)
            // Every operation requested, whatever kind caused it — an operation no transition of that kind names is counted, and
            // the walker's end has none of it (S3.11 T8) — but the mailroom's own, known by their source: Bora's notification,
            // a robot's wake of its own seat in a mode (`with.role`), and a seat's wake no transition of its cause's kind names.
            val requested = lines.filterIsInstance<Op>().filter { op ->
                op.phase == "requested" && when (op.op) {
                    NOTIFY_BORA -> false
                    SEAT_WAKE -> op.with?.get("role") == null && messages[op.cause]?.let { SEAT_WAKE in asked[it.kind].orEmpty() } == true
                    else -> true
                }
            }
            val answered = lines.filterIsInstance<Op>().filter { it.phase != "requested" }.map { it.op to it.cause }.toSet()
            fun named(op: Op) = described(op.op, messages[op.cause]?.let { it.kind to (it.args?.get("seat") as? JsonPrimitive)?.contentOrNull }, op.cause)
            val stageCounters = rules.lifecycle.counters.values.filter { it.scope == Scope.STAGE }.map { it.name }
            val effortCounters = rules.lifecycle.counters.values.filter { it.scope == Scope.EFFORT }.map { it.name }
            val counters = walk.stageNames.associateWith { name -> stageCounters.associateWith { state.counter(hero.stage(name), it).toInt() }.filterValues { it != 0 } } +
                ("effort" to effortCounters.associateWith { state.counter(hero.stage().substringBefore('/'), it).toInt() }.filterValues { it != 0 })
            return End(
                stages = walk.stageNames.associateWith { name -> state.stage(hero.stage(name)) ?: Walk.PLANNED },
                seats = walk.seats.keys.associateWith { seat -> state.machine("seat", seat)?.state ?: "provisioned" },
                counters = counters.filterValues { it.isNotEmpty() },
                delivered = lines.filterIsInstance<Delivered>().mapNotNull { messages[it.id]?.kind }.groupingBy { it }.eachCount(),
                refused = lines.filterIsInstance<Refused>().filter { it.id !in expectedRefused }.mapNotNull { messages[it.id]?.kind }.groupingBy { it }.eachCount(),
                operations = requested.map(::named).sorted(),
                waiting = requested.filter { (it.op to it.cause) !in answered }.map(::named).sorted(),
            )
        }

        /** By kind, the operations a transition's `do:` may request — a kind's expiry's too: what the walker's `operations` names. */
        private fun asked(rules: RuleSet): Map<String?, Set<String>> {
            val transitions = rules.lifecycle.machines.values.flatMap { it.transitions }.map { it.kind to it.effects }
            val expiries = rules.kinds.map { (kind, definition) -> kind to definition.expiry }
            return (transitions + expiries).groupBy({ it.first }, { it.second }).mapValues { (_, effects) ->
                effects.flatten().filterIsInstance<Effect.Do>().map { it.operation }.toSet()
            }
        }

        /** An operation as the walker names it: `<op> (<kind> <seat>)` — by the message that asked it, else by what asked it. */
        private fun described(op: String, asker: Pair<String, String?>?, cause: String): String =
            "$op (" + (asker?.let { (kind, seat) -> kind + (seat?.let { " $it" } ?: "") } ?: cause) + ")"

        /**
         * Every walk of `rules/walks/` that marks unordered events, or whose steps name two seats or more in one stage
         * (`args.seat`, `trigger.seat`), by its file's name.
         */
        fun ofTheWalksOnMaster(): List<Orders> = checkout.resolve("rules/walks").listDirectoryEntries("*.yaml").sorted()
            .filter { file -> Walk.read(file).let { walk -> walk.orders().size > 1 || walk.seatsOfAStage() > 1 } }
            .map(::of)

        /** The walker's end of each order of [file], by the labels of its events, as [End] holds it. */
        private fun walker(file: Path, root: Path): Map<List<String>, End> {
            val python = checkout.resolve("build/python/bin/python")
            check(python.exists()) { "run `just _python-env` first" }
            val process = ProcessBuilder(python.toString(), root.resolve("scripts/check-rules.py").toString(), "--trace", file.toString())
                .directory(root.toFile()).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            check(process.waitFor(10, TimeUnit.MINUTES)) { "the walker did not end" }
            val failures = lines.filter { it.startsWith("FAIL walk ") }
            check(failures.isEmpty()) { failures.joinToString("\n") }
            return lines.filter { it.startsWith("END ") }.associate { line ->
                val end = Json.parseToJsonElement(line.removePrefix("END ")).jsonObject
                val labels = end.getValue("orders").jsonArray.flatMap { order -> order.jsonArray[1].jsonArray.map { it.jsonPrimitive.content } }
                labels to walkersEnd(end.getValue("end").jsonObject)
            }
        }

        /** The walker's `END`, as [End] holds it: what its records say, counted and named as the mailroom's are. */
        private fun walkersEnd(at: JsonObject): End {
            fun records(key: String): List<JsonObject> = at.getValue(key).jsonArray.map { Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject }
            fun kinds(records: List<JsonObject>, status: String) = records.filter { it.text("status") == status }.mapNotNull { it.text("kind") }
            // A message refused where an unordered step's events sent it is in the log; one let go and then refused, in `messages`.
            val refused = kinds(records("log"), "refused") + kinds(records("messages"), "refused")
            return End(
                stages = at.texts("stages"),
                seats = at.texts("seats"),
                counters = at.getValue("counters").jsonObject.mapValues { (_, values) -> values.jsonObject.mapValues { it.value.jsonPrimitive.int } },
                delivered = kinds(records("log"), "delivered").groupingBy { it }.eachCount(),
                refused = refused.groupingBy { it }.eachCount(),
                operations = at.getValue("operations").jsonArray.map { it.jsonPrimitive.content }.sorted(),
                waiting = at.getValue("waiting").jsonArray.map { Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject }.map { waiting ->
                    val asker = waiting["asked by"]?.let(::asker)
                    described(waiting.text("operation")!!, asker?.let { it.text("kind")!! to (it["args"] as? JsonObject)?.text("seat") }, waiting.text("asked by") ?: "?")
                }.sorted(),
            )
        }

        /** What asked an operation in the walker's `waiting`: the message, described as JSON — or the id of an event, which no message is. */
        private fun asker(element: JsonElement): JsonObject? = (element as? JsonPrimitive)?.contentOrNull?.let { text ->
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.takeIf { "kind" in it }
        }

        private fun JsonObject.texts(key: String): Map<String, String> = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/** The most seats the steps of this walk name — by `args.seat` or `trigger.seat` — in one of its stages. */
private fun Walk.seatsOfAStage(): Int {
    var stage = this.stage
    val named = HashMap<String, MutableSet<String>>()
    steps.forEach { step ->
        stage = step.text("stage") ?: stage
        listOf(step.fields("args")["seat"], step.fields("trigger")["seat"]).forEach { seat ->
            (seat as? kp.engine.Datum.Text)?.let { named.getOrPut(stage) { HashSet() } += it.value }
        }
    }
    return named.values.maxOfOrNull { it.size } ?: 0
}
