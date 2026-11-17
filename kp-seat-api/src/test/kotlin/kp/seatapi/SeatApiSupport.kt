package kp.seatapi

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.engine.FactRef
import kp.log.LogWriter
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.mailroom.Mailroom
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.RuleTexts
import kp.model.Message
import kp.model.OfficeId
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.walk
import io.ktor.client.engine.cio.CIO as ClientCIO

val root = File(System.getProperty("kp.root"))
val panda = OfficeId("panda")

/** This stage, as the specs' office knows it. */
const val STAGE = "KP/P2/S2.3"
const val START = "2026-11-17T09:00:00Z"
const val SEEDED = "m-panda-000000"

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** The rule texts on master, as the daemon hands them over; [edit] replaces the first occurrence of a text in one file. */
class MasterRules : RuleTexts {
    private val files: MutableMap<String, String> = run {
        val rules = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary").map { File(root, "rules/$it.yaml") }
        val robots = File(root, "robots").listFiles { file -> file.name.endsWith(".yaml") }!!.sorted()
        val pool = listOf("roster", "offices", "budgets").map { File(root, "pool/$it.yaml") }
        val personas = File(root, "agents").listFiles()!!.map { File(it, "AGENT.md") }.filter { it.isFile }.sorted()
        (rules + robots + pool + personas).associate { it.relativeTo(root).path to it.readText() }.toMutableMap()
    }

    fun edit(file: String, from: String, to: String): MasterRules = apply {
        val text = files.getValue(file)
        check(from in text) { "'$from' is not in $file" }
        files[file] = text.replaceFirst(from, to)
    }

    override fun read(): Map<String, String> = files.toMap()
}

/** The world, faked: the seat each role is in the stage, a branch that exists, and nothing else. */
class SpecFacts : FactSource {
    val seats = mutableMapOf("coder" to "coder-1", "teamlead" to "teamlead", "reviewer" to "reviewer-1")

    override fun observe(fact: FactRef, stage: String?): Datum? = when (fact.name) {
        "seat.name" -> (fact.params["of"] as? Datum.Text)?.value?.let { seats[it] ?: it }?.let(Datum::Text)
        "git.branch_exists" -> Datum.Bool(true)
        "list.stop_conditions" -> Datum.of((1..10).map { "SC-$it" })
        // Above the Pool Manager's `context_health`: what lets its seat send `compact` to another seat.
        "seat.context_used" -> Datum.of(0.9)
        else -> null
    }
}

/** Operations answered `ok` at once; every seat busy, so that nothing is woken and delivered mail waits in its inbox. */
class SpecOperations : Operations {
    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) = done(OperationResult.Ok())
}

class BusySeats : Deliveries {
    override fun idle(seat: String) = false
    override fun wake(seat: String, message: Message) = false
}

class FixedClock(var now: Instant = Instant.parse(START)) : Clock {
    override fun now(): Instant = now
}

/**
 * One office in front of the seat API (S2.3 T1's seam): a real mailroom on a throwaway log with [rules] — master's
 * unless a spec edits them — this stage seeded in [seed], its seats `ready`; facts, operations and delivery faked;
 * the API on a loopback port, and tokens registered by the spec.
 */
class SeatOffice(seed: String = "assigned", rules: RuleTexts = MasterRules()) : AutoCloseable {
    val project: Path = Files.createTempDirectory(root.resolve("build").toPath().createDirectories(), "seat-api-")
    val tokens = Tokens()
    /** Every token this office made, live or dead: none may show anywhere (S2.3 DONE). */
    val made = ArrayList<String>()
    val mailroom: Mailroom
    val api: SeatApi

    init {
        LogWriter.open(project, panda).use { log ->
            fun line(text: String) = log.append(json("""{"v":1,"at":"$START",""" + text.removePrefix("{")))
            line("""{"event":"transition","machine":"stage","subject":"$STAGE","from":"planned","to":"$seed","cause":"$SEEDED"}""")
            listOf("coder-1", "teamlead", "reviewer-1").forEach {
                line("""{"event":"transition","machine":"seat","subject":"$it","from":"provisioned","to":"ready","cause":"$SEEDED"}""")
            }
        }
        mailroom = Mailroom.start(project, panda, rules, SpecFacts(), SpecOperations(), BusySeats(), FixedClock())
        api = SeatApi.start(mailroom, tokens, "127.0.0.1:0")
    }

