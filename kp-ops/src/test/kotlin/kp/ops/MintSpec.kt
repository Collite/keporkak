package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

/** The coder's App as Bora created it (`bora-github-apps.md` §6), reaching the three repositories, holding what §9 gives the coder. */
val KP_CODER = FakeApp("kp-coder", 5849303, 184046595, slug = "collite-kp-coder",
    holds = mapOf("contents" to "write", "pull_requests" to "write"), reaches = listOf("keporkak", "kp-toy", "project"))

/**
 * KP/P3/S3.2 — the daemon's token (T1's fourth sentence, `kp-ops`' half). The seam is `kp-ops`' mint in front of a local
 * HTTP server that answers as GitHub's documentation says ([FakeApps]): it checks each claim with the public half of a
 * key made for the spec and written as GitHub writes an App's — PKCS#1 — and grants only what the App holds. That no
 * token, key or claim reaches any output on the daemon's whole path is `kp-daemon`'s `TheDaemonReadsGitHubSpec`.
 */
class MintSpec : FreeSpec({
    "a mint asks GitHub for exactly the repositories and permissions it was given, with a claim the App's key signed for its id" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)

            val minted = GitHubApps(keys, github.api).mint(App("kp-coder", 5849303, 184046595), listOf("kp-toy"), mapOf("pull_requests" to "write", "contents" to "read"))

            minted.shouldBeInstanceOf<Minted.Ok>()
            github.requests.map { "${it.method} ${it.target}" } shouldBe listOf("POST /app/installations/184046595/access_tokens")
            Json.parseToJsonElement(github.requests.single().body) shouldBe
                Json.parseToJsonElement("""{"repositories": ["kp-toy"], "permissions": {"contents": "read", "pull_requests": "write"}}""")
            github.issued.map { "${it.app.identity} ${it.repositories} ${it.permissions}" } shouldBe listOf("kp-coder [kp-toy] {contents=read, pull_requests=write}")
        }
    }

    "a mint with a permission the App does not hold is failed, with GitHub's reason, and is not tried again" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)

            val minted = GitHubApps(keys, github.api).mint(App("kp-coder", 5849303, 184046595), listOf("kp-toy"), mapOf("contents" to "read", "checks" to "read"))

            minted shouldBe Minted.Failed("GitHub answered 422 to POST /app/installations/184046595/access_tokens: The permissions requested are not granted to this installation.")
            github.requests.size shouldBe 1
            github.issued.shouldBeEmpty()
        }
    }

    "the name GitHub shows for an App is read from GitHub — `collite-kp-coder` for `kp-coder` — never built from its identity" {
        val keys = tempdir().toPath()
        FakeApps.start(keys).use { github ->
            github.add(KP_CODER)

            GitHubApps(keys, github.api).name(App("kp-coder", 5849303, 184046595)) shouldBe Asked.Ok("collite-kp-coder")
            github.requests.map { "${it.method} ${it.target}" } shouldBe listOf("GET /app")
        }
    }
})
