@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kp.daemon.Played
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.engine.State
import kp.log.counted
import kp.model.Envelope
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kp.model.Read
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
 * The restart (S1.10 T7, from S1.6's `fdec0a4`): a walk's daemon stopped after each line it writes and started again
 * on what was written, the walk then taken up and played to its end. Each [Cut] says how that run ended: [SAME] — where
 * the uninterrupted run ends — or [IN_FLIGHT] — an operation was in flight, the start recorded it `failed`,
 * `interrupted` (contracts.md §7), and the run ends where the rules put that failure — or [WAKE_IN_FLIGHT] — the cut
 * fell after a judgement that woke a seat and before its `read`: a start wakes no one again, and the message stays
 * unread until S2.4 gives a wake its record (D-76, Amendment 22) — or, a kind of its own, otherwise.
 *
 * What a participant does once the daemon is back: a step whose own line is on the log — a seat's or Bora's message,
 * an operation's result, a reported fact — is not done again, since the start carries that line again as if it had just
 * arrived (contracts.md §3, Amendment 23); a step whose line is not there is done, since its sender got no answer. A
 * robot's or the mailroom's message is looked for on the log, as always.
 */
class Restarts private constructor(val cuts: List<Cut>, private val seeds: Long) {
    /** The daemon stopped after line [seq] ([told]); how the run started again there ended. */
    data class Cut(val seq: Long, val told: String, val ending: String)

    /** The cuts after a wake, before its `read`: the message stays unread (D-76). */
    val wakesInFlight: List<Cut> get() = cuts.filter { it.ending.startsWith(WAKE_IN_FLIGHT) }

    /** The cuts of no named kind: neither the same end, nor an operation in flight, nor a wake in flight. */
    val otherwise: List<Cut> get() = cuts.filter { it.ending != SAME && !it.ending.startsWith(IN_FLIGHT) && !it.ending.startsWith(WAKE_IN_FLIGHT) }

    /** What `just hero` prints of the restart: the counts, then each cut. */
    fun report(): List<String> {
        val same = cuts.count { it.ending == SAME }
        val inFlight = cuts.count { it.ending.startsWith(IN_FLIGHT) }
        return listOf("the restart: H-1′ stopped after each of the ${cuts.size} lines its daemon writes (the $seeds seeded before it kept) — " +
            "$same same end, $inFlight an operation in flight, ${wakesInFlight.size} a wake in flight, ${otherwise.size} otherwise") +
            cuts.map { "  after line ${it.seq} (${it.told}): ${it.ending}" }
    }

    companion object {
        const val SAME = "same end"
        const val IN_FLIGHT = "an operation in flight"
        const val WAKE_IN_FLIGHT = "a wake in flight"
        private const val INTERRUPTED = "interrupted"
        private const val REQUESTED = "requested"

        /** [walk] stopped after each line its daemon writes — or after those [only] picks. */
        fun afterEveryLineOf(walk: String, only: (Long) -> Boolean = { true }): Restarts {
            val run = Hero(walk).use { hero ->
                hero.player.rest()
                val lines = hero.office.lines().map { it.entry }
                Uninterrupted(files(hero.project), hero.player.played.toList(), Ending.of(hero.office.state, lines), lines)
            }
            // The lines before the daemon's first — what the harness seeds before it starts — are no cut of its: from its `rules` record on, every line is.
            val lines = run.log.values.flatten().sortedBy { it.first }.dropWhile { "\"event\":\"rules\"" !in it.second }
            val arrivals = run.played.associate { played -> played.number - 1 to arrival(played, run.lines) }.filterValues { it != null }.mapValues { it.value!! }
            val cuts = lines.filter { only(it.first) }.map { (seq, text) -> Cut(seq, summary(text), cut(walk, run, seq, arrivals)) }
            return Restarts(cuts, lines.first().first - 1)
        }

        private class Uninterrupted(val log: Map<String, List<Pair<Long, String>>>, val played: List<Played>, val end: Ending, val lines: List<Envelope>)

        /** Each log file of [project] by its path under the project, its lines with their `seq`. */
        private fun files(project: Path): Map<String, List<Pair<Long, String>>> = project.resolve("mail").walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") }
            .associate { file -> file.relativeTo(project).toString() to file.readLines().filter { it.isNotBlank() }.map { seqOf(it) to it } }

        private fun seqOf(line: String): Long = Regex("\"seq\":(\\d+)").find(line)!!.groupValues[1].toLong()

        /**
         * The `seq` of a participant's own line in the uninterrupted run — the message a seat or Bora sent, the result the
         * walk gave an operation, the fact it reported — or, for Bora acting while present, which writes nothing, the last
         * line before it. Null for a robot's or the mailroom's message, which is looked for, not sent.
         */
        private fun arrival(played: Played, lines: List<Envelope>): Long? {
            val step = played.step
            val window = lines.filter { it.seq.toLong() in played.first..played.last }
            return when {
                step.text("by") != null || step.who == "mailroom" -> null
                step.operation != null -> window.firstOrNull { it is Op && it.op == step.operation && it.cause == played.message && it.phase != REQUESTED }?.seq?.toLong()
                step.fields.containsKey("event") -> window.firstOrNull { it is Fact }?.seq?.toLong() ?: (played.first - 1).takeIf { played.first > played.last }
                played.message != null -> lines.firstOrNull { it is Message && it.id.value == played.message }?.seq?.toLong()
                else -> null
            }
        }

        /** The daemon stopped after line [cut]: started again on lines 1..[cut], the walk taken up where they leave it. */
        private fun cut(walk: String, run: Uninterrupted, cut: Long, arrivals: Map<Int, Long>): String {
            val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "restart-")
            try {
                run.log.forEach { (path, lines) ->
                    val kept = lines.filter { it.first <= cut }
                    if (kept.isNotEmpty()) project.resolve(path).also { it.parent.createDirectories() }.writeText(kept.joinToString("") { "${it.second}\n" })
                }
                // The world goes on while the daemon is down: the facts of each step begun by the cut stand when it starts again.
                val begun = run.played.indexOfLast { it.first <= cut }
                return Hero(walk, prepared = project, world = { script -> if (begun >= 0) script.worldThrough(begun) }).use { hero ->
                    val kept = arrivals.filterValues { it <= cut }.keys.associateWith { index -> run.played[index].let { it.message to it.outcome } }
                    hero.player.resume(kept)
                    val started = hero.office.state
                    val flights = hero.office.lines().counted().map { it.entry }.filterIsInstance<Op>().filter { it.seq.toLong() > cut && it.reason == INTERRUPTED }
                    val inFlight = flights.joinToString { op -> "${op.op} → ${where(hero, started, op.cause)}" }.let { "$IN_FLIGHT: $it" }
                    val wake = wakeInFlight(run, cut, started)
                    try {
                        hero.player.rest()
                    } catch (failure: WalkFailure) {
                        // An operation in flight ends where the rules put its failure: the walk cannot give it the result it scripts.
                        val failed = hero.script.walk.steps[hero.player.next - 1]
                        return@use when {
                            flights.any { it.op == failed.operation } -> inFlight
                            wake != null -> "$wake; the walk went otherwise: ${failure.message}"
                            else -> "otherwise — the walk went otherwise: ${failure.message}"
                        }
                    }
                    val differences = Ending.of(hero.office.state, hero.office.lines().map { it.entry }).differences(run.end)
                    when {
                        flights.isNotEmpty() -> inFlight + (if (differences.isEmpty()) ", and the walk ends as uninterrupted" else ", and the walk ends otherwise — $differences")
                        differences.isEmpty() -> SAME
                        wake != null -> "$wake; it ends otherwise: $differences"
                        else -> "otherwise — it ends otherwise: $differences"
                    }
                }
            } finally {
                project.deleteRecursively()
            }
        }

        /**
         * A wake in flight at [cut] (D-76): the uninterrupted run's next line is a seat's `read` — what it wrote once the
         * judgement before it, which woke that seat, was on disk — and the start left those messages unread. Null when not.
         */
        private fun wakeInFlight(run: Uninterrupted, cut: Long, started: State): String? {
            val read = run.lines.firstOrNull { it.seq.toLong() == cut + 1 } as? Read ?: return null
            if (!started.unread[read.seat].orEmpty().containsAll(read.ids)) return null
            val kinds = run.lines.filterIsInstance<Message>().associate { it.id.value to it.kind }
            return "$WAKE_IN_FLIGHT: the message stays unread — ${read.ids.joinToString { kinds[it] ?: it }} to ${read.seat}"
        }

        /** Where the start left the stage the message [cause] is about: its name in the walk and its state. */
        private fun where(hero: Hero, state: State, cause: String): String {
            val stage = state.delivered[cause]?.stage ?: state.pending[cause]?.stage ?: return "nothing moved"
            val name = hero.script.stages.entries.firstOrNull { it.value == stage }?.key ?: stage
            return "$name ${state.stage(stage) ?: "planned"}"
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
