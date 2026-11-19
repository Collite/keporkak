package kp.driver.acp

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kp.driver.Cancel
import kp.driver.Office
import kp.driver.Outcome
import kp.driver.TurnEnd
import kp.driver.ok
import kp.model.SeatId
import kotlin.time.Duration.Companion.seconds

/** T4 — turns, for the next developer. */
class TurnsSpec : StringSpec({
    val seat = SeatId("coder-1")

    "a turn ends with the agent's stop reason as it gave it, read as nothing more" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "end", "stopReason": "max_tokens"}], [{"do": "end", "stopReason": "refusal"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "one") shouldBe Outcome.Ok(TurnEnd("max_tokens", null))
            driver.prompt(seat, "two") shouldBe Outcome.Ok(TurnEnd("refusal", null))
            office.transcript("coder-1") shouldContain "2026-11-15T10:00:00Z coder-1 turn ended: max_tokens; no usage reported"
        }
    }

    "a prompt while a turn is in flight fails, and is not queued" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            val first = async { driver.prompt(seat, "the long one") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }

            driver.prompt(seat, "a second one") shouldBe Outcome.Failed("a turn is in flight for seat coder-1; the prompt is not queued")

            driver.cancel(seat).ok()
            first.await().ok().stopReason shouldBe "cancelled"
            agent.received("session/prompt") shouldHaveSize 1
        }
    }

    "cancel with no turn in flight is ok, says nothing was in flight, and sends nothing" {
        Office().use { office ->
            val agent = office.agent("{}")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.cancel(seat) shouldBe Outcome.Ok(Cancel.NOTHING_IN_FLIGHT)
            agent.received("cancel") shouldBe emptyList()
            office.transcript("coder-1").last() shouldBe "2026-11-15T10:00:00Z coder-1 cancel: no turn in flight, nothing sent"
        }
    }

    "an update between turns reaches the transcript through the same collector, after the turn's end" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "say", "text": "in the turn"}, {"do": "end"}, {"do": "say", "text": "after the turn"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "go").ok()

            eventually(10.seconds) {
                office.transcript("coder-1").takeLast(3) shouldBe listOf(
                    "2026-11-15T10:00:00Z coder-1 agent: in the turn",
                    "2026-11-15T10:00:00Z coder-1 turn ended: end_turn; no usage reported",
                    "2026-11-15T10:00:00Z coder-1 agent: after the turn",
                )
            }
        }
    }

    "a tool's result reaches the transcript with its update: its text, else its raw output, at most 2000 characters" {
        Office().use { office ->
            val agent = office.agent(
                """{"turns": [[{"do": "tool", "id": "t1", "title": "mcp__messaging__send"}, {"do": "tool_update", "id": "t1", "status": "completed", "text": "{\"status\":\"refused\",\"rule\":\"verdict\"}"},
                {"do": "tool", "id": "t2", "title": "Bash"}, {"do": "tool_update", "id": "t2", "status": "failed", "raw": {"exit": 2}},
                {"do": "tool", "id": "t3", "title": "Read"}, {"do": "tool_update", "id": "t3", "status": "completed", "text": "${"x".repeat(2003)}"},
                {"do": "tool", "id": "t4", "title": "Glob"}, {"do": "tool_update", "id": "t4", "status": "completed"}, {"do": "end"}]]}""",
            )
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "go").ok()

            office.transcript("coder-1").filter { " tool t" in it && " updated" !in it && "pending" !in it } shouldBe listOf(
                """2026-11-15T10:00:00Z coder-1 tool t1 completed → {"status":"refused","rule":"verdict"}""",
                """2026-11-15T10:00:00Z coder-1 tool t2 failed → {"exit":2}""",
                "2026-11-15T10:00:00Z coder-1 tool t3 completed → ${"x".repeat(2000)} … (3 more characters)",
                "2026-11-15T10:00:00Z coder-1 tool t4 completed",
            )
        }
    }

    "an agent that dies during a turn fails the turn at once" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            val turn = async { driver.prompt(seat, "the long one") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }

            ProcessHandle.of(agent.received("start").single()["pid"].toString().toLong()).get().destroyForcibly()

            turn.await() shouldBe Outcome.Failed("the protocol closed during the turn")
            driver.prompt(seat, "again") shouldBe Outcome.Failed("the session of seat coder-1 has ended")
            ProcessHandle.of(agent.received("child").single()["pid"].toString().toLong()).ifPresent { it.destroyForcibly() }
        }
    }
})
