package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kp.driver.Office
import kp.driver.SessionSpec
import kp.driver.ok
import kp.driver.sha256
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** T6 — the stop hook's call: the driver's two variables, and stop-gate against a daemon that answers, errs or is down, for the next developer. */
class StopGateSpec : StringSpec({
    "the driver mints the session token before it starts the process and gives the seat KP_HOOK_URL and KP_SESSION_TOKEN" {
        Office().use { office ->
            val agent = office.agent("""{"turns": []}""")
            val driver = office.driver()

            val started = driver.start(office.session("coder-1", agent).copy(hookUrl = "http://127.0.0.1:7711")).ok()

            val received = agent.received("start").single()
            received["KP_HOOK_URL"] shouldBe JsonPrimitive("http://127.0.0.1:7711")
            received["KP_SESSION_TOKEN_sha256"] shouldBe JsonPrimitive(sha256(started.token.value))
        }
    }

    "a session without a hook URL gets neither variable" {
        Office().use { office ->
            val agent = office.agent("""{"turns": []}""")
            office.driver().start(office.session("coder-1", agent)).ok()

            agent.received("start").single()["KP_HOOK_URL"] shouldBe JsonNull
            agent.received("start").single()["KP_SESSION_TOKEN_sha256"] shouldBe JsonNull
        }
    }

    "neither value reaches a transcript" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "leak"}, {"do": "end"}]]}""")
            val driver = office.driver()
            val started = driver.start(office.session("coder-1", agent).copy(hookUrl = "http://127.0.0.1:7799")).ok()

            driver.prompt(started.seat, "say your secrets").ok()

            val transcript = office.transcript("coder-1").joinToString("\n")
            transcript shouldNotContain started.token.value
            transcript shouldNotContain "127.0.0.1:7799"
        }
    }

    "stop-gate posts to the daemon's /hook/stop with the token as bearer, and the token is never one of curl's arguments" {
        StopStandIn(answer = """{"block": false, "reason": ""}""").use { daemon ->
            val bin = Files.createTempDirectory("kp-curl-")
            val argv = bin.resolve("argv")
            bin.resolve("curl").writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$argv'\nexec /usr/bin/curl \"\$@\"\n")
            bin.resolve("curl").toFile().setExecutable(true)

            val relayed = Hooks.run("stop-gate", listOf("3"), Hooks.STOP.toString(),
                mapOf("PATH" to "$bin:/usr/local/bin:/usr/bin:/bin", "KP_HOOK_URL" to daemon.url, "KP_SESSION_TOKEN" to "session-token-3")).trim()

            relayed shouldBe ""
            daemon.paths shouldContainExactly listOf("POST /hook/stop")
            daemon.authorizations shouldContainExactly listOf("Bearer session-token-3")
            daemon.bodies shouldContainExactly listOf("""{"stop_hook_active":false}""")
            argv.readText() shouldNotContain "session-token-3"
            bin.toFile().deleteRecursively()
        }
    }

    "a block without a reason, an error, an answer that is not JSON, or no daemon lets the turn end" {
        for (answer in listOf("""{"block": true}""", """{"block": true, "reason": ""}""", "not json", """{"block": "yes", "reason": "r"}""")) {
            StopStandIn(answer = answer).use { daemon -> Hooks.stopGate(limitSeconds = 3, url = daemon.url, token = "t") shouldBe "" }
        }
        StopStandIn(answer = """{"block": true, "reason": "r"}""", status = 500).use { daemon -> Hooks.stopGate(limitSeconds = 3, url = daemon.url, token = "t") shouldBe "" }
        val port = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        Hooks.stopGate(limitSeconds = 3, url = "http://127.0.0.1:$port", token = "t") shouldBe ""
        (System.nanoTime() - started) / 1_000_000 shouldBeLessThan 3_000L
    }

    "with no URL or no token in its environment, stop-gate asks nothing and lets the turn end" {
        StopStandIn(answer = """{"block": true, "reason": "r"}""").use { daemon ->
            Hooks.run("stop-gate", listOf("3"), Hooks.STOP.toString(), mapOf("KP_HOOK_URL" to daemon.url)).trim() shouldBe ""
            Hooks.run("stop-gate", listOf("3"), Hooks.STOP.toString(), mapOf("KP_SESSION_TOKEN" to "t")).trim() shouldBe ""
            daemon.connections.get() shouldBe 0
        }
    }

    "the stop hook's input says whether the turn already goes on because of a block" {
        StopStandIn(answer = """{"block": false}""").use { daemon ->
            val active = Hooks.STOP.toString().replace("\"stop_hook_active\":false", "\"stop_hook_active\":true")
            Hooks.run("stop-gate", listOf("3"), active, mapOf("KP_HOOK_URL" to daemon.url, "KP_SESSION_TOKEN" to "t"))

            daemon.bodies shouldContainExactly listOf("""{"stop_hook_active":true}""")
        }
    }
})
