package kp.driver.handshake

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.Collections

/**
 * A stand-in for the daemon on localhost: the messaging server — MCP over streamable HTTP, with no tools — and, for
 * `just handshake hooks`, the stop hook's `/hook/stop` (S2.2). MCP: it answers `initialize`, `tools/list` and
 * `ping`, and keeps — in memory only — each request's method and the Authorization header it came with, so the
 * handshake can see that the agent sent the session token. `/hook/stop`: it answers [stopAnswers] in turn — the last
 * one again once they run out — and keeps each call's Authorization header and body.
 */
internal class StandIn(private val stopAnswers: List<String> = listOf("""{"block": false}""")) : AutoCloseable {
    class Seen(val method: String, val authorization: String?)
    class Stop(val authorization: String?, val body: String, val answer: String)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    val stops: MutableList<Stop> = Collections.synchronizedList(mutableListOf())
    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp"
    /** The daemon's address for the hooks, `KP_HOOK_URL`: the stop hook posts to `<base>/hook/stop`. */
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    /** host:port, as `listen.mcp` is written in pool/offices.yaml. */
    val listen: String get() = "127.0.0.1:${server.address.port}"

    init {
        server.createContext("/mcp") { exchange -> exchange.use(::answer) }
        server.createContext("/hook/stop") { exchange -> exchange.use(::stop) }
        server.start()
    }

    private fun stop(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.sendResponseHeaders(405, -1)
        val answer = synchronized(stops) { stopAnswers[minOf(stops.size, stopAnswers.size - 1)] }
        stops += Stop(exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readAllBytes().decodeToString(), answer)
        val bytes = answer.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun answer(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.sendResponseHeaders(405, -1)
        val message = runCatching { Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject }.getOrNull()
            ?: return exchange.sendResponseHeaders(400, -1)
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: "(a response)"
        seen += Seen(method, exchange.requestHeaders.getFirst("Authorization"))
        val id = message["id"] ?: return exchange.sendResponseHeaders(202, -1)
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", (message["params"] as? JsonObject)?.get("protocolVersion") ?: JsonPrimitive("2025-06-18"))
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                    put("serverInfo", buildJsonObject { put("name", "kp-standin"); put("version", "0") })
                })
                "tools/list" -> put("result", buildJsonObject { put("tools", kotlinx.serialization.json.JsonArray(emptyList())) })
                "ping" -> put("result", JsonObject(emptyMap()))
                else -> put("error", buildJsonObject { put("code", -32601); put("message", "Method not found: $method") })
            }
        }.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, reply.size.toLong())
        exchange.responseBody.write(reply)
    }

    override fun close() = server.stop(0)
}

/** A listener that records every request it gets and answers none usefully: the poisoned HOME's MCP server. */
internal class Poison : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp"

    init {
        server.createContext("/") { exchange -> exchange.use { seen += "${it.requestMethod} ${it.requestURI}"; it.sendResponseHeaders(500, -1) } }
        server.start()
    }

    override fun close() = server.stop(0)
}
