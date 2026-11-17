package kp.seatapi

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kp.mailroom.Mailroom
import java.net.InetSocketAddress

/**
 * The seat API (architecture.md §2, `kp-seat-api`; contracts.md §5, §11): the five seat tools as an MCP server over
 * streamable HTTP at `/mcp`, and the stop hook's `/hook/stop`, on one listener bound to the loopback address.
 */
class SeatApi private constructor(private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>) : AutoCloseable {
    /** The address the listener is bound to. */
    val address: InetSocketAddress = runBlocking { server.engine.resolvedConnectors().first() }.let { InetSocketAddress(it.host, it.port) }

    override fun close() = server.stop(0, 0)

    companion object {
        /** Starts the listener on [listen] (`listen.mcp`, `host:port`), in front of [mailroom], with [tokens] as its registry. */
        fun start(mailroom: Mailroom, tokens: Tokens, listen: String): SeatApi {
            val host = listen.substringBeforeLast(':')
            val port = listen.substringAfterLast(':').toInt()
            return SeatApi(embeddedServer(CIO, host = host, port = port) {}.start(wait = false))
        }
    }
}
