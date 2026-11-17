package kp.seatapi.handshake

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.Outcome
import kp.driver.SeatFacts
import kp.driver.SessionSpec
import kp.driver.UsageWindow
import kp.driver.claude.ClaudeAdapter
import kp.driver.home.CompiledAnswerer
import kp.driver.home.ConfigHomes
import kp.driver.home.PermissionDefaults
import kp.driver.home.Places
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
import kp.mailroom.Sender
import kp.mailroom.Status
import kp.model.Message
import kp.model.OfficeId
import kp.model.SeatId
import kp.seatapi.Binding
import kp.seatapi.SeatApi
import kp.seatapi.Tokens
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * `just handshake tools` (S2.3 T8; contracts.md §5, §11, §12): the live proof of the five seat tools and the stop gate
 * on a real seat. A coder's config home built by S2.2's builder, this API on a loopback port, a mailroom on a throwaway
 * log with the checkout's rules, and the pinned adapter with the roster's cheapest model and effort — through the steps
 * of S2.3's DONE line, in two prompts. It spends tokens: it is no part of `just check`, and CI never runs it. Its
 * evidence — tokens removed, and searched for before it is written — goes to the file it names on its last line.
 *
 * Who registers a session's token is S2.4's wiring. Here the agent's command is a wrapper that hands the token the
 * driver gave the process (`KP_SESSION_TOKEN`) to this program over loopback, on curl's standard input — never in an
 * argument or a file — and only then starts the adapter: the token is live before Claude Code can connect. The stop
 * hook's calls go through a recorder that forwards each to the API's `/hook/stop` and keeps what it answered.
 *
 * Usage: ToolsHandshakeKt --root <keporkak checkout> [--adapter <dir with package-lock.json and node_modules>] [--office <name>]
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it.first() to it.getOrNull(1) }
    val root = Path.of(options["--root"] ?: ".").toAbsolutePath().normalize()
    val adapter = Path.of(options["--adapter"] ?: root.resolve("office/adapter").toString()).toAbsolutePath().normalize()
    val office = options["--office"] ?: InetAddress.getLocalHost().hostName.substringBefore('.')
    exitProcess(if (runBlocking { ToolsHandshake(root, adapter, office).run() }) 0 else 1)
}

private class ToolsHandshake(private val root: Path, private val adapter: Path, private val office: String) {
    private val roster = yaml(root.resolve("pool/roster.yaml"))
    private val offices = yaml(root.resolve("pool/offices.yaml"))
    private val tokenFile = Path.of(offices.get<YamlMap>("offices")!!.get<YamlMap>(office)!!.get<YamlMap>("roots")!!.getScalar("secrets")!!.content
        .replaceFirst("~", System.getProperty("user.home"))).resolve("claude-token")
    private val cheapest = roster.get<YamlList>("order")!!.items.first().yamlMap
    private val model = cheapest.getScalar("model")!!.content
    private val effort = cheapest.getScalar("effort")!!.content
    private val run = Files.createTempDirectory("kp-handshake-tools-")
    private val work = run.resolve("work").createDirectories()
    private val seats = run.resolve("seats").createDirectories()
    private val home = run.resolve("home").createDirectories()
    private val scratch = run.resolve("scratch").createDirectories()
    private val checkout = run.resolve("project").createDirectories()
    private val mail = run.resolve("mail").createDirectories()
    private val binding = Binding(CODER, ROLE, office, STAGE, model, effort)
    private val tokens = Tokens()
    /** Every session token this run saw: hidden in the evidence, and searched for in it. */
    private val minted: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val steps = mutableListOf<Triple<String, Boolean, String>>()
    private var spent = 0L