    /** A seat with a live token, bound to [role] in [stage]. */
    fun seat(name: String, role: String, stage: String? = STAGE): SeatClient {
        val token = "kp-spec-" + ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        made += token
        names += name
        tokens.register(token, Binding(name, role, "panda", stage, "claude-haiku-5-5", "low"))
        return SeatClient(name, token, "http://127.0.0.1:${api.address.port}").also(clients::add)
    }

    private val clients = ArrayList<SeatClient>()
    private val names = HashSet<String>()

    /** Every line of the log, in `seq` order, as written. */
    fun lines(): List<JsonObject> = project.resolve("mail").walk().filter { it.isRegularFile() && it.extension == "jsonl" }
        .flatMap { file -> file.readLines().filter(String::isNotBlank).map(::json) }.sortedBy { it["seq"]!!.jsonPrimitive.content.toLong() }.toList()

    fun records(event: String): List<JsonObject> = lines().filter { it["event"]?.jsonPrimitive?.content == event }
    fun messages(): List<JsonObject> = lines().filter { "kind" in it }

    /** Every file under the project and the build's output a spec could leave a token in — the log, first of all. */
    fun written(): List<String> = project.walk().filter { it.isRegularFile() }.map { Files.readString(it) }.toList()

    /**
     * Closes the office — and checks its whole log (S2.3 DONE): every message a seat sent is carried in that seat's name,
     * `from` the seat and `by: seat:<name>`, whatever its input named.
     */
    override fun close() {
        clients.forEach(SeatClient::close)
        api.close()
        mailroom.close()
        val bySeats = messages().filter { it.text("by")?.startsWith("seat:") == true }
        val strays = bySeats.filter { it.text("from") !in names || it.text("by") != "seat:${it.text("from")}" }
        check(strays.isEmpty()) { "a seat's message not carried in its own name: $strays" }
    }
}

/** What a tool answered: its JSON (the text of its one content block), and whether it is an error of the tool. */
data class Called(val json: JsonObject?, val text: String, val isError: Boolean)

/** A plain HTTP answer: its status and its body. */
data class Answered(val status: Int, val body: String)

/** A seat in front of the API: an MCP client that sends its token as bearer, and a plain HTTP client for the hook. */
class SeatClient(val name: String, val token: String, val base: String) : AutoCloseable {
    private val http = HttpClient(ClientCIO)
    private val client by lazy {
        Client(Implementation("seat-api-spec", "0")).also { client ->
            runBlocking { client.connect(StreamableHttpClientTransport(http, "$base/mcp", requestBuilder = { header("Authorization", "Bearer $token") })) }
        }
    }

    /** Calls [tool] with [arguments], a JSON object's text. */
    fun call(tool: String, arguments: String = "{}"): Called = runBlocking {
        val result = client.callTool(CallToolRequest(CallToolRequestParams(tool, json(arguments))))
        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        Called(runCatching { json(text) }.getOrNull(), text, result.isError == true)
    }

    /** The stop hook's call (contracts.md §11): `POST /hook/stop` with this seat's token. */
    fun stop(active: Boolean): JsonObject = json(post("/hook/stop", """{"stop_hook_active": $active}""", token).body)

    override fun close() {
        runCatching { runBlocking { client.close() } }
        http.close()
    }
}

/** A POST of [body] to [base]`[path]`, with [token] as bearer when one is given. */
fun post(base: String, path: String, body: String, token: String? = null): Answered {
    val request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    val response = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    return Answered(response.statusCode(), response.body())
}

fun SeatClient.post(path: String, body: String, token: String? = this.token): Answered = post(base, path, body, token)

/** A JSON-RPC `tools/call` of [tool] with [arguments], as an MCP client posts it. */
fun toolsCall(tool: String, arguments: String = "{}"): String =
    """{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "$tool", "arguments": $arguments}}"""

/** A JSON value's text content; for an array, its items'. */
val JsonObject.messageIds: List<String> get() = (this["messages"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }

fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
