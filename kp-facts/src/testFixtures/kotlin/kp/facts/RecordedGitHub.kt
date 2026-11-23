package kp.facts

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the local GitHub answers on a route: a status, a JSON body, and headers beside. */
data class Answer(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** One request the local GitHub received: its method, its path and query as sent, its headers, its body. */
data class RecordedRequest(val method: String, val target: String, val headers: Map<String, List<String>>, val body: String) {
    val authorization: String? get() = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value?.singleOrNull()
}

/**
 * GitHub's REST API for the specs (contracts.md §4.4, Amendment 33), on a local HTTP server: the seam of `kp-facts`' entry
 * for GitHub. Each route answers what a spec put there — a response recorded from `Collite/kp-toy`'s merged pull request
 * #1 on Panda (S3.2; [recorded]), as it was or changed in the fields GitHub documents — and every request is kept. A
 * route given several pages answers each with GitHub's `Link` header to the next. Routes match a path and the query
 * parameters a spec names; the last route put wins. [server] may serve other routes beside — GitHub's App endpoints.
 * A write — `PUT`, `PATCH`, `POST`, `DELETE` — answers what [onRequests] put there, one answer per request in turn
 * (S3.9: the operations on GitHub, recorded on Panda from `Collite/kp-toy`), or is held by [hold]. A route of [onRequest]
 * answers what its function makes of each request: GitHub as it acts on what it holds, each answer a recorded one.
 */
class RecordedGitHub(val server: HttpServer = local()) : AutoCloseable {
    private class Route(
        val path: String,
        val query: Map<String, String>,
        val pages: List<Answer>,
        val method: String = "GET",
        val held: Held? = null,
        val answering: ((RecordedRequest) -> Answer)? = null,
    ) {
        val asked = AtomicInteger()
    }

    /** A request [hold] keeps unanswered: [received] once it arrived; it is answered [answer] when [release]d, or never. */
    class Held(private val answer: Answer) {
        val received = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun release() = released.countDown()

        internal fun await(): Answer {
            received.countDown()
            released.await(HOLD.inWholeSeconds, TimeUnit.SECONDS)
            return answer
        }
    }

    private val routes = CopyOnWriteArrayList<Route>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val api: URI get() = URI.create("http://127.0.0.1:${server.address.port}/")

    init {
        server.createContext("/repos") { exchange -> answer(exchange) }
    }

    fun start(): RecordedGitHub = also { server.start() }

    /** GET [path] with at least the parameters of [query] answers [answer]. */
    fun on(path: String, vararg query: Pair<String, String>, answer: Answer) {
        routes += Route(path, query.toMap(), listOf(answer))
    }

    /** Every GET answers [answer], whatever its path — a refusal, a limit. */
    fun onEvery(answer: Answer) {
        routes += Route(EVERY, emptyMap(), listOf(answer))
    }

    /** [method] [path] answers each of [answers] in turn, one per request; past the last, the last again. */
    fun onRequests(method: String, path: String, vararg answers: Answer) {
        routes += Route(path, emptyMap(), answers.toList(), method)
    }

    /** [method] [path] — any query — answers what [answer] makes of each request, on the server's thread. */
    fun onRequest(method: String, path: String, answer: (RecordedRequest) -> Answer) {
        routes += Route(path, emptyMap(), emptyList(), method, answering = answer)
    }

    /** [method] [path] is held: kept, unanswered, until the spec releases it — then answered [answer]. */
    fun hold(method: String, path: String, answer: Answer = Answer(504, "")): Held = Held(answer).also { routes += Route(path, emptyMap(), emptyList(), method, it) }

    /** GET [path] with at least [query] answers each body of [pages] in turn, by the parameter `page`, linking each to the next. */
    fun onPages(path: String, vararg pages: String, query: Map<String, String> = emptyMap()) {
        routes += Route(path, query, pages.map { Answer(200, it) })
    }

    private fun answer(exchange: HttpExchange) {
        val request = RecordedRequest(exchange.requestMethod, exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
            exchange.requestHeaders.toMap(), exchange.requestBody.readAllBytes().decodeToString())
        requests += request
        val query = exchange.requestURI.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
            .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        val route = routes.lastOrNull { route ->
            exchange.requestMethod == route.method && (route.path == EVERY || route.path == exchange.requestURI.path) && route.query.all { query[it.key] == it.value }
        }
        val page = query["page"]?.toIntOrNull() ?: 1
        val answer = when {
            route == null -> Answer(404, NOT_FOUND)
            route.held != null -> route.held.await()
            route.answering != null -> route.answering.invoke(request)
            route.method != "GET" -> route.pages[minOf(route.asked.getAndIncrement(), route.pages.size - 1)]
            else -> route.pages.getOrNull(page - 1) ?: Answer(404, NOT_FOUND)
        }
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        answer.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        if (route != null && route.method == "GET" && page < route.pages.size) {
            val next = (query + ("page" to "${page + 1}")).entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, Charsets.UTF_8)}" }
            exchange.responseHeaders.add("Link", "<${api.toString().removeSuffix("/")}${exchange.requestURI.path}?$next>; rel=\"next\"")
        }
        val bytes = answer.body.toByteArray()
        exchange.sendResponseHeaders(answer.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        private const val EVERY = "*"

        /** GitHub's 404, as recorded. */
        val NOT_FOUND = recorded("repo-404.json")

        /** How long a held request waits for its release at most. */
        private val HOLD = kotlin.time.Duration.parse("2m")

        /**
         * A local server on a loopback port the system picks, not started; each request on a thread of its own, so that a held
         * one keeps no other waiting.
         */
        fun local(): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool { Thread(it, "recorded-github").apply { isDaemon = true } }
        }

        /** A response recorded from `Collite/kp-toy` on Panda, by its file name under `github/kp-toy/`, as it was recorded. */
        fun recorded(name: String): String =
            RecordedGitHub::class.java.getResource("/github/kp-toy/$name")?.readText() ?: error("no recording github/kp-toy/$name")

        /**
         * The object at [at] — a dotted path of fields and list indexes, `0` or `check_runs.0` — in the recording [name],
         * with each of [changes] set: a field by its dotted path (`user.login`) to a text, a number, a boolean, null or JSON.
         */
        fun recordedItem(name: String, at: String, vararg changes: Pair<String, Any?>): JsonObject {
            val item = at.split(".").filter { it.isNotEmpty() }.fold(Json.parseToJsonElement(recorded(name))) { json, step ->
                step.toIntOrNull()?.let { json.jsonArray[it] } ?: json.jsonObject.getValue(step)
            }.jsonObject
            return changes.fold(item) { changed, (path, value) -> changed.set(path.split("."), json(value)) }
        }

        /** [items] as the JSON list GitHub answers. */
        fun jsonList(vararg items: JsonObject): String = JsonArray(items.toList()).toString()

        /** [runs] as GitHub answers a sha's check runs. */
        fun checkRuns(vararg runs: JsonObject): String = JsonObject(mapOf("total_count" to JsonPrimitive(runs.size), "check_runs" to JsonArray(runs.toList()))).toString()

        private fun JsonObject.set(path: List<String>, value: JsonElement): JsonObject =
            JsonObject(this + (path.first() to if (path.size == 1) value else (this[path.first()] as? JsonObject ?: JsonObject(emptyMap())).set(path.drop(1), value)))

        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("no JSON for $value")
        }
    }
}
