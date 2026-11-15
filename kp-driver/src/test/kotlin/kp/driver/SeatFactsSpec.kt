package kp.driver

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.yamlMap
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
