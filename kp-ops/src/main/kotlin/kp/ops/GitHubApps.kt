package kp.ops

import java.net.URI
import java.nio.file.Path
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
 * the repositories and permissions asked, never wider; the name GitHub shows for the App. [api] is GitHub's REST root.
 */
class GitHubApps(private val keys: Path, private val api: URI = GITHUB, private val clock: () -> Instant = Instant::now) {
    /** An installation token of [app] for [repositories] — null: every one its installation reaches — with exactly [permissions]. */
    fun mint(app: App, repositories: List<String>?, permissions: Map<String, String>): Minted = Minted.Failed("kp-ops mints no token yet")

    /** The name GitHub shows for [app], read from GitHub. */
    fun name(app: App): Asked<String> = Asked.Failed("kp-ops reads no App's name yet")

    companion object {
        /** GitHub's REST root. */
        val GITHUB: URI = URI.create("https://api.github.com/")
    }
}