    suspend fun run(): Boolean {
        val lockfile = Json.parseToJsonElement(adapter.resolve("package-lock.json").readText()).jsonObject["packages"]!!.jsonObject["node_modules/$ADAPTER"]!!
            .jsonObject["version"]!!.jsonPrimitive.content
        val installed = version(adapter.resolve("node_modules/$ADAPTER/package.json"))
        val bundled = adapter.resolve("node_modules/@anthropic-ai/claude-agent-sdk/package.json").takeIf { it.exists() }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject["claudeCodeVersion"]?.jsonPrimitive?.content }
        val node = System.getenv("PATH").split(':').map { Path.of(it, "node") }.firstOrNull { Files.isExecutable(it) }
        val header = "adapter $ADAPTER: installed ${installed ?: "nothing"} · lockfile $lockfile — bundles Claude Code ${bundled ?: "?"}\n" +
            "the tools walk (S2.3): a seat built by the builder for the coder's persona, this API, a mailroom on a throwaway log; two prompts"
        println(header)
        if (installed == null || installed != lockfile || node == null) {
            steps += Triple("install", false, "installed ${installed ?: "nothing"}, lockfile $lockfile, node ${node ?: "not on PATH"}: run `just adapter`")
            return report(header)
        }
        LogWriter.open(mail, OfficeId(office)).use { log ->
            val at = now()
            fun line(text: String) = log.append(json("""{"v":1,"at":"$at",""" + text.removePrefix("{")))
            line("""{"event":"transition","machine":"stage","subject":"$STAGE","from":"planned","to":"assigned","cause":"$SEEDED"}""")
            listOf(CODER, TEAMLEAD, POOL).forEach { line("""{"event":"transition","machine":"seat","subject":"$it","from":"provisioned","to":"ready","cause":"$SEEDED"}""") }
        }
        val mailroom = Mailroom.start(mail, OfficeId(office), CheckoutRules(root), HandshakeFacts, Done, Asleep, Clock { Instant.now() })
        val api = SeatApi.start(mailroom, tokens, "127.0.0.1:0")
        val registrar = Registrar(tokens, binding, minted)
        val recorder = StopRecorder(api.address.port)
        val driver = ClaudeAdapter.driver(tokenFile, seats, java.time.Clock.systemUTC(), CompiledAnswerer(seats), object : Listener {})
        try {
            walk(driver, node, mailroom, api, registrar, recorder)
        } finally {
            driver.close()
            recorder.close()
            registrar.close()
            api.close()
            mailroom.close()
        }
        return report(header)
    }

    private suspend fun walk(driver: Driver, node: Path, mailroom: Mailroom, api: SeatApi, registrar: Registrar, recorder: StopRecorder) {
        val first = step("one message waits in the seat's inbox before its session starts") {
            val answer = mailroom.submit(Sender.Seat(TEAMLEAD), json("""{"kind": "assign", "stage": "$STAGE", "subject": "the handshake's first message", "args": {"seat": "$CODER"}}"""))
            check(answer.status == Status.DELIVERED) { "the first message was not delivered: $answer" }
            answer.id to "${answer.id}, from $TEAMLEAD to $CODER, delivered"
        } ?: return
        git(work, "init", "-q", "-b", "master")
        git(checkout, "init", "-q", "-b", "master")
        val list = checkout.resolve("tasks-handshake.md").also { it.writeText("# The handshake's stage list\n\n- [ ] T1 — the handshake\n") }
        val listen = "127.0.0.1:${api.address.port}"
        val built = step("a home built by the builder for the coder's persona, its messaging server this API") {
            val defaults = permissionDefaults()
            val home = ConfigHomes(root, seats).build(SeatId(CODER), ROLE, PROFILE, Places(work, scratch, checkout, list, listOf(run.resolve("code")), listen), defaults).ok()
            val messaging = home.servers.singleOrNull { it.url == "http://$listen/mcp" }
            check(messaging != null && messaging.carriesToken) { "no server at http://$listen/mcp carrying the session token: ${home.servers}" }
            home to "servers handed back: ${home.servers.joinToString { "${it.name} ${it.url}${if (it.carriesToken) " (the session token as bearer)" else ""}" }}"
        } ?: return
        val spec = SessionSpec(
            SeatId(CODER), listOf("/bin/sh", "-c", WRAPPER, node.toString(), adapter.resolve("node_modules/$ADAPTER/dist/index.js").toString()),
            work, built.path, model, effort, built.servers,
            mapOf("PATH" to "${node.parent}:/usr/local/bin:/usr/bin:/bin", "HOME" to home.toString(), "LANG" to "C.UTF-8", REGISTRAR to registrar.url),
            hookUrl = recorder.base,
        )
        val started = step("a session in the built home, its token registered before the agent's process started") {
            val started = driver.start(spec).ok()
            minted += started.token.value
            check(registrar.registered == 1) { "${registrar.registered} tokens registered" }
            check(tokens.bound(started.token.value) == binding) { "the session's token is not the one registered" }
            started to "session ${started.sessionId}; mode ${started.mode}, model ${started.model}, effort ${started.effort}; one token registered, for $CODER"
        } ?: return

        val before = transcript().size
        val turn = step("one prompt: whoami, inbox and a kind outside the seat's role; its turn ended") {
            val end = driver.prompt(SeatId(CODER), FIRST_PROMPT).ok()
            end to "stop reason ${end.stopReason}; tokens ${end.usage?.let { "input ${it.input}, output ${it.output}, total ${it.total}" } ?: "not reported"}"
        }
        val said = said(before)
        step("the bundled Claude Code connects with the injected server and header") {
            val calls = listOf("whoami", "inbox", "send").associateWith { toolCalls(it, before) }
            check(calls.values.all { it.isNotEmpty() && "completed" in it.values }) { "the seat's calls: $calls" }
            Unit to "the seat's calls of the messaging server's whoami, inbox and send completed (${calls.map { (tool, ids) -> "$tool ${ids.values.joinToString()}" }.joinToString("; ")}) — " +
                "the API answers a call without the live session token in its header 401, before any tool runs"
        }
        step("the seat calls whoami and repeats its seat and role") {
            check(CODER in said && Regex("""role\W{0,3}$ROLE\b""", RegexOption.IGNORE_CASE).containsMatchIn(said)) { "the seat said '$said'" }
            Unit to "the seat said: '${said.replace("\n", " ").take(400)}'"
        }
        step("it finds one message in inbox") {
            val read = records("read").filter { it.text("seat") == CODER }.flatMap { it.ids() }
            check(read == listOf(first)) { "read records for $CODER: $read" }
            Unit to "the log holds one read record for $CODER, naming $first"
        }
        step("a kind outside its role comes back refused with the rule's id") {
            val sent = lines().filter { it.text("kind") == "verdict" && it.text("from") == CODER }.map { it.text("id") }
            val refused = records("refused").filter { it.text("id") in sent }
            check(refused.size == 1 && refused.single().text("rule") == "verdict") { "refusals of $CODER's verdict: $refused" }
            check(Regex("""refused""", RegexOption.IGNORE_CASE).containsMatchIn(said) && "verdict" in said) { "the seat said '$said'" }
            Unit to "the log: ${refused.single().text("id")} refused, rule ${refused.single().text("rule")}, requirement '${refused.single().text("requirement")}'; the seat repeated it"
        }

        val second = mailroom.submit(Sender.Seat(POOL), json("""{"kind": "compact", "stage": "$STAGE", "subject": "the handshake's second message: read it, then end your turn", "args": {"seat": "$CODER"}}"""))
        val stopsBefore = recorder.stops.size
        val again = transcript().size
        step("with a second message left unread it is kept from ending its turn once, reads it, and the turn ends") {
            check(second.status == Status.DELIVERED) { "the second message was not delivered: $second" }
            val end = driver.prompt(SeatId(CODER), SECOND_PROMPT).ok()
            val stops = recorder.stops.drop(stopsBefore)
            val read = records("read").filter { it.text("seat") == CODER }.flatMap { it.ids() }
            check(stops.map { it.body to it.answer } == listOf(
                """{"stop_hook_active":false}""" to """{"block":true,"reason":"$REASON"}""",
                """{"stop_hook_active":true}""" to """{"block":false,"reason":""}""",
            )) { "the stop hook's calls: ${stops.map { "${it.body} → ${it.status} ${it.answer}" }}" }
            check(stops.all { it.authorization == "Bearer ${started.token.value}" }) { "a call to /hook/stop without the session token" }
            check(read == listOf(first, second.id)) { "read records for $CODER: $read" }
            check(records("fact").none { it.text("fact") == TURN_BLOCKED }) { "the gate reached its cap" }
            check(end.stopReason == "end_turn") { "the turn ended ${end.stopReason}" }
            Unit to "${stops.size} calls to /hook/stop, each with the session token as bearer: ${stops.joinToString(" · ") { "${it.body} → ${it.answer}" }}; " +
                "the seat read ${second.id} (inbox ${toolCalls("inbox", again).values.joinToString()}); the turn ended ${end.stopReason}; no ${TURN_BLOCKED} fact"
        }
        spent = (driver.observe(SeatId(CODER), SeatFacts.USAGE, UsageWindow.SESSION) as? Outcome.Ok)?.value?.value?.jsonPrimitive?.content?.toLong() ?: 0
        driver.kill(SeatId(CODER), Duration.ofSeconds(5))
        tokens.revoke(started.token.value)
    }

    private fun permissionDefaults(): PermissionDefaults {
        val block = yaml(root.resolve("rules/engagement.yaml")).get<YamlMap>("permissions") ?: error("rules/engagement.yaml has no permissions block")
        return PermissionDefaults.of(block.entries.entries.associate { (key, value) -> key.content to ((value as? YamlScalar)?.content ?: "") }).ok()
    }

    /** The seat's words in its transcript after line [from]. */
    private fun said(from: Int): String = transcript().drop(from).filter { " agent: " in it }.joinToString("") { it.substringAfter(" agent: ") }

    /** The seat's tool calls after line [from] whose title or input names [fragment], each with how it ended: `completed`, `failed` or `open`. */
    private fun toolCalls(fragment: String, from: Int): Map<String, String> {
        val lines = transcript().drop(from)
        val ids = lines.mapNotNull { line -> Regex(" tool (\\S+) ").find(line)?.takeIf { fragment in line.substringAfter(" tool ") }?.groupValues?.get(1) }.distinct()
        return ids.associateWith { id -> lines.lastOrNull { " tool $id completed" in it || " tool $id failed" in it }?.let { if (" tool $id completed" in it) "completed" else "failed" } ?: "open" }
    }

    private fun transcript(): List<String> = seats.resolve("_transcripts/$CODER.log").let { if (it.exists()) it.readLines() else emptyList() }

    /** Every line of the throwaway log, in `seq` order. */
    private fun lines(): List<JsonObject> = mail.resolve("mail").takeIf { it.exists() }?.walk()?.filter { it.isRegularFile() && it.extension == "jsonl" }
        ?.flatMap { file -> file.readLines().filter(String::isNotBlank).map(::json) }?.sortedBy { it["seq"]!!.jsonPrimitive.content.toLong() }?.toList().orEmpty()

    private fun records(event: String) = lines().filter { it.text("event") == event }

    private suspend fun <T> step(name: String, block: suspend () -> Pair<T, String>): T? {
        val outcome = runCatching { block() }
        val (ok, detail) = outcome.fold({ true to it.second }, { false to (it.message ?: it.javaClass.simpleName) })
        steps += Triple(name, ok, detail)
        println("${if (ok) "ok    " else "FAILED"} $name: ${hide(detail)}")
        return outcome.getOrNull()?.first
    }

    /** Writes the evidence with every token replaced, searches it for them, and names the file. */
    private fun report(header: String): Boolean {
        val passed = steps.size == STEPS && steps.all { it.second }
        val at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val evidence = buildString {
            appendLine("# Handshake (tools) — ${if (passed) "PASS" else "FAIL"} — $at")
            appendLine()
            appendLine(header)
            appendLine("model $model, effort $effort (the roster's cheapest pair); seat $CODER, role $ROLE, stage $STAGE; tokens spent: $spent")
            appendLine()
            appendLine("| step | result | detail |")
            appendLine("|---|---|---|")
            steps.forEach { (name, ok, detail) -> appendLine("| $name | ${if (ok) "ok" else "FAILED"} | ${detail.replace("|", "\\|").replace("\n", " ")} |") }
            if (steps.size < STEPS) appendLine("| … | not run | ${STEPS - steps.size} steps after the first that failed |")
            appendLine()
            appendLine("## The throwaway log")
            appendLine("```")
            lines().forEach { appendLine(it.toString()) }
            appendLine("```")
            appendLine()
            appendLine("## The transcript of seat $CODER")
            appendLine("```")
            transcript().forEach(::appendLine)
            appendLine("```")
        }
        val file = root.resolve("build/handshake/handshake-tools-${version(adapter.resolve("node_modules/$ADAPTER/package.json")) ?: "not-installed"}-${at.toString().replace(":", "")}.md")
        file.parent.createDirectories()
        file.writeText(hide(evidence))
        val leaked = secrets().any { file.readText().contains(it) }
        run.toFile().deleteRecursively()
        println("tokens spent: $spent")
        println("RESULT: ${if (passed && !leaked) "PASS" else "FAIL"}${if (leaked) " — a token was found in the evidence" else ""} — evidence: $file")
        return passed && !leaked
    }

    private fun secrets(): List<String> = listOfNotNull(runCatching { tokenFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }) + minted.toList()

    private fun hide(text: String) = secrets().fold(text) { acc, secret -> acc.replace(secret, "‹hidden›") }

    private fun version(file: Path): String? = file.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject["version"]?.jsonPrimitive?.content }

    private fun git(directory: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(directory.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
    }

    private fun <T> Outcome<T>.ok(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Failed -> error(reason)
    }

    companion object {
        const val ADAPTER = "@agentclientprotocol/claude-agent-acp"
        const val STEPS = 9
        const val STAGE = "KP/P2/S2.3"
        const val CODER = "coder-handshake"
        const val ROLE = "coder"
        const val TEAMLEAD = "teamlead"
        const val POOL = "pool-handshake"
        const val PROFILE = "keporkak"
        const val SEEDED = "m-handshake-000000"
        const val TURN_BLOCKED = "turn.blocked"
        const val REGISTRAR = "KP_HANDSHAKE_REGISTRAR"
        /** The gate's rule (engagement.yaml:stop_gate), as the API answers a block. */
        const val REASON = "a turn ends only when the seat has no unread mail"
        /** The agent's command: hand the session token to the registrar on curl's standard input, then become the adapter. */
        const val WRAPPER = """printf '%s' "${'$'}KP_SESSION_TOKEN" | curl --silent --fail --max-time 10 --output /dev/null --data-binary @- "${'$'}KP_HANDSHAKE_REGISTRAR" && exec "${'$'}0" "${'$'}@""""
        const val FIRST_PROMPT = "This is the office's handshake test of your messaging tools — the MCP server named messaging. Attempt each step: the " +
            "rules decide, not you. Step 1: call whoami, then write one line: seat <your seat>, role <your role> — as whoami answered. " +
            "Step 2: call inbox, then write how many messages it returned. Step 3: call send with kind verdict, subject PASS and args " +
            """{"verdict": "PASS", "sha": "abc1234"}""" + " — a kind your role may not send — then write the status and the rule its result names. " +
            "Call no other tool, then end your turn."
        const val SECOND_PROMPT = "Reply with only the word: ok — and call no tool before you have written it."
    }
}

