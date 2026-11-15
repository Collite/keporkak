package kp.driver.acp

import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.rpc.ACPJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kp.driver.Answer
import kp.driver.Office
import kp.driver.Outcome
import kp.driver.ok
import kp.driver.claude.ClaudeAdapter
import java.nio.file.Files
import java.time.Duration

/** T2 — the process and `initialize`, for the next developer. The agent is the scripted fake (FakeAgent.kt). */
class LaunchSpec : StringSpec({
    fun Office.acp() = AcpDriver(ClaudeAdapter(tokenFile), seats, clock, { Answer.DENY }, listener)

    "the agent's environment is the caller's, the config home and the token — nothing is inherited" {
        Office().use { office ->
            val agent = office.agent("{}")
            val launched = office.acp().launch(office.session("coder-1", agent)).ok()

            agent.received("start").single()["env"].toString() shouldBe """["CLAUDE_CODE_OAUTH_TOKEN","CLAUDE_CONFIG_DIR","LANG"]"""
            launched.process.end(Duration.ZERO)
        }
    }

    "the client declares neither fs nor terminal, and speaks protocol version 1 only" {
        Office().use { office ->
            val agent = office.agent("{}")
            office.acp().launch(office.session("coder-1", agent)).ok().process.end(Duration.ZERO)

            val params = agent.received("initialize").single().getValue("params").jsonObject
            params["protocolVersion"] shouldBe JsonPrimitive(1)
            params.getValue("clientCapabilities").jsonObject["fs"].toString() shouldBe """{"readTextFile":false,"writeTextFile":false}"""
            params.getValue("clientCapabilities").jsonObject["terminal"] shouldBe JsonPrimitive(false)
        }
    }

    "an initialize reply whose auth.status is an object (W1) is repaired in the transport, and the repair is noted" {
        Office().use { office ->
            val agent = office.agent("""{"w1": true}""")
            val launched = office.acp().launch(office.session("coder-1", agent))

            (launched is Outcome.Ok) shouldBe true
            office.transcript("coder-1") shouldContain
                "2026-11-15T10:00:00Z coder-1 repaired W1: agentCapabilities.auth.status was {} where the SDK reads a boolean; dropped"
            (launched as Outcome.Ok).value.process.end(Duration.ZERO)
        }
    }

    "without the repair, the pinned SDK cannot decode the reply W1 repairs" {
        val reply = """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"auth":{"status":{}}},"authMethods":[]}"""
        shouldThrowAny { ACPJson.decodeFromString(InitializeResponse.serializer(), reply) }
        val repaired = Repairs.repair("""{"jsonrpc":"2.0","id":1,"result":$reply}""").line
        ACPJson.decodeFromString(InitializeResponse.serializer(), ACPJson.parseToJsonElement(repaired).jsonObject.getValue("result").toString()).agentCapabilities.loadSession shouldBe true
    }

    "a line that needs no repair passes unchanged" {
        val line = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{"auth":{"status":true}}}}"""
        Repairs.repair(line).line shouldBe line
        Repairs.repair("""{"jsonrpc":"2.0","method":"session/update"}""").notes shouldBe emptyList()
    }

    "a token file that cannot be read fails the start with a reason naming the file, and no process starts" {
        Office().use { office ->
            Files.delete(office.tokenFile)
            val agent = office.agent("{}")

            val failed = office.acp().launch(office.session("coder-1", agent)) as Outcome.Failed

            failed.reason shouldContain office.tokenFile.toString()
            agent.received() shouldBe emptyList()
        }
    }

    "a command that cannot be started fails with the reason, and the token is in no reason" {
        Office().use { office ->
            val spec = office.session("coder-1", office.agent("{}")).copy(command = listOf("/nonexistent/agent", office.claudeToken))

            val failed = office.acp().launch(spec) as Outcome.Failed

            failed.reason shouldContain "the agent cannot be started"
            failed.reason shouldNotContain office.claudeToken
        }
    }

    "an agent that ends before answering initialize fails, once — the driver retries nothing" {
        Office().use { office ->
            val spec = office.session("coder-1", office.agent("{}")).copy(command = listOf("/bin/true"))

            val failed = office.acp().launch(spec) as Outcome.Failed

            failed.reason shouldContain "initialize failed"
            office.transcript("coder-1").count { it.contains(" process ") && it.endsWith("started: /bin/true in ${office.worktree}") } shouldBe 1
        }
    }

    "the interface names no ACP type and no vendor" {
        val source = java.nio.file.Path.of(System.getProperty("kp.root"), "kp-driver/src/main/kotlin/kp/driver/Driver.kt").toFile().readText()
        source shouldNotContain "com.agentclientprotocol"
        Regex("(?i)claude|anthropic").find(source)?.value shouldBe null
    }
})
