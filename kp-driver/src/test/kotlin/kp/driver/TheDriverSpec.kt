package kp.driver

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.model.SeatId
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * S2.1 — The driver. The five specs of the stage list, each named as its sentence.
 *
 * Seam: the driver's interface, with the scripted fake agent at the other end — a real child process speaking ACP over
 * stdio (fake/FakeAgent.kt) — an injected clock that stands still, and a temporary office (Office.kt). What the agent
 * received is read from the fake's own record; what the driver wrote, from the seat's transcript.
 */
class TheDriverSpec : StringSpec({
    "a started session is reported with its seat, and the agent received the working directory, the config home, the mode, the model, the effort and the messaging server with its token" {
        Office().use { office ->
            val agent = office.agent("""{"mode": "auto", "model": "default", "effort": "default"}""")

            val started = office.driver().start(office.session("coder-1", agent, model = "claude-haiku-5-5", effort = "low")).ok()

            started.seat shouldBe SeatId("coder-1")
            listOf(started.mode, started.model, started.effort) shouldBe listOf("default", "haiku", "low")
            val process = agent.received("start").single()
            process["cwd"] shouldBe JsonPrimitive(office.worktree.toString())
            process["CLAUDE_CONFIG_DIR"] shouldBe JsonPrimitive(office.seats.resolve("coder-1").toString())
            process["CLAUDE_CODE_OAUTH_TOKEN_sha256"] shouldBe JsonPrimitive(sha256(office.claudeToken))
            process["env"].toString() shouldBe """["CLAUDE_CODE_OAUTH_TOKEN","CLAUDE_CONFIG_DIR","LANG"]"""
            agent.received("session/set_config_option").map { "${it.text("configId")}=${it.text("value")}" } shouldBe
                listOf("mode=default", "model=claude-haiku-5-5", "effort=low")
            val server = agent.received("session/new").single().getValue("mcpServers").jsonArray.single().jsonObject
            server.text("name") shouldBe "kp-mail"
            server.text("url") shouldBe "http://127.0.0.1:7711/mcp"
            server.getValue("headers").toString() shouldBe """[{"name":"Authorization","sha256":"${sha256("Bearer " + started.token.value)}"}]"""
        }
    }

    "a prompt's updates reach the transcript in order, and the turn ends with its stop reason and its usage; a second prompt on the same session is another turn" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [
                [{"do": "say", "text": "reading the list"},
                 {"do": "tool", "id": "t1", "title": "Read STAGE.md", "kind": "read"},
                 {"do": "tool_update", "id": "t1", "status": "completed"},
                 {"do": "usage", "used": 1200, "size": 200000},
                 {"do": "end", "stopReason": "end_turn", "usage": {"input": 100, "output": 20, "total": 120}}],
                [{"do": "say", "text": "three tasks"},
                 {"do": "end", "stopReason": "end_turn", "usage": {"input": 50, "output": 5, "total": 55}}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            val first = driver.prompt(SeatId("coder-1"), "read the stage list").ok()
            val second = driver.prompt(SeatId("coder-1"), "how many tasks?").ok()

            first shouldBe TurnEnd("end_turn", Usage(input = 100, output = 20, total = 120))
            second shouldBe TurnEnd("end_turn", Usage(input = 50, output = 5, total = 55))
            office.transcript("coder-1") shouldContainInOrder listOf(
                "2026-11-15T10:00:00Z coder-1 prompt: read the stage list",
                "2026-11-15T10:00:00Z coder-1 agent: reading the list",
                "2026-11-15T10:00:00Z coder-1 tool t1 pending: Read STAGE.md",
                "2026-11-15T10:00:00Z coder-1 tool t1 completed",
                "2026-11-15T10:00:00Z coder-1 context: 1200 of 200000",
                "2026-11-15T10:00:00Z coder-1 turn ended: end_turn; tokens input 100, output 20, total 120",
                "2026-11-15T10:00:00Z coder-1 prompt: how many tasks?",
                "2026-11-15T10:00:00Z coder-1 agent: three tasks",
                "2026-11-15T10:00:00Z coder-1 turn ended: end_turn; tokens input 50, output 5, total 55",
            )
        }
    }

    "a permission request is answered with a one-time option only, whatever the agent offers" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[
                {"do": "ask", "id": "p1", "title": "git status", "input": {"command": "git status"},
                 "options": ["allow_always", "allow_once", "reject_always", "reject_once"]},
                {"do": "ask", "id": "p2", "title": "git push", "input": {"command": "git push"},
                 "options": ["allow_always", "allow_once", "reject_always", "reject_once"]},
                {"do": "ask", "id": "p3", "title": "git log", "input": {"command": "git log"},
                 "options": ["allow_always", "reject_always"]},
                {"do": "end", "stopReason": "end_turn"}]]}""")
            val driver = office.driver(answerer = { request -> if (request.input.toString().contains("git push")) Answer.DENY else Answer.ALLOW })
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(SeatId("coder-1"), "look at the repository").ok()

            agent.received("permission").map { "${it.text("toolCallId")} ${it.text("outcome")} ${it.text("kind")}" } shouldBe listOf(
                "p1 selected allow_once",
                "p2 selected reject_once",
                "p3 cancelled null",
            )
            office.transcript("coder-1") shouldContainInOrder listOf(
                "2026-11-15T10:00:00Z coder-1 permission p1: git status {\"command\":\"git status\"} → allow_once",
                "2026-11-15T10:00:00Z coder-1 permission p2: git push {\"command\":\"git push\"} → reject_once",
                "2026-11-15T10:00:00Z coder-1 permission p3: git log {\"command\":\"git log\"} → denied: allow_once is not offered (offered: allow_always, reject_always)",
            )
        }
    }

    "a cancelled turn ends `cancelled` and the session takes the next prompt; a killed agent is reported gone at once and leaves no process behind" {
        Office().use { office ->
            val agent = office.agent("""{"onEof": "linger", "turns": [
                [{"do": "tool", "id": "t1", "title": "sleep 300"}, {"do": "run", "seconds": 300}, {"do": "end"}],
                [{"do": "say", "text": "going on"}, {"do": "end"}],
                [{"do": "tool", "id": "t2", "title": "sleep 300"}, {"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            val seat = SeatId("coder-1")
            driver.start(office.session("coder-1", agent)).ok()

            val cancelled = async { driver.prompt(seat, "run the long command") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }
            driver.cancel(seat) shouldBe Outcome.Ok(Cancel.SENT)
            cancelled.await().ok().stopReason shouldBe "cancelled"
            driver.prompt(seat, "go on").ok().stopReason shouldBe "end_turn"

            val killed = async { driver.prompt(seat, "run it again") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 2 }
            driver.kill(seat, Duration.ofMillis(300)) shouldBe Outcome.Ok(Unit)

            office.facts.map { it.content().toString() } shouldContainInOrder listOf(
                """{"fact":"seat.process_gone","subject":"coder-1","value":true,"evidence":"the agent's process ended with exit code 137 at 2026-11-15T10:00:00Z"}""",
            )
            (killed.await() is Outcome.Failed) shouldBe true
            agent.pids().filter(::pidAlive) shouldBe emptyList()
        }
    }

    "a session loaded into a fresh process replays its history and has its mode, model and effort set again" {
        Office().use { office ->
            val first = office.agent("""{"turns": [[{"do": "say", "text": "noted"}, {"do": "end"}]]}""")
            val again = office.agent("""{"mode": "auto", "model": "default", "effort": "default", "turns": [[{"do": "recall"}, {"do": "end"}]]}""")
            val driver = office.driver()
            val seat = SeatId("coder-1")
            val started = driver.start(office.session("coder-1", first)).ok()
            driver.prompt(seat, "the code word is heron").ok()
            driver.kill(seat, Duration.ofSeconds(2)).ok()

            val loaded = driver.load(office.session("coder-1", again), started.sessionId).ok()
            driver.prompt(seat, "what was the code word?").ok()

            loaded.sessionId shouldBe started.sessionId
            loaded.token shouldNotBe started.token
            office.deadTokens shouldBe listOf(started.token)
            again.received("session/set_config_option").map { "${it.text("configId")}=${it.text("value")}" } shouldBe
                listOf("mode=default", "model=claude-haiku-5-5", "effort=low")
            office.transcript("coder-1") shouldContainInOrder listOf(
                "2026-11-15T10:00:00Z coder-1 loading session ${started.sessionId}",
                "2026-11-15T10:00:00Z coder-1 replay user: the code word is heron",
                "2026-11-15T10:00:00Z coder-1 replay agent: noted",
                "2026-11-15T10:00:00Z coder-1 prompt: what was the code word?",
                "2026-11-15T10:00:00Z coder-1 agent: you first said: the code word is heron",
            )
        }
    }
})

fun <T> Outcome<T>.ok(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Failed -> throw AssertionError("expected ok, the driver said failed: $reason")
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.let { if (it.isString) it.content else it.toString() }
