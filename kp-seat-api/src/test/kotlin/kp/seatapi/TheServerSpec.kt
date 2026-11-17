package kp.seatapi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** S2.3 T2 — the server (contracts.md §5): the loopback listener, the token registry, the gate in front of every tool. */
class TheServerSpec : StringSpec({

    "the listener is bound to the loopback address, and a connection to another address of this host is refused" {
        SeatOffice().use { office ->
            office.api.address.address.isLoopbackAddress shouldBe true
            val others = NetworkInterface.networkInterfaces().toList().flatMap { it.inetAddresses().toList() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
            others.forEach { other ->
                shouldThrow<ConnectException> { Socket().use { it.connect(InetSocketAddress(other, office.api.address.port), 2_000) } }
            }
        }
    }

    "a listen address that is not on the loopback address is refused at start" {
        SeatOffice().use { office ->
            val wildcard = shouldThrow<IllegalArgumentException> { SeatApi.start(office.mailroom, office.tokens, "0.0.0.0:0") }
            wildcard.message shouldBe "listen.mcp '0.0.0.0:0' is not on the loopback address"
        }
    }

    "a call with a live token reaches the MCP server; with no token, a dead one, or the token in the URL, it is answered 401 and the log does not grow" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")
            val dead = office.seat("coder-2", role = "coder").also { office.tokens.revoke(it.token) }
            val initialize = """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "spec", "version": "0"}}}"""
            val lines = office.lines()

            val live = coder.post("/mcp", initialize)
            val refused = listOf(
                coder.post("/mcp", toolsCall("whoami"), token = null),
                dead.post("/mcp", toolsCall("whoami")),
                coder.post("/mcp?token=${coder.token}", toolsCall("whoami"), token = null),
                coder.post("/mcp?token=${coder.token}", toolsCall("whoami")),
            )

            live.status shouldBe 200
            live.body shouldContain "\"keporkak\""
            refused.map { it.status } shouldBe listOf(401, 401, 401, 401)
            refused.forEach { it.body shouldNotContain coder.token }
            office.lines() shouldBe lines
        }
    }

    "a token maps to its seat while it is live, and to nothing once it is revoked" {
        val tokens = Tokens()
        val binding = Binding("coder-1", "coder", "panda", STAGE, "claude-haiku-5-5", "low")

        tokens.register("t-1", binding)
        val live = tokens.bound("t-1")
        tokens.revoke("t-1")

        listOf(live, tokens.bound("t-1"), tokens.bound("t-2")) shouldBe listOf(binding, null, null)
    }
})
