package kp.daemon.seats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * The Claude adapter as a seat's agent (contracts.md §9, §10; architecture.md §5): `node` running the pinned adapter
 * installed in [adapter] by `just adapter`. Its environment is built, not inherited: `PATH` with node's folder, the office
 * user's `HOME`, `LANG`. The driver's binding adds the config home and the Claude token.
 */
class ClaudeAgent(private val adapter: Path, private val home: Path = Path.of(System.getProperty("user.home"))) : Agent {
    private val node: Path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }.map { Path.of(it, "node") }.firstOrNull { it.isExecutable() }
        ?: Path.of("/usr/bin/node")

    override fun command(seat: String, role: String): List<String> = listOf(node.toString(), adapter.resolve("node_modules/$ADAPTER/dist/index.js").toString())

    override fun environment(seat: String): Map<String, String> =
        mapOf("PATH" to "${node.parent}:/usr/local/bin:/usr/bin:/bin", "HOME" to home.toString(), "LANG" to "C.UTF-8")

    companion object {
        const val ADAPTER = "@agentclientprotocol/claude-agent-acp"
    }
}

/**
 * S2.1's fake agent as every seat's agent (`kp.driver.fake.FakeAgentKt`, the driver's test fixtures): what the daemon's
 * specs and `just rehearse <walk> --seats fake` start. Each seat follows its script `<folder>/<seat>.json` — written
 * here when there is none: every prompt ends its turn at once, the roster's efforts are offered, and the process calls
 * the stop hook with its token before anything else — and records what it received in `<folder>/<seat>.jsonl`.
 */
class FakeAgents(private val classpath: String, val folder: Path) : Agent {
    private val java: Path = Path.of(System.getProperty("java.home"), "bin", "java")

    override fun command(seat: String, role: String): List<String> =
        listOf(java.toString(), "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", classpath, MAIN, script(seat).toString())

    override fun environment(seat: String): Map<String, String> = mapOf("LANG" to "C.UTF-8")

    /** [seat]'s script: its own when one was written for it, else the default. */
    fun script(seat: String): Path {
        val file = folder.createDirectories().resolve("$seat.json")
        if (!file.exists()) file.writeText(JsonObject(mapOf("record" to JsonPrimitive(record(seat).toString()), "probe" to JsonPrimitive(true),
            "efforts" to Json.parseToJsonElement(EFFORTS))).toString())
        return file
    }

    /** Where [seat]'s fake agents record what they received, one JSON object a line. */
    fun record(seat: String): Path = folder.resolve("$seat.jsonl")

    /** What [seat]'s fake agents received, in order. */
    fun received(seat: String): List<JsonObject> = record(seat).takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    /** The pid of [seat]'s last fake agent. */
    fun pid(seat: String): Long? = received(seat).lastOrNull { (it["event"] as? JsonPrimitive)?.contentOrNull == "start" }?.let { (it["pid"] as? JsonPrimitive)?.longOrNull }

    /** [seat]'s agent dies — its process tree ended from outside, as a crash would; false when none runs. */
    fun die(seat: String): Boolean {
        val process = pid(seat)?.let { ProcessHandle.of(it).orElse(null) }?.takeIf { it.isAlive } ?: return false
        process.descendants().forEach { it.destroyForcibly() }
        return process.destroyForcibly()
    }

    companion object {
        const val MAIN = "kp.driver.fake.FakeAgentKt"
        private const val EFFORTS = """["low", "medium", "high", "xhigh", "max"]"""

        /** A secrets folder for fake seats: the Claude token file the driver's binding reads, holding no token. */
        fun secrets(folder: Path): Path = folder.createDirectories().also { Files.writeString(it.resolve("claude-token"), "no-token-the-fake-agent-needs-none\n") }
    }
}
