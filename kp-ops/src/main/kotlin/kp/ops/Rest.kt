package kp.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A stage's pull request as GitHub lists it: its [number], its [state] — `open` or `closed` — whether it is [merged], its
 * [head]'s sha and when it was [created]. [name] is how a result names it: `Collite/kp-toy#3`.
 */
internal class PullRequest(val repository: String, val number: Int, val state: String, val merged: Boolean, val head: String, val created: String) {
    val name: String get() = "$repository#$number"
}

/** What looking for the stage's pull request found: it, none, or why it could not be told. */
internal sealed interface Looked {
    class Found(val pull: PullRequest) : Looked
    class None(val why: String) : Looked
    class Failed(val reason: String) : Looked
}

/**
 * GitHub's REST API as the performers ask it (Amendments 33 and 37): each request made once, with the token handed in for
 * it, which goes into that request's `Authorization` header and nowhere else; a list read to its last page, a next page
 * followed on [api] only. Nothing is kept from one request for the next.
 */
internal class Rest(private val api: URI, private val http: HttpClient) {
    /** GitHub's answer: its status, its body, and the `Link` header's next page. */
    class Reply(val status: Int, val text: String, val next: String?) {
        val json: JsonElement? by lazy {
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** GitHub's `message`, when the body holds one. */
        val message: String? get() = (json as? JsonObject)?.text("message")
    }

    /** A request's end: GitHub's reply, or why GitHub was not reached. */
    sealed interface Sent {
        class Answered(val reply: Reply) : Sent
        class NotReached(val reason: String) : Sent
    }

    /** [method] [path] with [token] and [body], once. */
    fun send(method: String, path: String, token: Token, body: JsonObject? = null, url: URI = url(path)): Sent {
        val request = HttpRequest.newBuilder(url)
            .timeout(GitHubApps.TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GitHubApps.API_VERSION)
            .header("Authorization", token.authorization())
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body.toString()))
            .apply { if (body != null) header("Content-Type", "application/json") }
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            Sent.Answered(Reply(response.statusCode(), response.body(), response.headers().firstValue("link").orElse(null)?.let { LINK_NEXT.find(it)?.groupValues?.get(1) }))
        } catch (error: IOException) {
            Sent.NotReached("GitHub did not answer $method $path: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Sent.NotReached("interrupted while asking GitHub $method $path")
        }
    }

    /**
     * GitHub's reason for an answer that is not the one asked for: the status, and its `message` — its lines, which GitHub
     * separates by blank ones, joined by ` · ` (a rule's refusal lists each rule on a line of its own).
     */
    fun refused(method: String, path: String, reply: Reply): String =
        "GitHub answered ${reply.status} to $method $path" + (reply.message?.let { message -> ": " + message.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" · ") } ?: "")

    /**
     * The stage's pull request (Amendment 33): of those in [where]'s repository whose head is its branch, every page of
     * them, the open one, else the one made last. Two open are no answer.
     */
    fun stagePull(where: StageOnGitHub, token: Token): Looked {
        val owner = where.repository.substringBefore('/')
        val path = "/repos/${where.repository}/pulls"
        val all = ArrayList<PullRequest>()
        var next: URI? = url(path, mapOf("head" to "$owner:${where.branch}", "state" to "all", "per_page" to PER_PAGE))
        while (next != null) {
            val reply = when (val sent = send("GET", path, token, url = next)) {
                is Sent.NotReached -> return Looked.Failed(sent.reason)
                is Sent.Answered -> sent.reply
            }
            if (reply.status != OK) return Looked.Failed(refused("GET", path, reply))
            val listed = reply.json as? JsonArray ?: return Looked.Failed("GitHub's answer to GET $path is no list")
            all += listed.map { item -> pull(where.repository, item) ?: return Looked.Failed("a pull request in GitHub's answer to GET $path lacks a number, state, merged_at, head.sha or created_at") }
            next = reply.next?.let(URI::create)?.also { if (!it.toString().startsWith(api.toString())) return Looked.Failed("GitHub's next page of GET $path is not under $api") }
        }
        val open = all.filter { it.state == OPEN }
        return when {
            open.size > 1 -> Looked.Failed("${where.repository} holds ${open.size} open pull requests whose head is ${where.branch}: ${open.joinToString(", ") { "#${it.number}" }}")
            open.size == 1 -> Looked.Found(open.single())
            all.isEmpty() -> Looked.None("${where.repository} holds no pull request whose head is ${where.branch}")
            else -> Looked.Found(all.maxWith(compareBy<PullRequest> { it.created }.thenBy { it.number }))
        }
    }

    fun url(path: String, query: Map<String, String> = emptyMap()): URI {
        val encoded = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return URI.create(api.toString().removeSuffix("/") + path + if (encoded.isEmpty()) "" else "?$encoded")
    }

    companion object {
        const val OK = 200
        const val CREATED = 201
        const val OPEN = "open"
        private const val PER_PAGE = "100"
        private val LINK_NEXT = Regex("<([^>]+)>;\\s*rel=\"next\"")
        private val SHA = Regex("[0-9a-fA-F]{40}")

        private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A pull request of GitHub's list, or null when it lacks a field Amendment 33 reads. */
        private fun pull(repository: String, item: JsonElement): PullRequest? {
            val json = item as? JsonObject ?: return null
            val merged = json["merged_at"] ?: return null
            return PullRequest(
                repository = repository,
                number = (json["number"] as? JsonPrimitive)?.intOrNull ?: return null,
                state = json.text("state") ?: return null,
                merged = merged !is JsonNull,
                head = (json["head"] as? JsonObject)?.text("sha")?.takeIf { SHA.matches(it) } ?: return null,
                created = json.text("created_at") ?: return null,
            )
        }
    }
}