/** The checkout's rule texts, as the daemon hands them over: the rules, the robots, the pool files and the personas. */
private class CheckoutRules(private val root: Path) : RuleTexts {
    override fun read(): Map<String, String> {
        val rules = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary").map { root.resolve("rules/$it.yaml").toFile() }
        val robots = root.resolve("robots").toFile().listFiles { file -> file.name.endsWith(".yaml") }!!.sorted()
        val pool = listOf("roster", "offices", "budgets").map { root.resolve("pool/$it.yaml").toFile() }
        val personas = root.resolve("agents").toFile().listFiles()!!.map { File(it, "AGENT.md") }.filter { it.isFile }.sorted()
        return (rules + robots + pool + personas).associate { it.relativeTo(root.toFile()).path to it.readText() }
    }
}

/** The world, faked: the seat each role is in the stage, a branch that exists, a context full enough for `compact`. */
private object HandshakeFacts : FactSource {
    private val seats = mapOf("coder" to "coder-handshake", "teamlead" to "teamlead", "pool" to "pool-handshake")

    override fun observe(fact: FactRef, stage: String?): Datum? = when (fact.name) {
        "seat.name" -> (fact.params["of"] as? Datum.Text)?.value?.let { seats[it] ?: it }?.let(Datum::Text)
        "git.branch_exists" -> Datum.Bool(true)
        "seat.context_used" -> Datum.of(0.9)
        else -> null
    }
}

