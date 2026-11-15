package kp.driver.acp

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonArray
import kp.driver.McpServer
import kp.driver.Offers
import kp.driver.Office
import kp.driver.Outcome
import kp.driver.ok
import kp.driver.pidAlive
import kp.driver.sha256
import kp.model.SeatId
import kotlin.time.Duration.Companion.seconds

/** T3 — the session: servers and headers, the token, mode/model/effort, the offers. For the next developer. */
class SessionOpenSpec : StringSpec({
    "every server the caller names reaches session/new with its headers; only one that carries the token gets it" {
        Office().use { office ->
            val agent = office.agent("{}")
            val servers = listOf(
                McpServer("kp-mail", "http://127.0.0.1:7711/mcp", carriesToken = true),
                McpServer("docs", "http://127.0.0.1:9000/mcp", headers = mapOf("X-Office" to "panda")),
            )
            val started = office.driver().start(office.session("coder-1", agent, servers = servers)).ok()

            agent.received("session/new").single().getValue("mcpServers").jsonArray.toString() shouldBe
                """[{"type":"http","name":"kp-mail","url":"http://127.0.0.1:7711/mcp","headers":[{"name":"Authorization","sha256":"${sha256("Bearer " + started.token.value)}"}]},""" +
                """{"type":"http","name":"docs","url":"http://127.0.0.1:9000/mcp","headers":[{"name":"X-Office","sha256":"${sha256("panda")}"}]}]"""
        }
    }

    "each started process gets its own random token, which shows in no string" {
        Office().use { office ->
            val driver = office.driver()
            val one = driver.start(office.session("coder-1", office.agent("{}"))).ok()
            val two = driver.start(office.session("coder-2", office.agent("{}"))).ok()

            one.token shouldNotBe two.token
            one.token.value.length shouldBe 43
            one.toString().contains(one.token.value) shouldBe false
            one.token.toString() shouldStartWith "SessionToken(sha256:"
        }
    }

    "what the agent offers is kept and can be asked for" {
        Office().use { office ->
            val driver = office.driver()
            driver.start(office.session("coder-1", office.agent("""{"efforts": ["low", "medium", "high", "xhigh", "max"]}"""))).ok()

            driver.offers(SeatId("coder-1")) shouldBe Outcome.Ok(Offers(
                modes = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions"),
                models = listOf("default", "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"),
                efforts = listOf("default", "low", "medium", "high", "xhigh", "max"),
            ))
            driver.offers(SeatId("coder-9")) shouldBe Outcome.Failed("seat coder-9 has no session")
        }
    }

    "a model the agent does not offer fails the start, says what is offered, and leaves no process" {
        Office().use { office ->
            val agent = office.agent("{}")

            val failed = office.driver().start(office.session("coder-1", agent, model = "claude-opus-9")) as Outcome.Failed

            failed.reason shouldBe "the agent does not offer model claude-opus-9 (offered: default, claude-haiku-5-5, claude-sonnet-5-5, claude-opus-5-5)"
            agent.pids().filter(::pidAlive) shouldBe emptyList()
            office.deadTokens shouldBe emptyList()
        }
    }

    "a second start for a seat whose session is live fails, and starts nothing" {
        Office().use { office ->
            val driver = office.driver()
            driver.start(office.session("coder-1", office.agent("{}"))).ok()
            val second = office.agent("{}")

            (driver.start(office.session("coder-1", second)) as Outcome.Failed).reason shouldStartWith "seat coder-1 has a live session"
            second.received() shouldBe emptyList()
        }
    }

    "the token is reported dead when the session's process ends" {
        Office().use { office ->
            val agent = office.agent("{}")
            val started = office.driver().start(office.session("coder-1", agent)).ok()

            ProcessHandle.of(agent.pids().single()).get().destroyForcibly()

            eventually(10.seconds) { office.deadTokens shouldBe listOf(started.token) }
        }
    }
})
