package kp.driver.acp

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kp.driver.Office
import kp.driver.Outcome
import kp.driver.ok
import kp.driver.pidAlive
import kp.driver.sha256
import kp.model.SeatId
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** T7 — kill and load, for the next developer. */
class KillAndLoadSpec : StringSpec({
    val seat = SeatId("coder-1")

    "an agent that leaves when its input closes is gone within the grace, with its own exit code" {
        Office().use { office ->
            val driver = office.driver()
            driver.start(office.session("coder-1", office.agent("{}"))).ok()

            driver.kill(seat, Duration.ofSeconds(10)).ok()

            office.facts.single { it.fact == "seat.process_gone" }.evidence shouldBe "the agent's process ended with exit code 0 at 2026-11-15T10:00:00Z"
        }
    }

    "a command the agent leaves behind when it leaves within the grace is ended too" {
        Office().use { office ->
            val agent = office.agent("""{"onEof": "orphan", "turns": [[{"do": "run", "seconds": 300}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()
            val turn = async { driver.prompt(seat, "run") }
            eventually(10.seconds) { agent.received("child") shouldHaveSize 1 }

            driver.kill(seat, Duration.ofSeconds(10)).ok()

            turn.await() shouldBe Outcome.Failed("the protocol closed during the turn")
            agent.pids().filter(::pidAlive) shouldBe emptyList()
        }
    }

    "kill of a seat with no session fails" {
        Office().use { office ->
            office.driver().kill(seat, Duration.ZERO) shouldBe Outcome.Failed("seat coder-1 has no session")
        }
    }

    "a load gives the servers a fresh token, and the token before it is dead" {
        Office().use { office ->
            val again = office.agent("{}")
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", office.agent("{}"))).ok()
            driver.kill(seat, Duration.ofSeconds(5)).ok()

            val loaded = driver.load(office.session("coder-1", again), started.sessionId).ok()

            val header = again.received("session/load").single().getValue("mcpServers").jsonArray.single().jsonObject.getValue("headers").toString()
            header shouldBe """[{"name":"Authorization","sha256":"${sha256("Bearer " + loaded.token.value)}"}]"""
            office.deadTokens shouldBe listOf(started.token)
        }
    }

    "a session that cannot be loaded fails with the reason, and leaves no process" {
        Office().use { office ->
            val again = office.agent("""{"loadable": false}""")
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", office.agent("{}"))).ok()
            driver.kill(seat, Duration.ofSeconds(5)).ok()

            val failed = driver.load(office.session("coder-1", again), started.sessionId) as Outcome.Failed

            failed.reason shouldStartWith "session/load of ${started.sessionId} failed: "
            failed.reason.contains("Resource not found") shouldBe true
            again.pids().filter(::pidAlive) shouldBe emptyList()
        }
    }

    "a load while the seat's session is live fails, and starts nothing" {
        Office().use { office ->
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", office.agent("{}"))).ok()
            val again = office.agent("{}")

            (driver.load(office.session("coder-1", again), started.sessionId) as Outcome.Failed).reason shouldStartWith "seat coder-1 has a live session"
            again.received() shouldBe emptyList()
        }
    }

    "a replayed history is written as replay and moves no observation" {
        Office().use { office ->
            val first = office.agent("""{"turns": [[{"do": "usage", "used": 1000, "size": 2000}, {"do": "end"}]]}""")
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", first)).ok()
            driver.prompt(seat, "one").ok()
            driver.kill(seat, Duration.ofSeconds(5)).ok()

            driver.load(office.session("coder-1", office.agent("{}")), started.sessionId).ok()

            office.transcript("coder-1") shouldContain "2026-11-15T10:00:00Z coder-1 replay context: 1000 of 2000"
            driver.observe(seat, "seat.context_used") shouldBe Outcome.Failed("the agent has reported no context usage yet")
        }
    }
})
