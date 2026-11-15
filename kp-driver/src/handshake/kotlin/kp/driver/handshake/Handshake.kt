package kp.driver.handshake

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.driver.Answer
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.McpServer
import kp.driver.ObservedFact
import kp.driver.Outcome
import kp.driver.PermissionRequest
import kp.driver.SeatFacts
import kp.driver.SessionSpec
import kp.driver.SessionToken
import kp.driver.UsageWindow
import kp.driver.claude.ClaudeAdapter
import kp.model.SeatId
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * `just handshake` (S2.1 T8, architecture.md §5): the driver against the real, pinned Claude adapter, with the
 * roster's cheapest model and effort, through the steps of S2.1's DONE line. It spends tokens; it is no part of
 * `just check`, and CI never runs it. Its evidence — tokens removed, and searched for before it is written — goes to
 * the file it names on its last line. Exit 0 when every step passed.
 *
 * The models step runs first, and spends no tokens: every roster model — a full id (contracts.md §9, Amendment 11) —
 * is set on a fresh session and must read back as a value the adapter offers other than `default`, and a made-up
 * name must be refused. `just handshake models` runs that step alone.
 *
 * Usage: HandshakeKt --root <keporkak checkout> [--adapter <dir with package-lock.json and node_modules>] [--office <name>] [models]
 */
fun main(args: Array<String>) {
    val (flags, words) = args.withIndex().partition { (i, arg) -> arg.startsWith("--") || (i > 0 && args[i - 1].startsWith("--")) }
    val options = flags.map { it.value }.chunked(2).associate { (key, value) -> key to value }
    val modelsOnly = when (words.map { it.value }) {
        emptyList<String>() -> false
        listOf("models") -> true
        else -> { System.err.println("usage: just handshake [models]"); exitProcess(2) }
    }
    val root = Path.of(options["--root"] ?: ".").toAbsolutePath().normalize()
    val adapter = Path.of(options["--adapter"] ?: root.resolve("office/adapter").toString()).toAbsolutePath().normalize()
    val office = options["--office"] ?: InetAddress.getLocalHost().hostName.substringBefore('.')
    exitProcess(if (runBlocking { Handshake(root, adapter, office, modelsOnly).run() }) 0 else 1)
}

private class Handshake(private val root: Path, private val adapter: Path, office: String, private val modelsOnly: Boolean) {
    private val roster = Yaml.default.parseToYamlNode(root.resolve("pool/roster.yaml").readText()).yamlMap
    private val offices = Yaml.default.parseToYamlNode(root.resolve("pool/offices.yaml").readText()).yamlMap
    private val secrets = Path.of(offices.get<YamlMap>("offices")!!.get<YamlMap>(office)!!.get<YamlMap>("roots")!!.getScalar("secrets")!!.content.replaceFirst("~", System.getProperty("user.home")))
    private val tokenFile = secrets.resolve("claude-token")
    private val cheapest = roster.get<YamlList>("order")!!.items.first().yamlMap
    private val model = cheapest.getScalar("model")!!.content
    private val effort = cheapest.getScalar("effort")!!.content
    /** Each roster model with the effort of its lowest pair. */
    private val rosterModels = roster.get<YamlList>("order")!!.items.map { it.yamlMap.getScalar("model")!!.content to it.yamlMap.getScalar("effort")!!.content }.distinctBy { it.first }
    private val rosterAdapter = (roster.get<YamlMap>("vendors")!!.get<YamlMap>("claude")!!.get<YamlNode>("adapter_version") as? YamlScalar)?.content
    private val seat = SeatId("handshake")
    private val word = "osprey-" + (1000 + SecureRandom().nextInt(9000))
    private val run = Files.createTempDirectory("kp-handshake-")
    private val work = run.resolve("work").createDirectories()
    private val seats = run.resolve("seats").createDirectories()
    private val home = run.resolve("home").createDirectories()
    private val steps = mutableListOf<Triple<String, Boolean, String>>()
    private val facts: MutableList<ObservedFact> = Collections.synchronizedList(mutableListOf())
    private val tokens: MutableList<SessionToken> = Collections.synchronizedList(mutableListOf())
    private val asked: MutableList<PermissionRequest> = Collections.synchronizedList(mutableListOf())
    private var spent = 0L

    /** The answerer: deny the command meant to be denied and anything unexpected; allow the two meant to run. */
    private val answerer = { request: PermissionRequest ->
        asked += request
        val input = request.input.toString()
        if ("kp-allowed" in input || "sleep 45" in input) Answer.ALLOW else Answer.DENY
    }

    private val listener = object : Listener {
        override fun observed(fact: ObservedFact) { facts += fact }
        override fun tokenDead(seat: SeatId, token: SessionToken) {}
    }

