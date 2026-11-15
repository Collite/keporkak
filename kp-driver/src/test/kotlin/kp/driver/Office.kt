package kp.driver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kp.driver.claude.ClaudeAdapter
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.HexFormat
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * One spec's office, in a temporary directory: the seats' root (config homes and `_transcripts/`), a worktree, a
 * secrets folder with a Claude token that is random and never printed, an injected clock, and every fact and dead
 * token the driver reported. The agent is always the scripted fake (FakeAgent.kt).
 */
class Office : AutoCloseable {
    val root: Path = Files.createTempDirectory("kp-driver-spec-")
    val seats: Path = root.resolve("seats").createDirectories()
    val worktree: Path = root.resolve("worktrees/KP-P2-S2.1").createDirectories()
    val tokenFile: Path = root.resolve("secrets/claude-token")
    val claudeToken: String = HexFormat.of().formatHex(ByteArray(24).also(SecureRandom()::nextBytes))
    val clock = MutableClock(Instant.parse("2026-11-15T10:00:00Z"))
    val facts: MutableList<ObservedFact> = Collections.synchronizedList(mutableListOf())
    val deadTokens: MutableList<SessionToken> = Collections.synchronizedList(mutableListOf())
    private val drivers = mutableListOf<Driver>()
    private var agents = 0

    init {
        tokenFile.parent.createDirectories()
        Files.createFile(tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        tokenFile.writeText(claudeToken + "\n")
    }

    val listener = object : Listener {
        override fun observed(fact: ObservedFact) { facts += fact }
        override fun tokenDead(seat: SeatId, token: SessionToken) { deadTokens += token }
    }

    fun driver(answerer: Answerer = Answerer { Answer.DENY }): Driver =
        ClaudeAdapter.driver(tokenFile, seats, clock, answerer, listener).also { drivers += it }

    /** A fake agent following [script] (JSON, without its `record`); its command and what it received. */
    fun agent(script: String): FakeAgentProcess {
        val record = root.resolve("agent-${++agents}.received.jsonl")
        val file = root.resolve("agent-$agents.script.json")
        val fields = Json.parseToJsonElement(script).jsonObject + ("record" to JsonPrimitive(record.absolutePathString()))
        file.writeText(JsonObject(fields).toString())
        FakeAgents.records.add(record)
        return FakeAgentProcess(fakeAgentCommand(file), record)
    }

    fun session(
        seat: String,
        agent: FakeAgentProcess,
        model: String = "claude-haiku-5-5",
        effort: String = "low",
        servers: List<McpServer> = listOf(McpServer("kp-mail", "http://127.0.0.1:7711/mcp", carriesToken = true)),
    ) = SessionSpec(
        seat = SeatId(seat),
        command = agent.command,
        workingDirectory = worktree,
        configHome = seats.resolve(seat).createDirectories(),
        model = model,
        effort = effort,
        servers = servers,
        environment = mapOf("LANG" to "C.UTF-8"),
    )

    fun transcript(seat: String): List<String> = seats.resolve("_transcripts/$seat.log").let { if (it.exists()) it.readLines() else emptyList() }

    override fun close() {
        drivers.forEach { it.close() }
        root.toFile().deleteRecursively()
    }
}

/** A fake agent's command line, and what it received, read from its record file. */
class FakeAgentProcess(val command: List<String>, private val record: Path) {
    fun received(): List<JsonObject> = if (record.exists()) record.readLines().map { Json.parseToJsonElement(it).jsonObject } else emptyList()
    fun received(event: String): List<JsonObject> = received().filter { (it["event"] as JsonPrimitive).content == event }
    fun pids(): List<Long> = received().mapNotNull { (it["pid"] as? JsonPrimitive)?.content?.toLong() }
}

/** Every fake agent the specs started; checked when the specs end (ProjectConfig). */
object FakeAgents {
    val records: MutableList<Path> = Collections.synchronizedList(mutableListOf())
    fun alive(): List<Long> = records.flatMap { FakeAgentProcess(emptyList(), it).pids() }.filter { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
}

fun fakeAgentCommand(script: Path): List<String> = listOf(
    Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString(),
    "-Xshare:auto", "-XX:TieredStopAtLevel=1",
    "-cp", System.getProperty("kp.driver.fake.classpath") ?: error("kp.driver.fake.classpath is not set (kp-driver/build.gradle.kts)"),
    "kp.driver.fake.FakeAgentKt",
    script.absolutePathString(),
)

fun pidAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

/** The injected clock: it moves only when a spec moves it. */
class MutableClock(@Volatile private var now: Instant) : Clock() {
    fun advance(by: Duration) { now = now.plus(by) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
