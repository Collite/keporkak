@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kp.daemon.Played
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.engine.State
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Fact
import kp.model.Held
import kp.model.Message
import kp.model.Op
import kp.model.Refused
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * The restart (S1.6 T6): a walk's daemon killed after each line of its log and started again on what was written, the
 * walk then played to its end. [linesOfTheWalk] lines, [cuts] restarts; [endingElsewhere] names each cut whose end
 * differs from the walk's uninterrupted end — the line it was cut after, and what went otherwise.
 *
 * What a participant does once the daemon is back: a message the mailroom decided — its `delivered`, `held` or `refused`
 * is on the log — is not sent again; one it never decided is, since its sender got no answer. A robot's message, an
 * operation's result and a reported fact are looked for, or reported, as the walk's steps say.
 */
class Restarts private constructor(val linesOfTheWalk: Int, val cuts: Int, val endingElsewhere: List<String>, private val seeds: Long) {
    /** What `just hero` prints of the restart. */
    fun report(): List<String> = listOf("the restart: $cuts cuts after the daemon's $linesOfTheWalk lines (the $seeds seeded before it kept), " +
        "${cuts - endingElsewhere.size} end as the walk does uninterrupted, ${endingElsewhere.size} otherwise") + endingElsewhere.map { "  $it" }

    companion object {
        fun afterEveryLineOf(walk: String): Restarts {
            val (log, played, end, ids) = Hero(walk).use { hero ->
                hero.player.rest()
                Uninterrupted(files(hero.project), hero.player.played.toList(), Ending.of(hero.office.state, hero.office.lines().map { it.entry }), hero.player.ids.toMap())
            }
            // The lines before the daemon's first — what the harness seeds before it starts — are no cut of its: from its `rules` record on, every line is.
            val lines = log.values.flatten().sortedBy { it.first }.dropWhile { "\"event\":\"rules\"" !in it.second }
            val seeds = lines.first().first - 1
            val elsewhere = lines.mapNotNull { (seq, text) -> cut(walk, log, seq, played, end, ids)?.let { "after line $seq (${summary(text)}): $it" } }
            return Restarts(lines.size, lines.size, elsewhere, seeds)
        }

        private data class Uninterrupted(val log: Map<String, List<Pair<Long, String>>>, val played: List<Played>, val end: Ending, val ids: Map<String, String>)

        /** Each log file of [project] by its path under the project, its lines with their `seq`. */
        private fun files(project: Path): Map<String, List<Pair<Long, String>>> = project.resolve("mail").walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") }
            .associate { file -> file.relativeTo(project).toString() to file.readLines().filter { it.isNotBlank() }.map { seqOf(it) to it } }

        private fun seqOf(line: String): Long = Regex("\"seq\":(\\d+)").find(line)!!.groupValues[1].toLong()

        /** The daemon killed after line [cut]: started again on lines 1..[cut], the walk taken up where they leave it. Null when it ends as uninterrupted. */
        private fun cut(walk: String, log: Map<String, List<Pair<Long, String>>>, cut: Long, played: List<Played>, end: Ending, ids: Map<String, String>): String? {
            val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "restart-")
            try {
                log.forEach { (path, lines) ->
                    val kept = lines.filter { it.first <= cut }
                    if (kept.isNotEmpty()) project.resolve(path).also { it.parent.createDirectories() }.writeText(kept.joinToString("") { "${it.second}\n" })
                }
                return Hero(walk, prepared = project).use { hero ->
                    val written = hero.office.lines().map { it.entry }.filter { it.seq.toLong() <= cut }
                    val index = played.indexOfFirst { !done(it, written) }.let { if (it < 0) played.size else it }
                    val doneSteps = played.take(index)
                    val stage = doneSteps.lastOrNull()?.step?.text("stage")?.let { it } ?: doneSteps.mapNotNull { it.step.text("stage") }.lastOrNull() ?: hero.script.walk.stage
                    hero.player.resume(index, ids.filterKeys { key -> doneSteps.any { it.step.id == key } },
                        doneSteps.filter { it.step.text("by") != null || it.step.who == "mailroom" }.mapNotNull { it.message }.toSet(), stage)
                    try {
                        hero.player.rest()
                    } catch (failure: WalkFailure) {
                        return@use "the walk went otherwise — ${failure.message}"
                    }
                    Ending.of(hero.office.state, hero.office.lines().map { it.entry }).differences(end).takeIf { it.isNotEmpty() }?.let { "it ends otherwise — $it" }
                }
            } finally {
                project.deleteRecursively()
            }
        }

