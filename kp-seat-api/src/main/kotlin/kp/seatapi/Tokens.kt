package kp.seatapi

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What was bound with a session token when its session opened (contracts.md §5): the seat, its role, office, stage, model and effort. */
data class Binding(val seat: String, val role: String, val office: String, val stage: String?, val model: String, val effort: String)

/**
 * The registry of live session tokens (contracts.md §5): a token maps to exactly one seat and what was bound with it,
 * and dies with its session — the caller revokes it then. A token is kept only as its SHA-256, so the registry holds
 * no token that could be printed.
 */
class Tokens {
    private val live = ConcurrentHashMap<String, Binding>()

    /** [token] is live from now on, for [binding]'s seat. */
    fun register(token: String, binding: Binding) {
        require(token.isNotBlank()) { "a session token is not blank" }
        live[digest(token)] = binding
    }

    /** [token] is dead from now on: its session died, or a fresh one replaced it. */
    fun revoke(token: String) {
        live.remove(digest(token))
    }

    /** What [token] was bound with while it is live; null for an unknown or dead token. */
    fun bound(token: String): Binding? = live[digest(token)]

    /** A key that names a token without being one: what the API counts a session's stop-gate blocks under. */
    internal fun key(token: String): String = digest(token)
}

private fun digest(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
