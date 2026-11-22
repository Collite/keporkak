package kp.seatapi

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.runBlocking
import kp.mailroom.Mailroom
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The seat API (architecture.md §2, `kp-seat-api`; contracts.md §5, §11): the five seat tools as an MCP server over
 * streamable HTTP at `/mcp`, and the stop hook's `/hook/stop`, on one listener bound to the loopback address and
 * nothing else. Every call is bound to one seat by its session token, read from the `Authorization` header only; a call
 * without a live one is answered 401 before any tool runs — the mailroom never hears of it. No token is written to a
 * log line, an error or a tool's answer.
 */
class SeatApi private constructor(private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>) : AutoCloseable {
    /** The address the listener is bound to. */
    val address: InetSocketAddress = runBlocking { server.engine.resolvedConnectors().first() }.let { InetSocketAddress(it.host, it.port) }

    override fun close() = server.stop(0, 0)

    companion object {
        /**
         * Starts the listener on [listen] (`listen.mcp` of `pool/offices.yaml`, `host:port`; port 0 picks one), in front of
         * [mailroom], with [tokens] as its registry; [github] answers a seat's ask for a GitHub token (Amendment 35). A
         * host that is not a loopback address is refused: the API is bound to the loopback address and nothing else
         * (contracts.md §5).
         */
        fun start(mailroom: Mailroom, tokens: Tokens, listen: String, github: GitHubTokens = GitHubTokens.NONE): SeatApi {
            val host = listen.substringBeforeLast(':', "")
            val port = requireNotNull(listen.substringAfterLast(':').toIntOrNull()) { "listen.mcp '$listen' names no port" }
            require(host.isNotEmpty() && InetAddress.getByName(host.removeSurrounding("[", "]")).isLoopbackAddress) {
                "listen.mcp '$listen' is not on the loopback address"
            }
            val tools = Tools(mailroom)
            val gate = StopGate(mailroom)
            val server = embeddedServer(CIO, host = host, port = port) { module(tokens, tools, gate) }
            return SeatApi(server.start(wait = false))
        }
    }
}

/** What the token gate bound the call to: the seat and its binding, and the key its session's blocks are counted under. */
internal class Bound(val binding: Binding, val session: String)

internal val BOUND = AttributeKey<Bound>("kp.seat")

private fun Application.module(tokens: Tokens, tools: Tools, gate: StopGate) {
    install(tokenGate(tokens))
    // Stateless (contracts.md §5): each POST is served by a server made for the seat the call's token names, so a
    // call is bound to the token it carries — not to one an earlier call carried. The pinned Claude Code speaks it
    // (S2.1's handshake: the stand-in answered the same way).
    mcpStatelessStreamableHttp(path = MCP) { tools.server(call.attributes[BOUND].binding) }
    gate.route(this)
}

/**
 * Every call is from the loopback address and carries a live session token as `Authorization: Bearer <token>`, and
 * nothing in its URL after the path — a token is accepted in the header only. Otherwise it is answered before any
 * route runs: 403 from elsewhere, 401 without a live token. The answer names no token.
 */
private fun tokenGate(tokens: Tokens) = createApplicationPlugin("kp-seat-token") {
    onCall { call ->
        if (!InetAddress.getByName(call.request.local.remoteAddress).isLoopbackAddress) return@onCall call.refuse(HttpStatusCode.Forbidden, "only the loopback address is served")
        val token = call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)?.trim()
        val binding = token?.takeIf { '?' !in call.request.uri }?.let(tokens::bound)
            ?: return@onCall call.refuse(HttpStatusCode.Unauthorized, "a live session token is required, in the Authorization header only")
        call.attributes.put(BOUND, Bound(binding, tokens.key(token)))
    }
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, why: String) =
    respondText("""{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"$why"}}""", ContentType.Application.Json, status)

private const val MCP = "/mcp"
private const val BEARER = "Bearer "
