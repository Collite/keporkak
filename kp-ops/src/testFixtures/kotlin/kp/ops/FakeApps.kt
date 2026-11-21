package kp.ops

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories

/**
 * An App as the fake GitHub holds it: its identity, id and installation, the name GitHub shows for it ([slug]), the
 * permissions it [holds], and the repositories its installation [reaches] — names in the organization `Collite`.
 */
data class FakeApp(
    val identity: String,
    val id: Long,
    val installation: Long,
    val slug: String,
    val holds: Map<String, String>,
    val reaches: List<String>,
)

/** One request the fake GitHub received: its method, its path and query, its headers, its body. */
data class AppsRequest(val method: String, val target: String, val headers: Map<String, List<String>>, val body: String) {
    val authorization: String? get() = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value?.singleOrNull()
}

/** A token the fake GitHub minted: for which App, which repositories (null: every one it reaches), which permissions. */
data class Issued(val app: FakeApp, val token: String, val repositories: List<String>?, val permissions: Map<String, String>)

/**
 * GitHub's App endpoints for the specs (contracts.md §4.4, Amendment 33), on a local HTTP server: the specs' seam is
 * `kp-ops`' mint in front of it. Each App added gets a key made for the spec — RSA, written as GitHub writes an App's
 * key, PKCS#1 PEM, to `<keys>/<identity>.pem`, mode 0600 — whose public half checks every claim. It answers, as GitHub's
 * documentation says: `POST /app/installations/<id>/access_tokens` — a token for exactly what was asked, or 422 with
 * GitHub's message when the App does not hold a permission or reach a repository; `GET /app` — the App's slug;
 * `GET /installation/repositories` — what a token reaches. A claim that is not the App's, or out of date, is 401.
 * [server] may serve other routes beside: it is shared with the recorded responses of `kp-facts`.
 */
class FakeApps(val server: HttpServer, private val keys: Path, private val clock: () -> Instant = Instant::now) : AutoCloseable {
    private val apps = CopyOnWriteArrayList<FakeApp>()
    private val publicKeys = java.util.concurrent.ConcurrentHashMap<String, PublicKey>()
    private val pems = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Every request, in order. */
    val requests = CopyOnWriteArrayList<AppsRequest>()
    /** Every token minted, in order. */
    val issued = CopyOnWriteArrayList<Issued>()
    /** Every claim received, in order. */
    val claims = CopyOnWriteArrayList<String>()
    val api: URI get() = URI.create("http://127.0.0.1:${server.address.port}/")

    init {
        keys.createDirectories()
        server.createContext("/app") { exchange -> answer(exchange) }
        server.createContext("/installation") { exchange -> answer(exchange) }
    }

    /** Adds [app], with a key made for it in the keys folder. */
    fun add(app: FakeApp): FakeApp {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val pem = pkcs1Pem(pair.private as RSAPrivateCrtKey)
        val file = keys.resolve("${app.identity}.pem")
        Files.writeString(file, pem)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        publicKeys[app.identity] = pair.public
        pems[app.identity] = pem
        apps += app
        return app
    }

    override fun close() = server.stop(0)

    /** From now on [identity]'s App does not hold [permission]: a mint that asks for it is refused. */
    fun withdraw(identity: String, permission: String) {
        val app = apps.first { it.identity == identity }
        apps[apps.indexOf(app)] = app.copy(holds = app.holds - permission)
    }

