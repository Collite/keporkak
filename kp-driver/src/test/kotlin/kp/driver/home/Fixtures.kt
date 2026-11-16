package kp.driver.home

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kp.driver.Answer
import kp.driver.OfferedOption
import kp.driver.PermissionRequest
import kp.driver.ok
import kp.model.SeatId
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A path of the keporkak checkout the specs run in. */
fun root(path: String): Path = Path.of(System.getProperty("kp.root")).resolve(path)

/**
 * A temporary keporkak checkout for the builder — a copy of master's personas, renders, skills, packaging data,
 * profiles and hooks, which a spec may edit — a seats' root, and one session's places.
 */
class Sources : AutoCloseable {
    val root: Path = Files.createTempDirectory("kp-home-spec-")
    val seats: Path = root.resolve("seats").createDirectories()
    val places = Places(
        worktree = root.resolve("office/_wt/keporkak-S2.2").createDirectories(),
        scratch = root.resolve("office/_scratch/keporkak/S2.2").createDirectories(),
        project = root.resolve("office/project").createDirectories(),
        ownFile = root.resolve("office/project/tasks-p2-s2.2.md"),
        codeRepos = listOf(root.resolve("office/keporkak"), root.resolve("office/_wt")),
        mcpListen = "127.0.0.1:7711",
    )

    init {
        for (folder in listOf("agents", "renders", "skills", "docs/profiles", "hooks")) copy(root(folder), root.resolve(folder))
    }

    /** The names in [folder], sorted. */
    fun names(folder: Path): List<String> = folder.listDirectoryEntries().map { it.name }.sorted()

    /** Rewrites the persona of [role]. */
    fun persona(role: String, edit: (String) -> String) {
        val file = root.resolve("agents/$role/AGENT.md")
        file.writeText(edit(file.readText()))
    }

    /**
     * `permissions.json` for one `deny:` word, compiled from the packaging data for [profile] with the table's places
     * (permission-table.tsv), written where the answerer reads it for the seat `table`.
     */
    fun permissions(word: String, profile: String, worktree: String, defaults: PermissionDefaults): Path {
        val packaging = Packaging.read(root.resolve("agents/packaging/claude.yaml").readText()).ok()
        val values = Profile.read(profile, root.resolve("docs/profiles/$profile.md").readText()).ok()
        val places = Places(
            worktree = Path.of(worktree),
            scratch = Path.of("/office/_scratch/keporkak/S2.2"),
            project = Path.of("/office/project"),
            ownFile = Path.of("/office/project/tasks-p2-s2.2.md"),
            codeRepos = listOf(Path.of("/office/keporkak"), Path.of("/office/_wt")),
            mcpListen = "127.0.0.1:7711",
        )
        val file = seats.resolve("table").createDirectories().resolve("permissions.json")
        file.writeText(packaging.permissions(listOf(word), values, places, defaults).ok().toString())
        return file
    }

    /** What the answerer says to the call as a permission request of the seat `table`: `allow` or `deny <border>`. */
    suspend fun answer(permissions: Path, tool: String, input: JsonElement): String {
        check(permissions == seats.resolve("table/permissions.json"))
        return answer("table", Call(tool, input))
    }

    /** What the answerer says to [call] as a permission request of [seat]: `allow`, `deny <border>` or `deny: <why>`. */
    suspend fun answer(seat: String, call: Call): String {
        val request = PermissionRequest(SeatId(seat), "toolu_$seat", call.tool, null, call.input,
            listOf(OfferedOption("allow-once", "Yes", "allow_once"), OfferedOption("reject", "No", "reject_once")), name = call.tool)
        val explained = CompiledAnswerer(seats).explained(request)
        return if (explained.answer == Answer.ALLOW) "allow" else BORDER.find(explained.because ?: "")?.let { "deny " + it.groupValues[1] } ?: "deny: ${explained.because}"
    }

