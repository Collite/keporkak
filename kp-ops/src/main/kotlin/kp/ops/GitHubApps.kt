package kp.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * An App of `pool/offices.yaml`'s block `github:` (contracts.md §9, Amendment 33): its [identity] — a name of the rules
 * and of its key's file, `<identity>.pem` — the App's [id] and its [installation]'s id in the organization.
 */
data class App(val identity: String, val id: Long, val installation: Long)

/**
 * An installation token, held in memory only (Amendment 33): its text goes into the one request that carries it, as
 * [authorization], and nowhere else — [toString] does not show it.
 */
class Token internal constructor(private val value: String, val expiresAt: String) {
    /** The `Authorization` header's value that carries it. */
    fun authorization(): String = "Bearer $value"

    override fun toString(): String = "an installation token until $expiresAt"
}

/** A mint's result: a token, or `failed` with the reason — GitHub's, when GitHub refused. */
sealed interface Minted {
    class Ok(val token: Token) : Minted {
        override fun toString(): String = "Ok($token)"
    }

    data class Failed(val reason: String) : Minted
}

/** What GitHub answered about an App: a value read from it, or `failed` with the reason. */
sealed interface Asked<out T> {
    data class Ok<T>(val value: T) : Asked<T>
    data class Failed(val reason: String) : Asked<Nothing>
}

/**
 * The GitHub Apps of the office (architecture.md §2, `kp-ops`; contracts.md §4.4, Amendment 33): an App's signed claim
 * from its key — `<identity>.pem` in [keys], the office's `<roots.secrets>/apps` — and its id; an installation token for
 * the repositories and permissions asked, never wider; the name GitHub shows for the App. A key, a claim and a token are
 * held in memory only: none is written to a file, a log, an error or a command's arguments. Every request is made once:
 * a failure is `failed` with GitHub's reason, and nothing is tried again. [api] is GitHub's REST root.
 */
