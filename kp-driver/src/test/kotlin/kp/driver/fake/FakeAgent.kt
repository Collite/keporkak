package kp.driver.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * The scripted fake agent of the driver's specs (S2.1 T1): a real child process that speaks ACP — JSON-RPC, one JSON
 * object per line — over its stdin and stdout, shaped like the Claude adapter the spike recorded (design/06, answers
 * 1 and 3–7), and follows the script a spec gives it. It shares no code with the driver.
 *
 * It appends what it received to the script's `record` file as JSON lines. A secret never goes there: header values
 * and the token variable are written as SHA-256 only.
 *
 * The script (JSON): `record` · `turns` — one list of steps per prompt · optional `mode`, `model`, `effort` (what a
 * session starts on), `modes`, `models`, `efforts` (what it offers), `w1` (send `auth.status` as `{}`, default true),
 * `onEof` (`exit`, `linger` or `orphan`), `loadable` (default true). Steps: `say`, `tool`, `tool_update`, `ask`, `usage`, `run`,
 * `hangup`, `leak`, `recall`, `end` — see [perform]. Steps after `end` are sent after the turn's answer.
 */
fun main(args: Array<String>) {
    FakeAgent(Json.parseToJsonElement(File(args[0]).readText()).jsonObject).run()
}

private class FakeAgent(private val script: JsonObject) {
    private val record = File(script.string("record")!!)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val requestIds = AtomicInteger(9000)
    private val turns = Executors.newSingleThreadExecutor()
    private var session: String? = null
    private var mode = script.string("mode") ?: "auto"
    private var model = script.string("model") ?: "default"
    private var effort = script.string("effort") ?: "default"
    private var prompts = 0
    @Volatile private var cancelled = false
    @Volatile private var child: Process? = null
    @Volatile private var headers: List<String> = emptyList()

    private val modes = script.strings("modes") ?: listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")
    private val models = script.strings("models") ?: listOf("default", "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
    private val efforts = listOf("default") + (script.strings("efforts") ?: listOf("low", "medium", "high"))
    private val configHome = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File)