    /** The home the builder makes for [seat] in [role], with these places, for [profile], and master's defaults or [defaults]. */
    fun home(seat: String, role: String, profile: String = "keporkak", defaults: PermissionDefaults = MASTER_DEFAULTS): Path =
        ConfigHomes(root, seats).build(SeatId(seat), role, profile, places, defaults).ok().path

    /** The roles of the personas, and the names of the profiles, sorted. */
    fun roles(): List<String> = root.resolve("agents").listDirectoryEntries().filter { it.resolve("AGENT.md").exists() }.map { it.name }.sorted()
    fun profiles(): List<String> = root.resolve("docs/profiles").listDirectoryEntries("*.md").map { it.name.removeSuffix(".md") }.sorted()

    override fun close() { root.toFile().deleteRecursively() }

    private fun copy(from: Path, to: Path) {
        if (!from.exists()) return
        to.parent.createDirectories()
        Files.walk(from).use { paths -> paths.forEach { path -> Files.copy(path, to.resolve(from.relativize(path).toString()), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES) } }
    }
}

/** A border's name in a reason: `border <word>`. */
val BORDER = Regex("border ([a-z][a-z-]*)")

/** `engagement.yaml:permissions` on master, as a caller hands it to the builder. */
val MASTER_DEFAULTS: PermissionDefaults by lazy {
    val block = Yaml.default.parseToYamlNode(root("rules/engagement.yaml").readText()).yamlMap.get<YamlMap>("permissions")!!
    PermissionDefaults.of(block.entries.entries.associate { (key, value) -> key.content to (value as YamlScalar).content }).ok()
}

/** One tool call: its tool's name and its input — the hook's `tool_input`, a permission request's input. */
class Call(val tool: String, val input: JsonElement) {
    override fun toString() = "$tool $input"
}

fun call(tool: String, input: String) = Call(tool, Json.parseToJsonElement(input))
fun bash(command: String) = Call("Bash", buildJsonObject { put("command", command) })
fun write(path: String) = Call("Write", buildJsonObject { put("file_path", path); put("content", "x") })
fun edit(path: String, old: String, new: String) = Call("Edit", buildJsonObject { put("file_path", path); put("old_string", old); put("new_string", new) })
fun read(path: String) = Call("Read", buildJsonObject { put("file_path", path) })

/** One row of permission-table.tsv. */
class Row(val word: String, val profile: String, val worktree: String, val tool: String, val input: JsonElement, val expected: String) {
    override fun toString() = "$word: $tool $input → $expected"
}

object TableRows {
    fun rows(): List<Row> = TableRows::class.java.getResource("permission-table.tsv")!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { Row(it[0], it[1], it[2], it[3], Json.parseToJsonElement(it[4]), it[5]) } }
}

/** The hook scripts of `hooks/`, run as the pinned Claude Code runs them: a process, the hook's JSON on stdin. */
object Hooks {
    /** What `deny` answers to a PreToolUse call, in the probe's form: `allow` (no decision), `deny <border>` or `deny: <why>`. */
    fun deny(permissions: Path, tool: String, input: JsonElement, environment: Map<String, String> = emptyMap()): String {
        val call = JsonObject(PRE_TOOL_USE + mapOf("tool_name" to JsonPrimitive(tool), "tool_input" to input))
        val out = run("deny", listOf(permissions.toString()), call.toString(), environment).trim()
        if (out.isEmpty()) return "allow"
        val decision = Json.parseToJsonElement(out).jsonObject.getValue("hookSpecificOutput").jsonObject
        check((decision["hookEventName"] as JsonPrimitive).content == "PreToolUse") { out }
        check((decision["permissionDecision"] as JsonPrimitive).content == "deny") { out }
        val reason = (decision["permissionDecisionReason"] as JsonPrimitive).content
        return BORDER.find(reason)?.let { "deny " + it.groupValues[1] } ?: "deny: " + reason.removePrefix("Keporkak denied this call: ").removeSuffix(".")
    }