/** Operations answered `ok` at once. */
private object Done : Operations {
    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) = done(OperationResult.Ok())
}

/** No seat is woken: the handshake prompts its seat itself, and mail waits in the inbox. */
private object Asleep : Deliveries {
    override fun idle(seat: String) = false
    override fun wake(seat: String, message: Message) = false
}

/** Takes a session token on loopback, as the wrapper hands it over, and registers it for [binding] — standing in for S2.4's wiring. */
private class Registrar(tokens: Tokens, binding: Binding, minted: MutableList<String>) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile var registered = 0
    val url: String get() = "http://127.0.0.1:${server.address.port}/register"

    init {
        server.createContext("/register") { exchange ->
            exchange.use {
                val token = it.requestBody.readAllBytes().decodeToString().trim()
                if (it.requestMethod != "POST" || token.isEmpty()) return@use it.sendResponseHeaders(400, -1)
                minted += token
                tokens.register(token, binding)
                registered++
                it.sendResponseHeaders(204, -1)
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

/** In front of the API's `/hook/stop`: forwards each call as it came and keeps what it answered (in memory only). */
private class StopRecorder(apiPort: Int) : AutoCloseable {
    class Stop(val authorization: String?, val body: String, val status: Int, val answer: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient.newHttpClient()
    private val target = URI.create("http://127.0.0.1:$apiPort/hook/stop")
    val stops: MutableList<Stop> = Collections.synchronizedList(mutableListOf())
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/hook/stop") { exchange -> exchange.use(::forward) }
        server.start()
    }

    private fun forward(exchange: HttpExchange) {
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        val body = exchange.requestBody.readAllBytes().decodeToString()
        val request = HttpRequest.newBuilder(target).header("Content-Type", "application/json")
            .apply { authorization?.let { header("Authorization", it) } }.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        stops += Stop(authorization, body, response.statusCode(), response.body())
        val bytes = response.body().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.statusCode(), if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}

private fun yaml(file: Path): YamlMap = Yaml.default.parseToYamlNode(file.readText()).yamlMap

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun JsonObject.ids(): List<String> = (this["ids"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

private fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