    /** The PEM text of [identity]'s key, as written — what a spec searches every output for. */
    fun pem(identity: String): String = pems.getValue(identity)

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.readAllBytes().decodeToString()
        val request = AppsRequest(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestHeaders.toMap(), body)
        requests += request
        val path = exchange.requestURI.path
        val (status, json) = when {
            exchange.requestMethod == "POST" && MINT.matches(path) -> mint(MINT.matchEntire(path)!!.groupValues[1].toLong(), request)
            exchange.requestMethod == "GET" && path == "/app" -> app(request)
            exchange.requestMethod == "GET" && path == "/installation/repositories" -> repositories(request)
            else -> 404 to message("Not Found")
        }
        val bytes = json.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun mint(installation: Long, request: AppsRequest): Pair<Int, JsonObject> {
        val app = apps.firstOrNull { it.installation == installation } ?: return 404 to message("Not Found")
        if (claimant(request) != app) return 401 to message("A JSON web token could not be decoded")
        val asked = Json.parseToJsonElement(request.body.ifEmpty { "{}" }).jsonObject
        val permissions = asked["permissions"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: app.holds
        val repositories = asked["repositories"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (permissions.any { (name, level) -> !covers(app.holds[name], level) }) return 422 to message("The permissions requested are not granted to this installation.")
        if (repositories != null && !app.reaches.containsAll(repositories)) {
            return 422 to message("There is at least one repository that does not exist or is not accessible to the parent installation.")
        }
        val token = "ghs_" + (1..36).map { ALPHABET.random() }.joinToString("")
        issued += Issued(app, token, repositories, permissions)
        return 201 to buildJsonObject {
            put("token", token)
            put("expires_at", clock().plusSeconds(3600).toString())
            put("permissions", JsonObject(permissions.mapValues { JsonPrimitive(it.value) }))
            put("repository_selection", if (repositories == null) "all" else "selected")
        }
    }

    private fun app(request: AppsRequest): Pair<Int, JsonObject> {
        val app = claimant(request) ?: return 401 to message("A JSON web token could not be decoded")
        return 200 to buildJsonObject {
            put("id", app.id)
            put("slug", app.slug)
            put("name", app.slug)
        }
    }

    private fun repositories(request: AppsRequest): Pair<Int, JsonObject> {
        val token = issued.lastOrNull { "Bearer ${it.token}" == request.authorization } ?: return 401 to message("Bad credentials")
        val reached = token.repositories ?: token.app.reaches
        return 200 to buildJsonObject {
            put("total_count", reached.size)
            put("repositories", JsonArray(reached.map { name -> buildJsonObject { put("name", name); put("full_name", "Collite/$name") } }))
        }
    }

    /** The App whose claim [request] carries — signed by its key, issued by its id, current, for ten minutes at most. */
    private fun claimant(request: AppsRequest): FakeApp? {
        val claim = request.authorization?.removePrefix("Bearer ") ?: return null
        claims += claim
        val parts = claim.split(".")
        if (parts.size != 3) return null
        val payload = Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()).jsonObject
        val app = apps.firstOrNull { it.id.toString() == payload["iss"]?.jsonPrimitive?.content } ?: return null
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKeys.getValue(app.identity))
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(Base64.getUrlDecoder().decode(parts[2]))
        }
        val iat = payload["iat"]?.jsonPrimitive?.long ?: return null
        val exp = payload["exp"]?.jsonPrimitive?.long ?: return null
        val now = clock().epochSecond
        return app.takeIf { verified && iat <= now + 60 && exp > now && exp - iat <= 600 }
    }

    companion object {
        private val MINT = Regex("/app/installations/([0-9]+)/access_tokens")
        private val ALPHABET = ('A'..'Z') + ('a'..'z') + ('0'..'9')

        /** A fake GitHub of its own, on a loopback port the system picks. */
        fun start(keys: Path, clock: () -> Instant = Instant::now): FakeApps = FakeApps(local(), keys, clock).also { it.server.start() }

        /** A local server on a loopback port the system picks, not started. */
        fun local(): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        private fun covers(held: String?, asked: String): Boolean = held == asked || (held == "write" && asked == "read")

        private fun message(text: String) = buildJsonObject {
            put("message", text)
            put("documentation_url", "https://docs.github.com/rest")
        }

        /** [key] as GitHub writes an App's key: PKCS#1 `RSAPrivateKey` in PEM (RFC 8017 A.1.2), lines of 64. */
        fun pkcs1Pem(key: RSAPrivateCrtKey): String {
            val fields = listOf(java.math.BigInteger.ZERO, key.modulus, key.publicExponent, key.privateExponent, key.primeP, key.primeQ,
                key.primeExponentP, key.primeExponentQ, key.crtCoefficient)
            val der = der(0x30, fields.fold(ByteArray(0)) { bytes, field -> bytes + der(0x02, field.toByteArray()) })
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN RSA PRIVATE KEY-----\n$body\n-----END RSA PRIVATE KEY-----\n"
        }

        private fun der(tag: Int, content: ByteArray): ByteArray {
            val n = content.size
            val length = if (n < 0x80) byteArrayOf(n.toByte())
            else generateSequence(n) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xff).toByte() }.toList().reversed().toByteArray()
                .let { byteArrayOf((0x80 or it.size).toByte()) + it }
            return byteArrayOf(tag.toByte()) + length + content
        }
    }
}