    /** What `deny` answers to [call] from the `permissions.json` of [home] — with only [path] as its PATH, if given. */
    fun deny(home: Path, call: Call, path: String? = null): String =
        deny(home.resolve("permissions.json"), call.tool, call.input, path?.let { mapOf("PATH" to it) } ?: emptyMap())

    /** What `stop-gate` prints at a Stop, with the daemon at [url] and the seat's [token] in its environment. */
    fun stopGate(limitSeconds: Int, url: String, token: String): String =
        run("stop-gate", listOf(limitSeconds.toString()), STOP.toString(), mapOf("KP_HOOK_URL" to url, "KP_SESSION_TOKEN" to token)).trim()

    /** What `inbox-first` prints at a SessionStart. */
    fun inboxFirst(environment: Map<String, String> = emptyMap()): String = run("inbox-first", emptyList(), SESSION_START.toString(), environment).trim()

    fun run(hook: String, args: List<String>, stdin: String, environment: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder(listOf("/bin/sh", root("hooks/$hook").toString()) + args)
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin", "LANG" to "C.UTF-8") + environment)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "hook $hook did not end" }
        check(process.exitValue() == 0) { "hook $hook exited ${process.exitValue()}: $out" }
        return out
    }

    // The hooks' JSON as the probe recorded it from the adapter 0.106.0, bundling Claude Code 2.1.322 (S2.2 pre-flight).
    private val COMMON = mapOf(
        "session_id" to JsonPrimitive("3cbf814c-37d5-4360-a5de-ce8c017f0ff0"),
        "transcript_path" to JsonPrimitive("/seats/coder-1/projects/-office-wt/3cbf814c-37d5-4360-a5de-ce8c017f0ff0.jsonl"),
        "cwd" to JsonPrimitive("/office/_wt/keporkak-S2.2"),
    )
    val SESSION_START = JsonObject(COMMON + mapOf("hook_event_name" to JsonPrimitive("SessionStart"), "source" to JsonPrimitive("startup")))
    private val PRE_TOOL_USE = COMMON + mapOf(
        "prompt_id" to JsonPrimitive("34accc33-9e93-4b9a-acfb-fccacd970273"),
        "permission_mode" to JsonPrimitive("default"),
        "effort" to buildJsonObject { put("level", "low") },
        "hook_event_name" to JsonPrimitive("PreToolUse"),
        "tool_use_id" to JsonPrimitive("toolu_01VRAap3LYsD7UndX9sfTaNK"),
    )
    val STOP = JsonObject(COMMON + mapOf(
        "prompt_id" to JsonPrimitive("34accc33-9e93-4b9a-acfb-fccacd970273"),
        "permission_mode" to JsonPrimitive("default"),
        "effort" to buildJsonObject { put("level", "low") },
        "hook_event_name" to JsonPrimitive("Stop"),
        "stop_hook_active" to JsonPrimitive(false),
        "last_assistant_message" to JsonPrimitive("Done."),
        "background_tasks" to kotlinx.serialization.json.JsonArray(emptyList()),
        "session_crons" to kotlinx.serialization.json.JsonArray(emptyList()),
    ))
}

/**
 * A stand-in for the daemon's `/hook/stop` on localhost: it answers [answer] with [status] to every request, or — with
 * none — never answers. It keeps each request's method and path, Authorization header and body, in memory only.
 */
class StopStandIn(private val answer: String?, private val status: Int = 200) : AutoCloseable {
    val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val connections = java.util.concurrent.atomic.AtomicInteger()
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/") { exchange ->
            connections.incrementAndGet()
            paths += "${exchange.requestMethod} ${exchange.requestURI.path}"
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            bodies += exchange.requestBody.readAllBytes().decodeToString()
            if (answer == null) runCatching { Thread.sleep(60_000) }
            else {
                val bytes = answer.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}

/** A JSON object's text field. */
fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
