@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon

import kp.mailroom.Clock
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Fact
import kp.model.Held
import kp.model.Message
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

val checkout: Path = Path.of(System.getProperty("kp.root"))

/** Who the spec says the next connections come from: an ordinary peer (the office user), or root — simulated, never needed. */
class SimulatedPeers : PeerReader {
    @Volatile var root = false
    override fun peer(channel: SocketChannel): Peer = Peer(if (root) "root" else "bora")
}

/** What one run of `kp` printed, and its exit code. */
data class Ran(val code: Int, val text: String) {
    /** The line `kp status` gives Bora's presence on. */
    val presence: String get() = text.lines().first { it.startsWith("Bora: ") }.removePrefix("Bora: ")
}

/** The wall clock, moved on by what a spec lets pass: [pass] a day, and the daemon's "now" is a day later. */
class MovingClock : Clock {
    @Volatile private var moved: Duration = Duration.ZERO

    override fun now(): Instant = Instant.now().plus(moved).truncatedTo(ChronoUnit.SECONDS)

    fun pass(time: Duration) {
        moved = moved.plus(time)
    }
}

/**
 * A rehearsal of a walk on a throwaway project (S1.5 T1's seam): the daemon's control socket, a `kp` run as a
 * subprocess, and the peer of each connection read through [SimulatedPeers]. It starts paused where the walk first needs
 * Bora. The walk is one of `rules/walks/`, or one of this module's own (`src/test/resources/walks/`); the rules are
 * the checkout's, or those under [root].
 */
class Rehearsed(walk: String = "h1-prime", root: Path = checkout) : AutoCloseable {
    val project: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "rehearsal-")
    /** A Unix socket's path has at most 107 bytes: this one is under the system's temporary folder. */
    private val sockets: Path = Files.createTempDirectory("kp-")
    val socket: Path = sockets.resolve("kp.sock")
    val peers = SimulatedPeers()
    val said = CopyOnWriteArrayList<String>()
    val clock = MovingClock()
    val rehearsal: Rehearsal = Rehearsal.start(root, walkFile(walk), project, OFFICE, socket, peers, clock) { said += it }
    val waitingFor: String? = rehearsal.awaitBora()

    /** Lets [time] pass on the daemon's clock and fires the deadlines that have then passed, as its timers would. */
    fun pass(time: Duration) {
        clock.pass(time)
        rehearsal.office.tick()
    }

    /** Runs `scripts/kp` with [args]; with [root], the daemon reads its peer as root — `sudo kp …`, simulated. */
    fun kp(vararg args: String, root: Boolean = false): Ran {
        peers.root = root
        try {
            val process = ProcessBuilder(listOf(checkout.resolve("scripts/kp").toString()) + args).redirectErrorStream(true)
                .apply { environment()["KP_SOCKET"] = socket.toString() }.start()
            val text = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "kp ${args.joinToString(" ")} did not end" }
            return Ran(process.exitValue(), text)
        } finally {
            peers.root = false
        }
    }

    /** Every line of the daemon's log (not the hand logs), in `seq` order. */
    fun log(): Log = Log(project.resolve("mail"))

    /** The id of the [nth] message of [kind] (S3.0 T5). */
    fun id(kind: String, nth: Int = 0): String = idOf(log().lines, kind, nth)

    /** The ticket the message [id] opened: the `seq` of the line that opens it (S3.0 T5). */
    fun ticket(id: String): Long = ticketOf(log().lines, id)

    /** A copy of the daemon's log in a folder of its own, to edit. */
    fun copyOfTheLog(): Path = Files.createTempDirectory(project, "copy-").also { project.resolve("mail").copyToRecursively(it.resolve("mail"), followLinks = false, overwrite = false) }.resolve("mail")

    /** A hand log of [lines] in the project's `mail/KP/_hand/`. */
    fun handLog(name: String, lines: String): Path =
        project.resolve("mail/KP/_hand").createDirectories().resolve(name).also { it.writeText(lines.trimIndent() + "\n") }

    override fun close() {
        rehearsal.close()
        sockets.toFile().deleteRecursively()
        Judgements.check(project)
    }

    companion object {
        const val OFFICE = "panda"

        private fun walkFile(walk: String): Path =
            checkout.resolve("rules/walks/$walk.yaml").takeIf { it.exists() } ?: checkout.resolve("kp-daemon/src/test/resources/walks/$walk.yaml")
    }
}

/**
 * The id of the [nth] message of [kind] on [lines] (S3.0 T5): a message's id is its line's `seq` (contracts.md §2), which
 * moves with the rules and walks every stage shares — a spec reads it from the log, and pins no number.
 */
fun idOf(lines: List<Envelope>, kind: String, nth: Int = 0): String = lines.filterIsInstance<Message>().filter { it.kind == kind }[nth].id.value