        /**
         * Whether a step is done on the lines kept: a message the mailroom decided (its record is kept), a robot's or the
         * mailroom's message kept, an operation's result kept, a reported fact kept.
         */
        private fun done(step: Played, kept: List<Envelope>): Boolean {
            val id = step.message
            return when {
                step.step.operation != null -> kept.any { it is Op && it.op == step.step.operation && it.cause == id && it.phase != "requested" }
                step.step.fields.containsKey("event") -> kept.any { it is Fact && it.seq.toLong() in step.first..step.last } ||
                    (step.first > step.last) // a step that wrote nothing — Bora acting while present
                id == null -> step.last <= (kept.lastOrNull()?.seq?.toLong() ?: 0)
                step.step.text("by") != null || step.step.who == "mailroom" -> kept.any { it is Message && it.id.value == id }
                else -> kept.any { (it is Delivered && it.id == id) || (it is Held && it.id == id) || (it is Refused && it.id == id) }
            }
        }

        private fun summary(text: String): String {
            fun field(name: String) = Regex("\"$name\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            return field("kind")?.let { "$it ${field("from")}→${field("to")}" } ?: field("event")?.let { event ->
                listOfNotNull(event, field("machine"), field("op"), field("phase"), field("fact"), field("from")?.let { "${it}→${field("to")}" }).joinToString(" ")
            } ?: "?"
        }
    }

    /**
     * Where a walk ends, as a reader compares two ends: each machine's subjects and their states, the counters, the waits
     * still open, the messages held and the tickets open — a message named by its kind, a thread by its first message's kind.
     */
    data class Ending(val machines: Map<String, String>, val counters: Map<String, Long>, val waiting: List<String>, val held: List<String>, val tickets: List<String>) {
        fun differences(other: Ending): String = listOfNotNull(
            diff("states", machines, other.machines), diff("counters", counters, other.counters),
            "waiting $waiting, not ${other.waiting}".takeIf { waiting != other.waiting },
            "held $held, not ${other.held}".takeIf { held != other.held },
            "open tickets $tickets, not ${other.tickets}".takeIf { tickets != other.tickets },
        ).joinToString("; ")

        private fun <T> diff(what: String, mine: Map<String, T>, theirs: Map<String, T>): String? =
            (mine.keys + theirs.keys).filter { mine[it] != theirs[it] }.takeIf { it.isNotEmpty() }?.joinToString(", ", "$what ") { "$it ${mine[it]} (not ${theirs[it]})" }

        companion object {
            fun of(state: State, lines: List<Envelope>): Ending {
                val kinds = lines.filterIsInstance<Message>().associate { it.id.value to it.kind }
                fun named(subject: String) = kinds[subject]?.let { "the $it's thread" } ?: subject
                return Ending(
                    state.machines.flatMap { (machine, subjects) -> subjects.map { (subject, at) -> "$machine ${named(subject)}" to at.state } }.toMap().toSortedMap(),
                    state.counters.flatMap { (subject, values) -> values.map { (name, value) -> "$subject $name" to value } }.toMap().toSortedMap(),
                    state.deadlines.values.map { "${it.kind} ${it.from}→${it.to}" }.sorted(),
                    state.held.keys.map { kinds[it] ?: it }.sorted(),
                    state.openTickets().values.map { kinds[it.item] ?: it.item }.sorted(),
                )
            }
        }
    }
}
