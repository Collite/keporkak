@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kp.daemon.Played
import kp.daemon.Projection
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.engine.Datum
import kp.engine.State
import kp.engine.fold
import kp.engine.settled
import kp.log.counted
import kp.model.Envelope
import kp.model.Fact
import kp.model.Judged
import kp.model.Message
import kp.model.Op
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
 * `interrupted` (contracts.md §7), and the run ends where the rules put that failure — or, a kind of its own,
 * otherwise. A wake is an operation since S2.4 (Amendment 22): a cut after a judgement that woke seats, before each
 * wake's `ok`, has those wakes in flight — one for each seat — and the start wakes each, idle with its mail unread, again.
 *
 * What a participant does once the daemon is back: a step whose own line is on the log — a seat's or Bora's message,
 * an operation's result, a reported fact — is not done again, since the start carries that line again as if it had just
 * arrived (contracts.md §3, Amendment 23); a step whose line is not there is done, since its sender got no answer. A
 * robot's or the mailroom's message is looked for on the log, as always.
 */
class Restarts private constructor(val cuts: List<Cut>, private val seeds: Long) {
    /** The daemon stopped after line [seq] ([told]); how the run started again there ended; what was in flight there. */
    data class Cut(val seq: Long, val told: String, val ending: String, val flights: List<Flight> = emptyList()) {
        /** Whether the run ended where the uninterrupted one does — what was in flight recorded `interrupted` or not. */
        val endsAsUninterrupted: Boolean get() = ending == SAME || ending.startsWith(IN_FLIGHT) && ending.endsWith(", $AS_UNINTERRUPTED")
    }

    /**
     * An operation the start found in flight at a cut: the operation, what it wakes — a seat, with a message (S2.4), or a
     * robot's own seat in a mode — and the kind of the message that caused it.
     */
    data class Flight(val op: String, val woken: String?, val cause: String) {
        override fun toString() = listOfNotNull(op, woken?.let { "of $it" }, "for $cause").joinToString(" ")
    }

    /** The cuts where every operation in flight is a wake, however many: one for each seat a judgement woke. */
    val wakesInFlight: List<Cut> get() = cuts.filter { cut -> cut.flights.isNotEmpty() && cut.flights.all { it.op == SEAT_WAKE } }

    /** The cuts of no named kind: neither the same end, nor an operation in flight. */
    val otherwise: List<Cut> get() = cuts.filter { it.ending != SAME && !it.ending.startsWith(IN_FLIGHT) }

    /** What `just hero` prints of the restart: the counts, then each cut. */
    fun report(): List<String> {
        val same = cuts.count { it.ending == SAME }
        val inFlight = cuts.count { it.ending.startsWith(IN_FLIGHT) }
        return listOf("the restart: H-1′ stopped after each of the ${cuts.size} lines its daemon writes (the $seeds seeded before it kept) — " +
            "$same same end, $inFlight an operation in flight (${wakesInFlight.size} of them a wake), ${otherwise.size} otherwise") +
            cuts.map { cut -> "  after line ${cut.seq} (${cut.told}): ${cut.ending}" + cut.flights.takeIf { it.isNotEmpty() }?.joinToString(prefix = " — in flight at the cut: ").orEmpty() }
    }