    suspend fun run(): Boolean {
        val lockfile = packageVersion(Json.parseToJsonElement(adapter.resolve("package-lock.json").readText()).jsonObject["packages"]!!.jsonObject["node_modules/$ADAPTER"]!!.jsonObject)
        val installed = adapter.resolve("node_modules/$ADAPTER/package.json").takeIf { it.exists() }?.let { packageVersion(Json.parseToJsonElement(it.readText()).jsonObject) }
        val bundled = adapter.resolve("node_modules/@anthropic-ai/claude-agent-sdk/package.json").takeIf { it.exists() }
            ?.let { Json.parseToJsonElement(it.readText()).jsonObject["claudeCodeVersion"]?.jsonPrimitive?.content }
        val node = System.getenv("PATH").split(':').map { Path.of(it, "node") }.firstOrNull { Files.isExecutable(it) }
        val header = "adapter $ADAPTER: installed ${installed ?: "nothing"} · lockfile $lockfile · roster ${rosterAdapter ?: "not pinned"} — bundles Claude Code ${bundled ?: "?"}" +
            if (modelsOnly) "\nthe models step alone: no prompt is sent" else ""
        println(header)
        val standIn = StandIn()
        val driver = ClaudeAdapter.driver(tokenFile, seats, Clock.systemUTC(), answerer, listener)
        try {
            if (installed == null || installed != lockfile || node == null) {
                steps += Triple("install", false, "installed ${installed ?: "nothing"}, lockfile $lockfile, node ${node ?: "not on PATH"}: run `just adapter`")
            } else {
                if (models(driver, node) != null && !modelsOnly) walk(driver, spec(node, standIn), standIn)
            }
        } finally {
            driver.close()
            standIn.close()
        }
        return report(header)
    }

    private fun spec(node: Path, standIn: StandIn) = spec(node, seat, model, effort, listOf(McpServer("kp-standin", standIn.url, carriesToken = true)))

    private fun spec(node: Path, seat: SeatId, model: String, effort: String, servers: List<McpServer>) = SessionSpec(
        seat = seat,
        command = listOf(node.toString(), adapter.resolve("node_modules/$ADAPTER/dist/index.js").toString()),
        workingDirectory = work,
        configHome = seats.resolve(seat.value).createDirectories(),
        model = model,
        effort = effort,
        servers = servers,
        environment = mapOf("PATH" to "${node.parent}:/usr/local/bin:/usr/bin:/bin", "HOME" to home.toString(), "LANG" to "C.UTF-8"),
    )

    /**
     * Every roster model set on a fresh session of its own and read back (§9, Amendment 11) — the driver fails a start
     * whose model does not read back as a value the adapter offers other than `default` — and a made-up name beside
     * them, which must fail. No prompt is sent.
     */
    private suspend fun models(driver: Driver, node: Path) = step("every roster model accepted and read back as a value the adapter offers, and a made-up name not") {
        val seat = SeatId(MODELS_SEAT)
        val read = rosterModels.map { (name, effort) ->
            val started = when (val outcome = driver.start(spec(node, seat, name, effort, emptyList()))) {
                is Outcome.Ok -> outcome.value
                is Outcome.Failed -> error("$name: ${outcome.reason}")
            }
            tokens += started.token
            val offered = driver.offers(seat).ok().models
            driver.kill(seat, Duration.ofSeconds(5)).ok()
            check(started.model != "default" && started.model in offered) { "$name read back as ${started.model} (offered: $offered)" }
            "$name → ${started.model}"
        }
        when (val madeUp = driver.start(spec(node, seat, MADE_UP, effort, emptyList()))) {
            is Outcome.Ok -> {
                tokens += madeUp.value.token
                driver.kill(seat, Duration.ofSeconds(5))
                error("the made-up name $MADE_UP was accepted, read back as ${madeUp.value.model}")
            }
            is Outcome.Failed -> Unit to "${read.joinToString(", ")}; $MADE_UP refused: ${madeUp.reason}"
        }
    }

