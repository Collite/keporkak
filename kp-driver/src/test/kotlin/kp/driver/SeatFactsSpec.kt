package kp.driver

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.yamlMap
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.time.Duration.Companion.seconds
import kp.driver.scan.comparisons

/**
 * S2.1 T6 — the facts the driver observes: one spec named after each `seat.*` fact whose source is O in
 * `rules/vocabulary.yaml`, and the checks that hold the driver to the vocabulary and to reporting, never concluding.
 * Each fact is shown as its `fact` record's content.
 */
class SeatFactsSpec : StringSpec({
    val seat = SeatId("coder-1")
    fun Driver.fact(name: String, window: UsageWindow? = null) = observe(seat, name, window).ok().content().toString()

    "seat.process_gone is false while the agent runs, and true at once when its process ends, with the exit code" {
        Office().use { office ->
            val agent = office.agent("{}")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            val pid = agent.pids().single()
            driver.fact("seat.process_gone") shouldBe """{"fact":"seat.process_gone","subject":"coder-1","value":false,"evidence":"the agent's process $pid is running"}"""

            ProcessHandle.of(pid).get().destroyForcibly()

            eventually(10.seconds) {
                office.facts.map { it.content().toString() } shouldContain
                    """{"fact":"seat.process_gone","subject":"coder-1","value":true,"evidence":"the agent's process ended with exit code 137 at 2026-11-15T10:00:00Z"}"""
            }
            driver.fact("seat.process_gone") shouldBe """{"fact":"seat.process_gone","subject":"coder-1","value":true,"evidence":"the agent's process ended with exit code 137 at 2026-11-15T10:00:00Z"}"""
        }
    }

    "seat.protocol_closed is reported when the agent closes the protocol, though its process still runs" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "hangup"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            driver.fact("seat.protocol_closed") shouldBe """{"fact":"seat.protocol_closed","subject":"coder-1","value":false,"evidence":"the protocol is open"}"""

            driver.prompt(seat, "hang up") shouldBe Outcome.Failed("the protocol closed during the turn")

            office.facts.map { it.content().toString() } shouldContain
                """{"fact":"seat.protocol_closed","subject":"coder-1","value":true,"evidence":"the protocol closed at 2026-11-15T10:00:00Z"}"""
            driver.observe(seat, "seat.process_gone").ok().value shouldBe JsonPrimitive(false)
        }
    }

    "seat.silent_for is the time since the agent last sent anything while a turn is in flight, and zero with none" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "tool", "id": "t1", "title": "sleep 300"}, {"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            driver.fact("seat.silent_for") shouldBe """{"fact":"seat.silent_for","subject":"coder-1","value":"0s","evidence":"no turn is in flight"}"""
            val turn = async { driver.prompt(seat, "run it") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }

            office.clock.advance(Duration.ofMillis(42_500))

            driver.fact("seat.silent_for") shouldBe """{"fact":"seat.silent_for","subject":"coder-1","value":"42.5s","evidence":"a turn is in flight since 2026-11-15T10:00:00Z; the agent last sent something at 2026-11-15T10:00:00Z"}"""
            driver.cancel(seat).ok()
            turn.await().ok()
            driver.observe(seat, "seat.silent_for").ok().value shouldBe JsonPrimitive("0s")
        }
    }

    "seat.tool_call_open_for is the time since the oldest open tool call was opened, and zero with none" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "tool", "id": "t1", "title": "sleep 300"}, {"do": "tool_update", "id": "t1", "status": "in_progress"}, {"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            driver.fact("seat.tool_call_open_for") shouldBe """{"fact":"seat.tool_call_open_for","subject":"coder-1","value":"0s","evidence":"no tool call is open"}"""
            val turn = async { driver.prompt(seat, "run it") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }

            office.clock.advance(Duration.ofSeconds(7))

            eventually(10.seconds) {
                driver.fact("seat.tool_call_open_for") shouldBe """{"fact":"seat.tool_call_open_for","subject":"coder-1","value":"7s","evidence":"tool call t1 is open since 2026-11-15T10:00:00Z (1 open)"}"""
            }
            driver.cancel(seat).ok()
            turn.await().ok().stopReason shouldBe "cancelled"
            driver.observe(seat, "seat.tool_call_open_for").ok().value shouldBe JsonPrimitive("0s")
        }
    }

    "seat.context_used is the fraction of the context window the agent last reported in use" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "usage", "used": 50000, "size": 200000}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            driver.observe(seat, "seat.context_used") shouldBe Outcome.Failed("the agent has reported no context usage yet")

            driver.prompt(seat, "read").ok()

            driver.fact("seat.context_used") shouldBe """{"fact":"seat.context_used","subject":"coder-1","value":0.25,"evidence":"50000 of 200000 at 2026-11-15T10:00:00Z"}"""
        }
    }

    "seat.usage is the tokens of the last turn that ended, and their sum over the session" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [
                [{"do": "end", "usage": {"input": 100, "output": 20, "total": 120}}],
                [{"do": "end", "usage": {"input": 50, "output": 5, "total": 55}}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "one").ok()
            driver.prompt(seat, "two").ok()

            driver.fact("seat.usage", UsageWindow.TURN) shouldBe """{"fact":"seat.usage","subject":"coder-1","value":55,"evidence":"the last turn ended at 2026-11-15T10:00:00Z: input 50, output 5, total 55"}"""
            driver.fact("seat.usage", UsageWindow.SESSION) shouldBe """{"fact":"seat.usage","subject":"coder-1","value":175,"evidence":"2 turns ended in this session, 2 with usage reported"}"""
            driver.observe(seat, "seat.usage") shouldBe Outcome.Failed("seat.usage needs a window: turn or session")
        }
    }

    "the driver observes exactly the vocabulary's seat facts whose source is O, and each has a spec named after it" {
        val vocabulary = Yaml.default.parseToYamlNode(root("rules/vocabulary.yaml").readText()).yamlMap.get<com.charleskorn.kaml.YamlMap>("facts")!!
        val observed = vocabulary.entries.filter { (name, fields) -> name.content.startsWith("seat.") && fields.yamlMap.getScalar("source")?.content == "O" }.keys.map { it.content }.toSet()

        SeatFacts.names shouldBe observed
        val specs = Regex("^\\s*\"(seat\\.[a-z_]+) ", RegexOption.MULTILINE).findAll(root("kp-driver/src/test/kotlin/kp/driver/SeatFactsSpec.kt").readText()).map { it.groupValues[1] }.toSet()
        specs shouldBe observed
    }

    "a fact the vocabulary does not hold cannot be made or asked for" {
        shouldThrow<IllegalArgumentException> { ObservedFact("seat.stalled", SeatId("coder-1"), JsonPrimitive(true), "") }
        Office().use { office ->
            office.driver().observe(seat, "seat.idle") shouldBe Outcome.Failed("the driver observes no fact named seat.idle")
        }
    }

    "the driver's source holds no word for a conclusion" {
        val words = Regex("(?i)\\b(stall|stalled|stalling|done|idle|hung|stuck)\\b")
        val found = Files.walk(root("kp-driver/src/main")).use { files -> files.filter { it.extension == "kt" }.toList() }
            .flatMap { file -> file.readText().lines().mapIndexedNotNull { i, line -> words.find(line)?.let { "${root("").relativize(file)}:${i + 1}: ${it.value}" } } }

        found shouldBe emptyList()
    }

    "the driver's source compares no observation with a limit" {
        // Every line of the driver's source that compares, in any form, is listed as written with what it compares: a
        // new one — an observation against a limit among them — or a listed one changed turns this red until it is
        // listed again, and a reviewer reads why.
        val found = Files.walk(root("kp-driver/src/main")).use { files -> files.filter { it.extension == "kt" }.toList() }
            .flatMap { file -> comparisons(file.readText()).map { "${file.fileName}: $it" } }

        found.sorted() shouldBe NOT_A_LIMIT.keys.sorted()
    }

    "the scan sees the watchdog threshold review-008 wrote into Observations.silentFor" {
        // review-008, re-review of B1: this line, after `val heard = …`, left the spec above green.
        val source = root("kp-driver/src/main/kotlin/kp/driver/acp/Observations.kt").readText()
        val threshold = "        if (Duration.between(heard, clock.instant()) > Duration.ofMinutes(20)) " +
            "return fact(SeatFacts.SILENT_FOR, duration(Duration.ZERO), \"over the watchdog limit\")"
        val written = source.replaceFirst(Regex("""(?m)^[ \t]*val heard = .*\n"""), "\$0$threshold\n")

        written shouldNotBe source
        comparisons(written) - comparisons(source).toSet() shouldBe listOf(threshold.trim())
    }

    "the source scan finds a comparison in any form, and takes no type argument, arrow, comment or string for one" {
        // review-008, B1: a Duration threshold and a comparison with a lowercase name went unseen. Its re-review: a
        // side that ends in a call with a call inside, a bracket, an index or `!!`, or a right side in brackets.
        val compares = listOf(
            "if (silent > Duration.ofMinutes(20)) return fact(SeatFacts.SILENT_FOR, duration(silent), \"over the watchdog's limit\")",
            "if (silent >= limit) return null",
            "if (Duration.between(heard, clock.instant()) > Duration.ofMinutes(20)) return fact(SeatFacts.SILENT_FOR, duration(Duration.ZERO), \"over the watchdog limit\")",
            "Duration.between(heard, clock.instant()) >= limit",
            "f(g()) > 5",
            "(now - heard) > limit",
            "samples[0] > limit",
            "lastHeard!! < since",
            "silent > (limit)",
            "if (silent<limit || used>=cap) w",
            "x = listOf(silent<limit, used>cap)",
            "if (silent-->limit) w",
            "val over = used.compareTo(limit) > 0",
            "when (Long.compare(used, cap)) { 1 -> w }",
            "if (Duration.between(heard, now).isAfter(limit)) x",
            "if ((limit - silent).isNegative) x",
            "val capped = silent.coerceAtMost(limit)",
            "val most = maxOf(used, limit)",
            "val most = max(used, limit)",
            "val most = listOf(used, limit).max()",
            "val first = listOf(used, limit).sorted().first()",
            "if (silent in Duration.ZERO..limit) y",
            "for (i in 0 until n) z",
            "val c = '\"'; if (a > b) x",
            "val s = \"\\\"}\"; if (a > b) x",
            "val s = \"\${if (silent > limit) \"over\" else \"\"}\"",
        )
        compares.filter { comparisons(it) != listOf(it) } shouldBe emptyList()
        comparisons("if (Duration.between(heard, now)\n    > limit) x") shouldBe listOf("> limit) x")

        comparisons(
            listOf(
                "private val open: MutableMap<String, Pair<Instant, List<Long>>> = mutableMapOf()",
                "fun observe(fact: String): Outcome<ObservedFact> = when (fact) { else -> Outcome.Failed(\"x\") }",
                "open.forEach { (id, at) -> note(id, at) }",
                "val until = System.nanoTime() + grace.toNanos()",
                "fun <T : Enum<T>> wire(value: T, serializer: KSerializer<T>): String = x",
                "private val asking = ConcurrentHashMap.newKeySet<Deferred<Answer>>()",
                "val written: CompletableDeferred<Unit>? get() = null",
                "data class Ok<out T>(val value: T) : Outcome<T>",
                "val onNote: (String) -> Unit = { }",
                "val line = \"silent > limit, 0..9, x.max()\" // used >= limit, in a comment",
                "val c = '>'",
                "/** silent > limit, in a comment */",
                "/* a block comment /* nested */ silent > limit",
                "   silent > limit */",
                "val raw = \"\"\"",
                "    silent > limit \${'\$'}",
                "\"\"\"",
            ).joinToString("\n"),
        ) shouldBe emptyList()
    }

    "the transcript is <roots.seats>/_transcripts/<seat>.log, appended across the seat's sessions" {
        Office().use { office ->
            office.driver().use { it.start(office.session("coder-1", office.agent("{}"))).ok() }
            val first = office.transcript("coder-1")

            office.driver().start(office.session("coder-1", office.agent("{}"))).ok()

            Files.exists(office.seats.resolve("_transcripts/coder-1.log")) shouldBe true
            office.transcript("coder-1").take(first.size) shouldBe first
            office.transcript("coder-1").count { it.contains(" coder-1 started: session ") } shouldBe 2
        }
    }
})

private fun root(path: String): Path = Path.of(System.getProperty("kp.root")).resolve(path)

/** Every line of the driver's source that compares, by file and as written, and why none is an observation against a limit. */
private val NOT_A_LIMIT = mapOf(
    "AgentProcess.kt: while (process.isAlive && System.nanoTime() < until) {" to "kill's grace period, which the caller gives (T7)",
    "AgentBinding.kt: override fun toString() = \"Environment(\${variables.keys.sorted()})\"" to
        "the environment's variable names, sorted to be shown — never their values",
    "ClaudeAdapter.kt: if (lines > 1) return Outcome.Failed(\"the Claude token file \$tokenFile holds \$lines lines; the token stands alone on one line (office/README.md)\")" to
        "the token file's lines — an input, not an observation",
    "Observations.kt: val heard = listOfNotNull(since, lastHeard).max()" to
        "the later of two instants: when the turn began, when the agent was last heard",
    "Observations.kt: val (id, since) = open.entries.minByOrNull { it.value }?.toPair() ?: return fact(SeatFacts.TOOL_CALL_OPEN_FOR, duration(Duration.ZERO), \"no tool call is open\")" to
        "the oldest open tool call, whose age is reported",
    "Observations.kt: if (size <= 0) return Outcome.Failed(\"the agent reported a context size of \$size\")" to
        "a context size the fraction cannot be computed from — an input",
    "ConfigHomes.kt: fun <T> read(path: String, missing: String, parse: (String) -> Outcome<T>): T? =" to
        "a generic helper's type parameter and function type (S2.2) — no comparison",
    "Packaging.kt: return problems.distinct().sorted()" to "the packaging check's problem lines, sorted to be reported (S2.2)",
    "Packaging.kt: while (i < glob.length) {" to "a glob's characters, read one by one into an expression (S2.2)",
    "Lines.kt: return \" → \" + if (text.length <= OUTPUT_LIMIT) text else text.take(OUTPUT_LIMIT) + \" … (\${text.length - OUTPUT_LIMIT} more characters)\"" to
        "a tool's result in the transcript (S2.5): how much of it a line carries — the transcript's length, no observation of a seat",
)