    companion object {
        const val SAME = "same end"
        const val IN_FLIGHT = "an operation in flight"
        const val SEAT_WAKE = "seat.wake"
        const val AS_UNINTERRUPTED = "and the walk ends as uninterrupted"
        private const val INTERRUPTED = "interrupted"
        private const val REQUESTED = "requested"
        private const val SEAT = "seat"
        private const val ROLE = "role"
        private const val MODE = "mode"

        /** [walk] stopped after each line its daemon writes — or after those [only] picks. */
        fun afterEveryLineOf(walk: String, only: (Long) -> Boolean = { true }): Restarts {
            val run = Hero(walk).use { hero ->
                hero.player.rest()
                val lines = hero.office.lines().map { it.entry }
                Uninterrupted(files(hero.project), hero.player.played.toList(), Ending.of(hero.office.state, hero.office.lines().counted().map { it.entry }), lines)
            }
            // The lines before the daemon's first — what the harness seeds before it starts — are no cut of its: from its `rules` record on, every line is.
            val lines = run.log.values.flatten().sortedBy { it.first }.dropWhile { "\"event\":\"rules\"" !in it.second }
            val arrivals = run.played.associate { played -> played.number - 1 to arrival(played, run.lines) }.filterValues { it != null }.mapValues { it.value!! }
            val cuts = lines.filter { only(it.first) }.map { (seq, text) -> cut(walk, run, seq, arrivals).let { (ending, flights) -> Cut(seq, summary(text), ending, flights) } }
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

        /**
         * The daemon stopped after line [cut]: started again on lines 1..[cut], the walk taken up where they leave it. How
         * the run ended, and what the lines it started on left in flight — each operation by what it is, not as told.
         */
        private fun cut(walk: String, run: Uninterrupted, cut: Long, arrivals: Map<Int, Long>): Pair<String, List<Flight>> {
            val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "restart-")
            try {
                run.log.forEach { (path, lines) ->
                    val kept = lines.filter { it.first <= cut }
                    if (kept.isNotEmpty()) project.resolve(path).also { it.parent.createDirectories() }.writeText(kept.joinToString("") { "${it.second}\n" })
                }
                // The world goes on while the daemon is down: the facts of each step begun by the cut stand when it starts again.
                val begun = run.played.indexOfLast { it.first <= cut }
                return Hero(walk, prepared = project, world = { script -> if (begun >= 0) script.worldThrough(begun) }).use { hero ->
                    // What the start finds in flight (contracts.md §7, Amendment 23): the operations requested with no result
                    // through the last judgement the cut closed and the first line after it, which the start carries again —
                    // the rest of a judgement the cut left open is let go with it.
                    val kept = run.lines.filter { it.seq.toLong() <= cut }
                    val carried = minOf(cut, (kept.filterIsInstance<Judged>().maxOfOrNull { it.seq.toLong() } ?: 0L) + 1)
                    val kinds = kept.filterIsInstance<Message>().associate { it.id.value to it.kind }
                    val left = fold(kept.filter { it.seq.toLong() <= carried }).settled().operations.map { Flight(it.op, woken(it.with), kinds[it.cause] ?: it.cause) }
                    ending(hero, run, cut, arrivals) to left
                }
            } finally {
                project.deleteRecursively()
            }
        }

        /** How the run started again on the lines through [cut] ended. */
        private fun ending(hero: Hero, run: Uninterrupted, cut: Long, arrivals: Map<Int, Long>): String {
            val kept = arrivals.filterValues { it <= cut }.keys.associateWith { index -> run.played[index].let { it.message to it.outcome } }
            hero.player.resume(kept)
            val started = hero.office.state
            val flights = hero.office.lines().counted().map { it.entry }.filterIsInstance<Op>().filter { it.seq.toLong() > cut && it.reason == INTERRUPTED }
            val inFlight = flights.joinToString { op -> "${op.op} → ${where(hero, started, op.cause)}" }.let { "$IN_FLIGHT: $it" }
            try {
                hero.player.rest()
            } catch (failure: WalkFailure) {
                // An operation in flight ends where the rules put its failure: the walk cannot give it the result it scripts.
                val failed = hero.script.walk.steps[hero.player.next - 1]
                return when {
                    flights.any { it.op == failed.operation } -> inFlight
                    else -> "otherwise — the walk went otherwise: ${failure.message}"
                }
            }
            val ending = Ending.of(hero.office.state, hero.office.lines().counted().map { it.entry })
            val differences = ending.differences(run.end)
            return when {
                flights.isNotEmpty() -> inFlight + (if (differences.isEmpty()) ", $AS_UNINTERRUPTED" else ", and the walk ends otherwise — $differences")
                differences.isEmpty() -> SAME
                else -> "otherwise — it ends otherwise: $differences"
            }
        }

        /** What a wake wakes: its seat, or a robot's own seat in its mode; null for any other operation. */
        private fun woken(with: Datum.Fields): String? {
            fun text(name: String) = (with.fields[name] as? Datum.Text)?.value
            return text(SEAT) ?: text(ROLE)?.let { role -> "the $role's own seat (${text(MODE)})" }
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
     * still open, the messages held, the tickets open and each party's unread mail — what a re-seat replays (contracts.md
     * §2.2, `read`) — the kinds delivered, in the order they were, and the operations still in flight (issue #39, item 6):
     * a message named by its kind, a thread by its first message's kind.
     */
    data class Ending(
        val machines: Map<String, String>, val counters: Map<String, Long>, val waiting: List<String>, val held: List<String>, val tickets: List<String>,
        val unread: List<String>, val delivered: List<String>, val inFlight: List<String>,
    ) {
        fun differences(other: Ending): String = listOfNotNull(
            diff("states", machines, other.machines), diff("counters", counters, other.counters),
            "waiting $waiting, not ${other.waiting}".takeIf { waiting != other.waiting },
            "held $held, not ${other.held}".takeIf { held != other.held },
            "open tickets $tickets, not ${other.tickets}".takeIf { tickets != other.tickets },
            "unread $unread, not ${other.unread}".takeIf { unread != other.unread },
            "delivered ${delivered.size} kinds in another order or number: ${delivered.zip(other.delivered).indexOfFirst { (a, b) -> a != b }.let { at -> if (at < 0) "${delivered.size} not ${other.delivered.size}" else "at ${at + 1}, ${delivered[at]} not ${other.delivered[at]}" }}"
                .takeIf { delivered != other.delivered },
            "in flight $inFlight, not ${other.inFlight}".takeIf { inFlight != other.inFlight },
        ).joinToString("; ")

        private fun <T> diff(what: String, mine: Map<String, T>, theirs: Map<String, T>): String? =
            (mine.keys + theirs.keys).filter { mine[it] != theirs[it] }.takeIf { it.isNotEmpty() }?.joinToString(", ", "$what ") { "$it ${mine[it]} (not ${theirs[it]})" }

        companion object {
            /**
             * What [of] compares of the state, and what it leaves out today, each with its reason (the retro of P1,
             * candidate 2; S2.8 T4). S2.4 is changing what it compares (issue #39, item 6): this lists it, nothing else.
             */
            val PROJECTION = Projection(
                "where a walk ends (Restarts.Ending)",
                compares = setOf("machines", "counters", "deadlines", "held", "tickets", "unread"),
                leavesOut = mapOf(
                    "seq" to "a start writes lines of its own (`voided`, `rules`): the numbers after it differ, as ids do",
                    "at" to "the time of the last line, which a start's own lines move",
                    "unjudged" to "a judgement left open: where a walk ends, every judgement is closed",
                    "opened" to "a judgement left open: where a walk ends, every judgement is closed",
                    "pending" to "a message not yet judged: where a walk ends, every message is",
                    "hops" to "counts keyed by thread ids, which a restart numbers differently",
                    "answered" to "message ids, which a restart numbers differently; a wait answered is no longer in `deadlines`",
                    "delivered" to "not compared yet: issue #39, item 6 — S2.4 widens what the restart compares",
                    "operations" to "not compared yet: issue #39, item 6 — S2.4 widens what the restart compares",
                    "rules" to "not compared: the start reloads the same files, and nothing here asks that it did — a Found line of S2.8",
                    "refusals" to "not compared: a seat's refusals of the day are not asked for — a Found line of S2.8",
                    "sessions" to "not compared: where a seat's session window begins is not asked for — a Found line of S2.8",
                    "wakes" to "not compared: where a robot's own seat was last woken is not asked for — a Found line of S2.8",
                ),
            )

            fun of(state: State, lines: List<Envelope>): Ending {
                val kinds = lines.filterIsInstance<Message>().associate { it.id.value to it.kind }
                fun named(subject: String) = kinds[subject]?.let { "the $it's thread" } ?: subject
                return Ending(
                    state.machines.flatMap { (machine, subjects) -> subjects.map { (subject, at) -> "$machine ${named(subject)}" to at.state } }.toMap().toSortedMap(),
                    state.counters.flatMap { (subject, values) -> values.map { (name, value) -> "$subject $name" to value } }.toMap().toSortedMap(),
                    state.deadlines.values.map { "${it.kind} ${it.from}→${it.to}" }.sorted(),
                    state.held.keys.map { kinds[it] ?: it }.sorted(),
                    state.openTickets().values.map { kinds[it.item] ?: it.item }.sorted(),
                    state.unread.flatMap { (party, ids) -> ids.map { "${kinds[it] ?: it} to $party" } }.sorted(),
                    state.delivered.values.map { it.kind },
                    state.operations.map { "${it.op} for ${kinds[it.cause] ?: it.cause}" },
                )
            }
        }
    }
}