    private suspend fun walk(driver: Driver, spec: SessionSpec, standIn: StandIn) {
        val configHomeEmpty = Files.list(spec.configHome).use { it.count() } == 0L
        val started = step("initialize") {
            val started = driver.start(spec).ok()
            tokens += started.token
            started to (transcript().firstOrNull { " initialized: " in it }?.substringAfter(" initialized: ") ?: "answered")
        } ?: return
        if (step("a session — working directory, empty config home, a stand-in server that saw its header") {
                val wanted = "Bearer ${started.token.value}"
                withTimeout(60.seconds) { while (standIn.seen.none { it.authorization == wanted }) delay(200) }
                check(configHomeEmpty) { "the config home was not empty" }
                val methods = standIn.seen.filter { it.authorization == wanted }.map { it.method }.distinct()
                Unit to "session ${started.sessionId} in ${spec.workingDirectory}, config home empty; the stand-in saw the session token on ${methods.joinToString(", ")}" +
                    "; requests without it: ${standIn.seen.count { it.authorization != wanted }}"
            } == null) return
        if (step("model and effort set and read back") {
                val offered = driver.offers(seat).ok().models
                check(started.mode == "default" && started.model != "default" && started.model in offered && started.effort == effort) {
                    "read back mode ${started.mode}, model ${started.model}, effort ${started.effort} (models offered: $offered)"
                }
                Unit to "mode ${started.mode}; model $model read back as ${started.model} (offered: ${offered.joinToString(", ")}); effort ${started.effort}"
            } == null) return
        if (step("one prompt ending with a stop reason and its usage") {
                val end = driver.prompt(seat, "Remember this code word for later: $word. For now, reply with only the word: ok").ok()
                checkNotNull(end.usage) { "the turn ended ${end.stopReason} with no usage" }
                Unit to "stop reason ${end.stopReason}; tokens input ${end.usage!!.input}, output ${end.usage!!.output}, total ${end.usage!!.total}"
            } == null) return
        if (step("one permission request denied and one allowed, each one-time") {
                val denied = driver.prompt(seat, "Use the Bash tool to run exactly this command, and nothing else: touch kp-denied.txt").ok()
                val allowed = driver.prompt(seat, "Use the Bash tool to run exactly this command, and nothing else: touch kp-allowed.txt").ok()
                val lines = transcript().filter { " permission " in it }
                check(lines.any { "kp-denied" in it && it.endsWith("→ reject_once") }) { "no request for kp-denied answered reject_once: $lines" }
                check(lines.any { "kp-allowed" in it && it.endsWith("→ allow_once") }) { "no request for kp-allowed answered allow_once: $lines" }
                check(lines.none { "_always" in it.substringAfter("→") }) { "an option that persists was chosen: $lines" }
                check(!work.resolve("kp-denied.txt").exists() && work.resolve("kp-allowed.txt").exists()) { "kp-denied.txt exists: ${work.resolve("kp-denied.txt").exists()}, kp-allowed.txt exists: ${work.resolve("kp-allowed.txt").exists()}" }
                Unit to "${lines.size} requests: " + lines.joinToString(" | ") { it.substringAfter(" permission ") } + "; turns ended ${denied.stopReason}, ${allowed.stopReason}"
            } == null) return
        if (step("a cancel in the middle of a command, and the next prompt taken") {
                coroutineScope {
                    val turn = async { driver.prompt(seat, "Use the Bash tool to run exactly this command in the foreground, not in the background, and wait for it to finish: sleep 45") }
                    withTimeout(120.seconds) { while (!(asked.any { "sleep 45" in it.input.toString() } && driver.observe(seat, SeatFacts.TOOL_CALL_OPEN_FOR).value() != "0s")) delay(200) }
                    delay(3_000)
                    val open = driver.observe(seat, SeatFacts.TOOL_CALL_OPEN_FOR).value()
                    val sent = Instant.now()
                    driver.cancel(seat).ok()
                    val end = turn.await().ok()
                    val took = Duration.between(sent, Instant.now()).toMillis()
                    check(end.stopReason == "cancelled") { "the turn ended ${end.stopReason}" }
                    val next = driver.prompt(seat, "Reply with only the word: ok").ok()
                    Unit to "cancelled with a tool call open for $open; the turn ended ${end.stopReason} ${took} ms after the cancel; the next prompt ended ${next.stopReason}"
                }
            } == null) return
        spent += usage(driver)
        if (step("a kill reported at once, with no process left") {
                val tree = ProcessHandle.current().descendants().toList()
                val sent = Instant.now()
                driver.kill(seat, Duration.ofSeconds(5)).ok()
                val took = Duration.between(sent, Instant.now()).toMillis()
                val gone = facts.firstOrNull { it.fact == SeatFacts.PROCESS_GONE && it.subject == seat }
                checkNotNull(gone) { "seat.process_gone was not reported when kill returned" }
                delay(500)
                val left = tree.filter { it.isAlive }.map { "${it.pid()} ${it.info().command().orElse("?")}" }
                check(left.isEmpty()) { "processes left: $left" }
                Unit to "${tree.size} processes in the agent's tree before; kill returned after $took ms with ${gone.fact} reported (${gone.evidence}); none left"
            } == null) return
        if (step("a load into a fresh process whose next turn recalls the first") {
                val loaded = driver.load(spec, started.sessionId).ok()
                tokens += loaded.token
                val replayed = transcript().count { " replay " in it }
                val before = transcript().size
                val end = driver.prompt(seat, "What was the code word I asked you to remember earlier in this conversation? Reply with only the word.").ok()
                val said = transcript().drop(before).filter { " agent: " in it }.joinToString("") { it.substringAfter(" agent: ") }
                check(word in said) { "the agent said '$said', not $word" }
                check(loaded.token != started.token) { "the load reused the token" }
                Unit to "session ${loaded.sessionId} loaded with a fresh token; $replayed updates replayed; mode ${loaded.mode}, model ${loaded.model}, effort ${loaded.effort} set again; the next turn (${end.stopReason}) said '$said'"
            } == null) return
        spent += usage(driver)
        driver.kill(seat, Duration.ofSeconds(5))
    }

