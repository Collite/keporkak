package kp.driver.acp

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kp.driver.Office
import kp.driver.UsageWindow
import kp.driver.ok
import kp.model.SeatId
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** T7 — issue #16's three loose ends of S2.1, a spec each, for the next developer. */
class LooseEndsSpec : StringSpec({
    val seat = SeatId("coder-1")

    "a tool call left open when its turn ends stays open, until it closes, a turn is cancelled or the process ends" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [
                [{"do": "tool", "id": "t1", "title": "watch the build"}, {"do": "end"}],
                [{"do": "tool_update", "id": "t1", "status": "completed"}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "start watching").ok().stopReason shouldBe "end_turn"
            office.clock.advance(Duration.ofSeconds(42))

            driver.observe(seat, "seat.tool_call_open_for").ok().value shouldBe JsonPrimitive("42s")
            driver.prompt(seat, "stop watching").ok()
            driver.observe(seat, "seat.tool_call_open_for").ok().value shouldBe JsonPrimitive("0s")
        }
    }

    "a tool call left open when its turn ends is closed when the process ends (issue #22)" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "tool", "id": "t1", "title": "watch the build"}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            driver.prompt(seat, "start watching").ok().stopReason shouldBe "end_turn"
            office.clock.advance(Duration.ofSeconds(42))
            driver.observe(seat, "seat.tool_call_open_for").ok().value shouldBe JsonPrimitive("42s")

            ProcessHandle.of(agent.pids().single()).get().destroyForcibly()

            eventually(10.seconds) { driver.observe(seat, "seat.process_gone").ok().value shouldBe JsonPrimitive(true) }
            driver.observe(seat, "seat.tool_call_open_for").ok().value shouldBe JsonPrimitive("0s")
            driver.observe(seat, "seat.tool_call_open_for").ok().evidence shouldBe "no tool call is open"
        }
    }

    "the session window of seat.usage starts again after a load" {
        Office().use { office ->
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", office.agent("""{"turns": [[{"do": "end", "usage": {"input": 100, "output": 20, "total": 120}}]]}"""))).ok()
            driver.prompt(seat, "one").ok()
            driver.observe(seat, "seat.usage", UsageWindow.SESSION).ok().value shouldBe JsonPrimitive(120)
            driver.kill(seat, Duration.ofSeconds(5)).ok()

            driver.load(office.session("coder-1", office.agent("{}")), started.sessionId).ok()

            val session = driver.observe(seat, "seat.usage", UsageWindow.SESSION).ok()
            session.value shouldBe JsonPrimitive(0)
            session.evidence shouldBe "0 turns ended in this session, 0 with usage reported"
        }
    }

    "the driver forgets a process that ended" {
        Office().use { office ->
            val driver = office.driver() as AcpDriver
            driver.start(office.session("coder-1", office.agent("{}"))).ok()
            driver.start(office.session("coder-2", office.agent("""{"turns": [[{"do": "hangup"}]]}"""))).ok()
            driver.held() shouldBe 2

            driver.kill(seat, Duration.ofSeconds(5)).ok()
            eventually(10.seconds) { driver.held() shouldBe 1 }
            driver.kill(SeatId("coder-2"), Duration.ZERO).ok()
            eventually(10.seconds) { driver.held() shouldBe 0 }
        }
    }
})
