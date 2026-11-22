package kp.ops

/**
 * What a performer answers (architecture.md §2, `kp-ops`): `ok`, with what the operation gives back — `ok(sha)` — or
 * `failed` with the reason, `failed(conflict)` among them (contracts.md §7). A performer retries nothing by itself.
 */
sealed interface Performed {
    data class Ok(val result: Map<String, String> = emptyMap()) : Performed
    data class Failed(val reason: String) : Performed
}
