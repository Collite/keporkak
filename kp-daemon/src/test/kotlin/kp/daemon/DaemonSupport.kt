@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon

import kp.model.Delivered
import kp.model.Envelope
import kp.model.Message
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
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

/**
 * A rehearsal of a walk on a throwaway project (S1.5 T1's seam): the daemon's control socket, a `kp` run as a
 * subprocess, and the peer of each connection read through [SimulatedPeers]. It starts paused where the walk first needs
 * Bora.
 */
class Rehearsed(walk: String = "h1-prime") : AutoCloseable {
    val project: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "rehearsal-")
    /** A Unix socket's path has at most 107 bytes: this one is under the system's temporary folder. */
    private val sockets: Path = Files.createTempDirectory("kp-")
    val socket: Path = sockets.resolve("kp.sock")
    val peers = SimulatedPeers()
    val said = CopyOnWriteArrayList<String>()
    val rehearsal: Rehearsal = Rehearsal.start(checkout, checkout.resolve("rules/walks/$walk.yaml"), project, OFFICE, socket, peers) { said += it }
    val waitingFor: String? = rehearsal.awaitBora()

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

    /** A copy of the daemon's log in a folder of its own, to edit. */
    fun copyOfTheLog(): Path = Files.createTempDirectory(project, "copy-").also { project.resolve("mail").copyToRecursively(it.resolve("mail"), followLinks = false, overwrite = false) }.resolve("mail")

    /** A hand log of [lines] in the project's `mail/KP/_hand/`. */
    fun handLog(name: String, lines: String): Path =
        project.resolve("mail/KP/_hand").createDirectories().resolve(name).also { it.writeText(lines.trimIndent() + "\n") }

    override fun close() {
        rehearsal.close()
        sockets.toFile().deleteRecursively()
    }

    companion object {
        const val OFFICE = "panda"
    }
}

/** A log read back with kp-model's reader. */
class Log(folder: Path) {
    val lines: List<Envelope> = folder.walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") && "_hand" !in it.toString() }
        .flatMap { file -> file.readLines().map(::parseLine) }.sortedBy { it.seq }.toList()

    fun messages(kind: String): List<Message> = lines.filterIsInstance<Message>().filter { it.kind == kind }

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