    fun run() {
        note(
            "start",
            "pid" to JsonPrimitive(ProcessHandle.current().pid()),
            "cwd" to JsonPrimitive(File("").absolutePath),
            "env" to JsonArray(System.getenv().keys.sorted().map(::JsonPrimitive)),
            "CLAUDE_CONFIG_DIR" to (System.getenv("CLAUDE_CONFIG_DIR")?.let(::JsonPrimitive) ?: JsonNull),
            "CLAUDE_CODE_OAUTH_TOKEN_sha256" to (System.getenv("CLAUDE_CODE_OAUTH_TOKEN")?.let { JsonPrimitive(sha256(it)) } ?: JsonNull),
        )
        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isNotBlank()) handle(Json.parseToJsonElement(line).jsonObject)
        }
        note("eof")
        if (script.string("onEof") == "linger") Thread.sleep(Long.MAX_VALUE)
        // `orphan`: leave on EOF without ending the running command.
        if (script.string("onEof") != "orphan") child?.destroyForcibly()
        exitProcess(0)
    }

    private fun handle(message: JsonObject) {
        val method = message.string("method")
        val id = message["id"]
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        when {
            method == null && id != null -> pending.remove(id.jsonPrimitive.content.toInt())?.complete(message)
            method == "session/cancel" -> {
                note("cancel")
                cancelled = true
                child?.destroyForcibly()
            }
            id == null -> note("notification", "method" to JsonPrimitive(method))
            method == "initialize" -> {
                note("initialize", "params" to params)
                answer(id, initializeResult())
            }
            method == "session/new" -> {
                headers = headerValues(params.getValue("mcpServers"))
                note("session/new", "cwd" to params.getValue("cwd"), "mcpServers" to hidden(params.getValue("mcpServers")))
                session = UUID.randomUUID().toString()
                historyFile(session!!)?.apply { parentFile.mkdirs(); createNewFile() }
                answer(id, sessionState(withId = true))
            }
            method == "session/load" -> load(id, params)
            method == "session/set_mode" -> {
                mode = params.string("modeId")!!
                note("session/set_mode", "modeId" to JsonPrimitive(mode))
                answer(id, JsonObject(emptyMap()))
            }
            method == "session/set_config_option" -> setOption(id, params)
            method == "session/prompt" -> prompt(id, params)
            else -> fail(id, -32601, "Method not found: $method")
        }
    }

    private fun initializeResult() = buildJsonObject {
        put("protocolVersion", 1)
        put("agentCapabilities", buildJsonObject {
            put("loadSession", true)
            put("promptCapabilities", buildJsonObject { put("image", true); put("embeddedContext", true) })
            put("mcpCapabilities", buildJsonObject { put("http", true); put("sse", true) })
            // The spike's W1: the adapter sends `{}` where the SDK types a Boolean.
            if (script.boolean("w1") != false) put("auth", buildJsonObject { put("status", JsonObject(emptyMap())) })
        })
        put("authMethods", JsonArray(emptyList()))
        put("agentInfo", buildJsonObject { put("name", "fake-agent"); put("version", "0.0.0") })
    }

    private fun sessionState(withId: Boolean) = buildJsonObject {
        if (withId) put("sessionId", session)
        put("modes", buildJsonObject {
            put("currentModeId", mode)
            put("availableModes", JsonArray(modes.map { buildJsonObject { put("id", it); put("name", it) } }))
        })
        put("configOptions", configOptions())
    }

    private fun configOptions() = JsonArray(listOf(
        select("mode", "Mode", "mode", mode, modes),
        select("model", "Model", "model", model, models),
        select("effort", "Effort", "thought_level", effort, efforts),
    ))

    private fun select(id: String, name: String, category: String, current: String, values: List<String>) = buildJsonObject {
        put("id", id); put("name", name); put("category", category); put("type", "select"); put("currentValue", current)
        put("options", JsonArray(values.map { buildJsonObject { put("value", it); put("name", it) } }))
    }

    private fun setOption(id: JsonElement, params: JsonObject) {
        val option = params.string("configId")!!
        val value = params.string("value")!!
        note("session/set_config_option", "configId" to JsonPrimitive(option), "value" to JsonPrimitive(value))
        val offered = when (option) { "mode" -> modes; "model" -> models; "effort" -> efforts; else -> emptyList() }
        if (value !in offered) return fail(id, -32602, "Invalid value for config option $option: $value")
        when (option) { "mode" -> mode = value; "model" -> model = value; "effort" -> effort = value }
        answer(id, buildJsonObject { put("configOptions", configOptions()) })
    }

    /** A loaded session replays its history and starts again on the script's mode, model and effort (spike `06` Q6). */
    private fun load(id: JsonElement, params: JsonObject) {
        headers = headerValues(params.getValue("mcpServers"))
        val wanted = params.string("sessionId")!!
        note("session/load", "sessionId" to JsonPrimitive(wanted), "cwd" to params.getValue("cwd"), "mcpServers" to hidden(params.getValue("mcpServers")))
        val history = historyFile(wanted)
        if (script.boolean("loadable") == false || history == null || !history.exists()) return fail(id, -32002, "Resource not found: session $wanted")
        session = wanted
        history.readLines().filter { it.isNotBlank() }.forEach { send(update(Json.parseToJsonElement(it).jsonObject)) }
        answer(id, sessionState(withId = false))
    }

    private fun prompt(id: JsonElement, params: JsonObject) {
        val text = params.getValue("prompt").jsonArray.joinToString("") { it.jsonObject.string("text") ?: "" }
        note("session/prompt", "text" to JsonPrimitive(text))
        remember(buildJsonObject { put("sessionUpdate", "user_message_chunk"); put("content", text(text)) })
        val steps = script["turns"]?.jsonArray?.getOrNull(prompts++)?.jsonArray ?: JsonArray(emptyList())
        cancelled = false
        // Steps after `end` are sent after the turn's answer: updates between turns.
        val endAt = steps.indexOfFirst { it.jsonObject.string("do") == "end" }
        val during = if (endAt < 0) steps else JsonArray(steps.take(endAt + 1))
        val after = if (endAt < 0) emptyList() else steps.drop(endAt + 1)
        turns.submit {
            answer(id, runTurn(during))
            after.forEach { perform(it.jsonObject) }
        }
    }

    private fun runTurn(steps: JsonArray): JsonObject {
        for (step in steps.map { it.jsonObject }) {
            if (cancelled) return stop("cancelled")
            val end = perform(step)
            if (end != null) return end
        }
        return if (cancelled) stop("cancelled") else stop("end_turn")
    }

    /** One step of a turn; returns the prompt's result when the step ends the turn. */
    private fun perform(step: JsonObject): JsonObject? {
        when (step.string("do")) {
            "say" -> emit(buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", text(step.string("text")!!)) })
            "tool" -> emit(buildJsonObject {
                put("sessionUpdate", "tool_call"); put("toolCallId", step.string("id")); put("title", step.string("title"))
                put("kind", step.string("kind") ?: "execute"); put("status", "pending"); put("rawInput", step["input"] ?: JsonNull)
            })
            "tool_update" -> emit(buildJsonObject {
                put("sessionUpdate", "tool_call_update"); put("toolCallId", step.string("id")); put("status", step.string("status"))
            })
            "usage" -> emit(buildJsonObject {
                put("sessionUpdate", "usage_update"); put("used", step.long("used")); put("size", step.long("size"))
            })
            "ask" -> ask(step)
            // An agent that repeats its secrets: the token in its environment and the headers it was given.
            "leak" -> emit(buildJsonObject {
                put("sessionUpdate", "agent_message_chunk")
                put("content", text("my token is ${System.getenv("CLAUDE_CODE_OAUTH_TOKEN")} and my headers are ${headers.joinToString(" ")}"))
            })
            // Closes stdout and stays: the protocol closes while the process lives.
            "hangup" -> {
                System.out.close()
                Thread.sleep(Long.MAX_VALUE)
            }
            "run" -> runCommand(step)
            "recall" -> {
                val first = historyFile(session!!)!!.readLines().map { Json.parseToJsonElement(it).jsonObject }
                    .first { it.string("sessionUpdate") == "user_message_chunk" }.getValue("content").jsonObject.string("text")
                emit(buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", text("you first said: $first")) })
            }
            "end" -> return if (cancelled) stop("cancelled") else stop(step.string("stopReason") ?: "end_turn", step["usage"]?.jsonObject)
            else -> error("unknown step $step")
        }
        return null
    }

    /** A permission request with the options the step names, by kind; the answer is recorded as it came. */
    private fun ask(step: JsonObject) {
        val toolCallId = step.string("id")!!
        val options = step.strings("options")!!.map { kind -> buildJsonObject { put("optionId", "opt-$kind"); put("name", kind); put("kind", kind) } }
        val requestId = requestIds.incrementAndGet()
        val reply = CompletableFuture<JsonObject>().also { pending[requestId] = it }
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", requestId); put("method", "session/request_permission")
            put("params", buildJsonObject {
                put("sessionId", session)
                put("toolCall", buildJsonObject {
                    put("toolCallId", toolCallId); put("title", step.string("title")); put("kind", step.string("kind") ?: "execute")
                    put("status", "pending"); put("rawInput", step["input"] ?: JsonNull)
                })
                put("options", JsonArray(options))
            })
        })
        val outcome = reply.get().getValue("result").jsonObject.getValue("outcome").jsonObject
        val chosen = outcome.string("optionId")
        note("permission", "toolCallId" to JsonPrimitive(toolCallId), "outcome" to JsonPrimitive(outcome.string("outcome")),
            "kind" to (options.firstOrNull { it.string("optionId") == chosen }?.getValue("kind") ?: JsonNull))
        val allowed = chosen != null && chosen.startsWith("opt-allow")
        emit(buildJsonObject { put("sessionUpdate", "tool_call_update"); put("toolCallId", toolCallId); put("status", if (allowed) "completed" else "failed") })
    }

    /** A command that runs until it ends or the turn is cancelled; its pid is recorded. */
    private fun runCommand(step: JsonObject) {
        val process = ProcessBuilder("sleep", step.long("seconds").toString()).start()
        child = process
        note("child", "pid" to JsonPrimitive(process.pid()))
        process.waitFor()
    }

    private fun stop(reason: String, usage: JsonObject? = null) = buildJsonObject {
        put("stopReason", reason)
        if (usage != null) put("usage", buildJsonObject {
            put("inputTokens", usage.long("input")); put("outputTokens", usage.long("output")); put("totalTokens", usage.long("total"))
        })
    }

    private fun emit(update: JsonObject) {
        remember(update)
        send(update(update))
    }

    private fun update(update: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "session/update")
        put("params", buildJsonObject { put("sessionId", session); put("update", update) })
    }

    private fun remember(update: JsonObject) {
        historyFile(session ?: return)?.apply { parentFile.mkdirs(); appendText(update.toString() + "\n") }
    }

    private fun historyFile(id: String) = configHome?.resolve("fake-sessions/$id.jsonl")

    private fun answer(id: JsonElement, result: JsonObject) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })

    private fun fail(id: JsonElement, code: Int, message: String) = send(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) })
    })

    @Synchronized
    private fun send(message: JsonObject) {
        System.out.write((message.toString() + "\n").toByteArray())
        System.out.flush()
    }

    @Synchronized
    private fun note(event: String, vararg fields: Pair<String, JsonElement>) {
        record.appendText(JsonObject(mapOf("event" to JsonPrimitive(event)) + fields).toString() + "\n")
    }

    private fun headerValues(servers: JsonElement) = servers.jsonArray.flatMap { server ->
        (server.jsonObject["headers"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject.string("value")!! }
    }

    /** MCP servers as received, each header's value replaced by its SHA-256. */
    private fun hidden(servers: JsonElement) = JsonArray(servers.jsonArray.map { server ->
        val fields = server.jsonObject
        JsonObject(fields + ("headers" to JsonArray((fields["headers"]?.jsonArray ?: JsonArray(emptyList())).map { header ->
            buildJsonObject { put("name", header.jsonObject.string("name")); put("sha256", sha256(header.jsonObject.string("value")!!)) }
        })))
    })
}

private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }
private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.content.toLong()
private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }
private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

