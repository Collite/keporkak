package kp.daemon.hero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.daemon.Walk
import kp.daemon.checkout
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
    /** An end: each stage's state, by its name in the walk, and each of the walk's seats'. */
    data class End(
        val stages: Map<String, String>,
        val seats: Map<String, String>,
        val counters: Map<String, Map<String, Int>> = emptyMap(),
        val delivered: Map<String, Int> = emptyMap(),
        val refused: Map<String, Int> = emptyMap(),
        val operations: List<String> = emptyList(),
        val waiting: List<String> = emptyList(),
    )

    /** One order: the labels of its events, in the order played; how it ended through the mailroom, and in the walker. */
    data class Played(val labels: List<String>, val end: End, val walkers: End?)

    /** What `just hero` prints of it. */
    fun report(): String = "$walk: ${played.size} order${if (played.size == 1) "" else "s"} through the mailroom, " +
        (played.count { it.end == it.walkers }.let { same -> if (same == played.size) "each ending as the walker's" else "${played.size - same} ending otherwise than the walker's" })

    companion object {
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
                    val end = End(
                        walk.stageNames.associateWith { name -> hero.office.state.stage(hero.stage(name)) ?: Walk.PLANNED },
                        walk.seats.keys.associateWith { seat -> hero.office.state.machine("seat", seat)?.state ?: "provisioned" },
                    )
                    Played(order.labels, end, walkers[order.labels])
                }
            }
            return Orders(walk.name, played)
        }

        /**
         * Every walk of `rules/walks/` that marks unordered events, or whose steps name two seats or more in one stage
         * (`args.seat`, `trigger.seat`), by its file's name.
         */
        fun ofTheWalksOnMaster(): List<Orders> = checkout.resolve("rules/walks").listDirectoryEntries("*.yaml").sorted()
            .filter { file -> Walk.read(file).let { walk -> walk.orders().size > 1 || walk.seatsOfAStage() > 1 } }
            .map(::of)

        /** The walker's end of each order of [file], by the labels of its events. */
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
                val at = end.getValue("end").jsonObject
                labels to End(at.texts("stages"), at.texts("seats"))
            }
        }

        private fun JsonObject.texts(key: String): Map<String, String> = getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }
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