    private fun usage(driver: Driver): Long = (driver.observe(seat, SeatFacts.USAGE, UsageWindow.SESSION) as? Outcome.Ok)?.value?.value?.jsonPrimitive?.content?.toLong() ?: 0

    private suspend fun <T> step(name: String, block: suspend () -> Pair<T, String>): T? {
        val outcome = runCatching { block() }
        val (ok, detail) = outcome.fold({ true to it.second }, { false to (it.message ?: it.javaClass.simpleName) })
        steps += Triple(name, ok, detail)
        println("${if (ok) "ok    " else "FAILED"} $name: ${hide(detail)}")
        return outcome.getOrNull()?.first
    }

    private fun transcript(name: String = seat.value): List<String> = seats.resolve("_transcripts/$name.log").let { if (it.exists()) it.readLines() else emptyList() }

    /** Writes the evidence with every token replaced, searches it for them, and names the file. */
    private fun report(header: String): Boolean {
        val expected = if (modelsOnly) 1 else STEPS
        val passed = steps.size == expected && steps.all { it.second }
        val at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val evidence = buildString {
            appendLine("# Handshake${if (modelsOnly) " (models)" else ""} — ${if (passed) "PASS" else "FAIL"} — $at")
            appendLine()
            appendLine(header)
            appendLine("model $model, effort $effort (the roster's cheapest pair); roster models ${rosterModels.joinToString(", ") { it.first }}; tokens spent: $spent")
            appendLine()
            appendLine("| step | result | detail |")
            appendLine("|---|---|---|")
            steps.forEach { (name, ok, detail) -> appendLine("| $name | ${if (ok) "ok" else "FAILED"} | ${detail.replace("|", "\\|")} |") }
            if (steps.size < expected) appendLine("| … | not run | ${expected - steps.size} steps after the first that failed |")
            appendLine()
            for (name in listOf(MODELS_SEAT, seat.value)) {
                val lines = transcript(name)
                if (lines.isEmpty()) continue
                appendLine("## The transcript of seat $name")
                appendLine("```")
                lines.forEach(::appendLine)
                appendLine("```")
            }
        }
        val hidden = hide(evidence)
        val file = root.resolve("build/handshake/handshake-${if (modelsOnly) "models-" else ""}${installedOrLock()}-${at.toString().replace(":", "")}.md")
        file.parent.createDirectories()
        file.writeText(hidden)
        val leaked = secretValues().any { file.readText().contains(it) }
        run.toFile().deleteRecursively()
        println("tokens spent: $spent")
        println("RESULT: ${if (passed && !leaked) "PASS" else "FAIL"}${if (leaked) " — a token was found in the evidence" else ""} — evidence: $file")
        return passed && !leaked
    }

    private fun installedOrLock() = adapter.resolve("node_modules/$ADAPTER/package.json").takeIf { it.exists() }?.let { packageVersion(Json.parseToJsonElement(it.readText()).jsonObject) } ?: "not-installed"

    private fun secretValues(): List<String> = listOfNotNull(runCatching { tokenFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }) + tokens.map { it.value }

    private fun hide(text: String) = secretValues().fold(text) { acc, secret -> acc.replace(secret, "‹hidden›") }

    private fun packageVersion(json: kotlinx.serialization.json.JsonObject) = json["version"]!!.jsonPrimitive.content

    private fun <T> Outcome<T>.ok(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Failed -> error(reason)
    }

    private fun Outcome<ObservedFact>.value(): String? = (this as? Outcome.Ok)?.value?.value?.jsonPrimitive?.content

    companion object {
        const val ADAPTER = "@agentclientprotocol/claude-agent-acp"
        const val STEPS = 9
        /** A name shaped like a full id that names no model: the adapter must refuse it. */
        const val MADE_UP = "claude-nosuchmodel-5-5"
        const val MODELS_SEAT = "handshake-models"
    }
}