/**
 * The ticket the message [id] opened, as contracts.md §2 numbers it: the `seq` of the line that opens it — its delivery or
 * its hold. Read from the line's place in the log, not from its `ticket`: a spec that compares the two shows that
 * the mailroom numbers a ticket as the contract says, and pins no number the shared rules and walks move (S3.0 T5).
 */
fun ticketOf(lines: List<Envelope>, id: String): Long =
    lines.first { (it is Delivered && it.id == id && it.ticket != null) || (it is Held && it.id == id && it.ticket != null) }.seq.toLong()

/** A log read back with kp-model's reader. */
class Log(folder: Path) {
    val lines: List<Envelope> = folder.walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") && "_hand" !in it.toString() }
        .flatMap { file -> file.readLines().map(::parseLine) }.sortedBy { it.seq }.toList()

    fun messages(vararg kinds: String): List<Message> = lines.filterIsInstance<Message>().filter { it.kind in kinds }

    fun message(id: String): Message = lines.filterIsInstance<Message>().single { it.id.value == id }

    /**
     * The log as a reader tells it: each message's fate (`digest by robot:DEP-8 delivered`), each move of Bora's
     * presence, each fact that triggered something, each expiry.
     */
    fun story(): List<String> = lines.mapNotNull { line ->
        fun told(id: String, what: String): String = message(id).let { it.kind + (it.by.takeIf { by -> by.startsWith("robot:") || by == "mailroom" }?.let { by -> " by $by" } ?: "") + " " + what }
        when (line) {
            is Delivered -> told(line.id, "delivered")
            is Held -> told(line.id, "held by ${line.by}")
            is Refused -> told(line.id, "refused")
            is Expired -> told(line.id, "expired")
            is Fact -> "fact ${line.fact}"
            is Transition -> if (line.machine == "presence") "presence ${line.from}→${line.to}" else null
            else -> null
        }
    }

    /** What happened to [message], as its record and the transitions it caused say. */
    fun outcome(message: Message): String {
        val id = message.id.value
        lines.filterIsInstance<Refused>().firstOrNull { it.id == id }?.let { return "refused: ${it.requirement}" }
        lines.filterIsInstance<Delivered>().firstOrNull { it.id == id } ?: return "no record"
        return "delivered: " + lines.filterIsInstance<Transition>().filter { it.cause == id }.joinToString(", ") { "${it.rule} ${it.from}→${it.to}" }
    }
}

/** In [folder]'s files, replaces [from] with [to] on the one line that holds both [marker] and [from]. */
fun edit(folder: Path, marker: String, from: String, to: String) {
    val file = folder.walk().single { file -> file.isRegularFile() && file.readLines().any { marker in it && from in it } }
    file.writeLines(file.readLines().map { if (marker in it && from in it) it.replace(from, to) else it })
}

/**
 * Every line belongs to a judgement (S1.10's DONE; S2.7 T7, issue #39 item 5): what a spec's harness checks of its
 * project's log when it closes it — a rehearsal, a test office, a hero, a restart, a case of the sweep — and what the
 * check after each spec (ProjectConfig) reads.
 */
object Judgements {
    private val problems = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Checks the daemon's log in [project]; what is wrong waits for the check after the spec. */
    fun check(project: Path) {
        val mail = project.resolve("mail")
        if (mail.exists()) problems += outsideAJudgement(Log(mail).lines).map { "${project.fileName}: $it" }
    }

    /** What the checks since the last call found, and none kept. */
    fun drain(): List<String> = problems.toList().also { problems.clear() }

    /**
     * Every line that no `judged` closes and no `voided` voids, and a log that does not end with a `judged` line: a
     * judgement runs from its first line to the `judged` naming it; a `voided` names the judgement before it and opens
     * the one that carries its first line again (contracts.md §2.2, §3).
     */
    fun outsideAJudgement(lines: List<Envelope>): List<String> {
        val problems = ArrayList<String>()
        var first: Long? = null
        lines.forEach { line ->
            val seq = line.seq.toLong()
            when (line) {
                is kp.model.Judged -> {
                    if (line.first.toLong() != first) problems += "$seq judged names ${line.first}, not the first line of its judgement ($first)"
                    first = null
                }
                is kp.model.Voided -> {
                    if (first == null || line.first.toLong() != first || line.last.toLong() != seq - 1) problems += "$seq voided names ${line.first}–${line.last}, not the judgement before it"
                    first = seq
                }
                else -> if (first == null) first = seq
            }
        }
        if (lines.isNotEmpty() && lines.last() !is kp.model.Judged) problems += "${lines.last().seq}: the log does not end with a judged line"
        return problems
    }
}
