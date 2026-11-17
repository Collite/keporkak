package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Message
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * An office on a throwaway project without a socket — for the slices below the T1 specs: master's rules (or a copy of
 * them, [root]), the facts of `h1-prime.yaml`'s table, operations that end `ok` at once, no seat, a clock the spec moves.
 */
class TestOffice(val root: Path = checkout, private val project: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "office-")) : AutoCloseable {
    val walk = Walk.read(checkout.resolve("rules/walks/h1-prime.yaml"))
    val facts = TableFacts(walk.facts)
    @Volatile var now: Instant = Instant.parse("2026-11-17T09:00:00Z")
    var office = start()
        private set
    var commands = KpCommands(office, facts)
        private set

    private fun start() = Office.start("panda", project, root, facts, Operations { _, done -> done(OperationResult.Ok()) }, NoSeats, Clock { now })

    fun restart() {
        office.close()
        office = start()
        commands = KpCommands(office, facts)
    }

    fun send(seat: String, input: String): Answer = office.submit(Sender.Seat(seat), json(input))

    /** Runs a `kp` command as [peer] and collects what it prints. */
    fun kp(vararg args: String, peer: Peer = Peer("bora")): Ran {
        val lines = ArrayList<String>()
        val code = commands.run(Request(args.first(), args.drop(1), tty = false), peer) { lines += it }
        return Ran(code, lines.joinToString("") { "$it\n" })
    }

    fun messages(): List<Message> = office.lines().map { it.entry }.filterIsInstance<Message>()

    override fun close() = office.close()

    companion object {
        /** A copy of the checkout's rule files — rules, robots, pool, personas — that a spec may edit. */
        fun copyOfTheRules(): Path {
            val copy = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "rules-")
            for (folder in listOf("rules", "robots", "pool")) {
                checkout.resolve(folder).listDirectoryEntries("*.yaml").forEach { it.copyTo(copy.resolve(folder).createDirectories().resolve(it.name)) }
            }
            checkout.resolve("agents").listDirectoryEntries().filter { it.isDirectory() && it.resolve("AGENT.md").isRegularFile() }
                .forEach { it.resolve("AGENT.md").copyTo(copy.resolve("agents/${it.name}").createDirectories().resolve("AGENT.md")) }
            return copy
        }
    }
}

object NoSeats : Deliveries {
    override fun idle(seat: String) = false
    override fun wake(seat: String, message: Message) = false
}

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** The TeamLead's proposal of `h1-prime.yaml`, for [stage]. */
fun proposal(stage: String = "KP/P2/S2.1") =
    """{"kind": "assignment-proposal", "stage": "$stage", "subject": "coder-1 for $stage", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}"""
