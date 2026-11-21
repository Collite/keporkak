package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

/** The slices of T3, for the next developer: the claim, the key's shapes, a GitHub that does not answer or grants less. */
class GitHubAppsSpec : FreeSpec({
    val coder = App("kp-coder", 5849303, 184046595)

    "a claim is RS256, issued by the App's id, from a minute before now for nine minutes" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)
            val now = Instant.parse("2026-11-21T09:00:00Z")

            val claim = (GitHubApps(keys, github.api, clock = { now }).claim(coder) as Asked.Ok).value
            val (header, payload) = claim.split(".").take(2).map { Json.parseToJsonElement(Base64.getUrlDecoder().decode(it).decodeToString()).jsonObject }

            header.toString() shouldBe """{"alg":"RS256","typ":"JWT"}"""
            payload.toString() shouldBe """{"iat":${now.epochSecond - 60},"exp":${now.epochSecond + 540},"iss":"5849303"}"""
        }
    }

    "a key written PKCS#8 is read as well; a file that is no key, or none, fails the mint and quotes nothing of it" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)
            val pkcs8 = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
            val apps = GitHubApps(keys, github.api)

            Files.writeString(keys.resolve("kp-coder.pem"), "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder().encodeToString(pkcs8)}\n-----END PRIVATE KEY-----\n")
            apps.claim(coder).shouldBeInstanceOf<Asked.Ok<String>>()
            Files.writeString(keys.resolve("kp-coder.pem"), "-----BEGIN OPENSSH PRIVATE KEY-----\nc2VjcmV0LWtleS1ieXRlcw==\n-----END OPENSSH PRIVATE KEY-----\n")
            val notAKey = (apps.mint(coder, listOf("kp-toy"), mapOf("contents" to "read")) as Minted.Failed).reason
            notAKey shouldBe "the key of kp-coder at ${keys.resolve("kp-coder.pem")} is no PEM RSA private key (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY)"
            notAKey shouldNotContain "c2VjcmV0"
            Files.delete(keys.resolve("kp-coder.pem"))
            (apps.mint(coder, null, mapOf("contents" to "read")) as Minted.Failed).reason shouldBe "no key for kp-coder at ${keys.resolve("kp-coder.pem")}: NoSuchFileException"
            github.requests.size shouldBe 0
        }
    }

    "a GitHub that does not answer fails the mint and the name, each once" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github -> github.add(KP_CODER) }
        val nobody = URI.create("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/")
        val apps = GitHubApps(keys, nobody)

        (apps.mint(coder, listOf("kp-toy"), mapOf("contents" to "read")) as Minted.Failed).reason shouldStartWith
            "GitHub did not answer POST /app/installations/184046595/access_tokens: ConnectException"
        (apps.name(coder) as Asked.Failed).reason shouldStartWith "GitHub did not answer GET /app: ConnectException"
    }

    "a token GitHub grants with less than was asked is a failed mint, not a narrower token" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)
            github.server.removeContext("/app")
            github.server.createContext("/app") { exchange ->
                val body = """{"token":"ghs_narrower","expires_at":"2026-11-21T10:00:00Z","permissions":{"contents":"read"}}""".toByteArray()
                exchange.sendResponseHeaders(201, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            val minted = GitHubApps(keys, github.api).mint(coder, listOf("kp-toy"), mapOf("contents" to "write", "pull_requests" to "write"))

            minted shouldBe Minted.Failed("GitHub granted less than asked for kp-coder: contents write (granted read), pull_requests write (granted none)")
        }
    }

    "a token is shown by nothing that prints it: not its own text, not a mint's result" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)

            val minted = GitHubApps(keys, github.api).mint(coder, listOf("kp-toy"), mapOf("contents" to "read")) as Minted.Ok
            val token = github.issued.single().token

            minted.token.authorization() shouldBe "Bearer $token"
            "$minted ${minted.token}" shouldNotContain token
            "$minted" shouldContain "an installation token until"
        }
    }
})