class GitHubApps(
    private val keys: Path,
    private val api: URI = GITHUB,
    private val clock: () -> Instant = Instant::now,
    private val http: HttpClient = client(),
) {
    /**
     * An installation token of [app] for [repositories] — names in the organization; null: every repository its
     * installation reaches — with exactly [permissions] (`contents` → `read`…). A permission the App does not hold is a
     * failed mint (contracts.md §9), and so is a token GitHub grants with less than was asked.
     */
    fun mint(app: App, repositories: List<String>?, permissions: Map<String, String>): Minted {
        val claim = when (val signed = claim(app)) {
            is Asked.Ok -> signed.value
            is Asked.Failed -> return Minted.Failed(signed.reason)
        }
        val body = buildJsonObject {
            repositories?.let { names -> put("repositories", JsonArray(names.map(::JsonPrimitive))) }
            put("permissions", JsonObject(permissions.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }
        val path = "/app/installations/${app.installation}/access_tokens"
        val answer = when (val sent = send("POST", path, "Bearer $claim", body.toString())) {
            is Asked.Ok -> sent.value
            is Asked.Failed -> return Minted.Failed(sent.reason)
        }
        if (answer.status != CREATED) return Minted.Failed(refused("POST", path, answer))
        val json = answer.json() as? JsonObject ?: return Minted.Failed("GitHub's answer to POST $path is no JSON object")
        val token = json.text("token") ?: return Minted.Failed("GitHub's answer to POST $path holds no token")
        val granted = (json["permissions"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
        val short = permissions.filter { (name, level) -> !covers(granted[name], level) }
        if (short.isNotEmpty()) {
            return Minted.Failed("GitHub granted less than asked for ${app.identity}: " + short.entries.joinToString(", ") { (name, level) -> "$name $level (granted ${granted[name] ?: "none"})" })
        }
        return Minted.Ok(Token(token, json.text("expires_at") ?: "an unknown time"))
    }

    /** The name GitHub shows for [app] — its slug, read from GitHub (`GET /app`), never built from its identity. */
    fun name(app: App): Asked<String> {
        val claim = when (val signed = claim(app)) {
            is Asked.Ok -> signed.value
            is Asked.Failed -> return signed
        }
        val answer = when (val sent = send("GET", "/app", "Bearer $claim", null)) {
            is Asked.Ok -> sent.value
            is Asked.Failed -> return sent
        }
        if (answer.status != OK) return Asked.Failed(refused("GET", "/app", answer))
        val slug = (answer.json() as? JsonObject)?.text("slug") ?: return Asked.Failed("GitHub's answer to GET /app names no slug")
        return Asked.Ok(slug)
    }

    /**
     * [app]'s signed claim (a JSON Web Token, RS256): issued by its id, from a minute ago — GitHub's clock may lag — for
     * nine minutes, inside GitHub's ten. Its key is read for this claim and kept nowhere.
     */
    internal fun claim(app: App): Asked<String> {
        val file = keys.resolve("${app.identity}.pem")
        val key = try {
            Keys.privateKey(Files.readString(file))
        } catch (error: IOException) {
            return Asked.Failed("no key for ${app.identity} at $file: ${error.javaClass.simpleName}")
        } catch (error: Keys.NotAKey) {
            return Asked.Failed("the key of ${app.identity} at $file ${error.message}")
        }
        val now = clock().epochSecond
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = """{"iat":${now - SKEW},"exp":${now + LIFE},"iss":"${app.id}"}"""
        return Asked.Ok(Keys.signed(header, payload, key))
    }

    /**
     * The repositories [token]'s installation reaches, `<owner>/<name>`: every page of `GET /installation/repositories`,
     * a next page followed on GitHub's own root only.
     */
    fun repositories(token: Token): Asked<List<String>> {
        val path = "/installation/repositories"
        val names = ArrayList<String>()
        var next: URI? = api.resolve(path.removePrefix("/") + "?per_page=100")
        while (next != null) {
            val answer = when (val sent = send("GET", path, token.authorization(), null, next)) {
                is Asked.Ok -> sent.value
                is Asked.Failed -> return sent
            }
            if (answer.status != OK) return Asked.Failed(refused("GET", path, answer))
            val listed = ((answer.json() as? JsonObject)?.get("repositories") as? JsonArray) ?: return Asked.Failed("GitHub's answer to GET $path lists no repositories")
            names += listed.map { (it as? JsonObject)?.text("full_name") ?: return Asked.Failed("a repository in GitHub's answer to GET $path has no full_name") }
            next = answer.next?.let(URI::create)?.also { if (!it.toString().startsWith(api.toString())) return Asked.Failed("GitHub's next page of GET $path is not under $api") }
        }
        return Asked.Ok(names)
    }

    /** One request with [authorization], made once; `failed` when GitHub could not be reached. */
    private fun send(method: String, path: String, authorization: String, body: String?, url: URI = api.resolve(path.removePrefix("/"))): Asked<Answer> {
        val request = HttpRequest.newBuilder(url)
            .timeout(TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", authorization)
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .apply { if (body != null) header("Content-Type", "application/json") }
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            Asked.Ok(Answer(response.statusCode(), response.body(), response.headers().firstValue("link").orElse(null)?.let { LINK_NEXT.find(it)?.groupValues?.get(1) }))
        } catch (error: IOException) {
            Asked.Failed("GitHub did not answer $method $path: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Asked.Failed("interrupted while asking GitHub $method $path")
        }
    }

    private class Answer(val status: Int, val body: String, val next: String? = null) {
        fun json(): JsonElement? = try {
            Json.parseToJsonElement(body)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** GitHub's reason for refusing: the status and its `message`. */
    private fun refused(method: String, path: String, answer: Answer): String {
        val message = ((answer.json() as? JsonObject)?.text("message"))
        return "GitHub answered ${answer.status} to $method $path" + (message?.let { ": $it" } ?: "")
    }

    companion object {
        /** GitHub's REST root. */
        val GITHUB: URI = URI.create("https://api.github.com/")
        /** What the daemon reads GitHub with: its token's permissions, each to read (contracts.md §4.4, Amendment 33). */
        val READING: Map<String, String> = mapOf("contents" to "read", "pull_requests" to "read", "checks" to "read")
        /** The least a token can be — the repositories' metadata, to read: what `just apps` lists an installation's repositories with (§12). */
        val METADATA: Map<String, String> = mapOf("metadata" to "read")
        /** The REST API's version every request names: GitHub's default, which it still serves. */
        const val API_VERSION = "2022-11-28"
        private val LINK_NEXT = Regex("<([^>]+)>;\\s*rel=\"next\"")
        private const val OK = 200
        private const val CREATED = 201
        private const val SKEW = 60L
        private const val LIFE = 540L
        /** How long one request may take: a judgement waits for GitHub inside the mailroom's lock (Amendment 33). */
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        fun client(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

        /** Whether a granted level covers an asked one: `write` covers `read`. */
        private fun covers(granted: String?, asked: String): Boolean = granted == asked || (granted == "write" && asked == "read") || granted == "admin"

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}
