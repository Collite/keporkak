package kp.driver.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The contract's sample of the vendor's words (S3.13's triage, S3.14 T5), and how words heard in a run differ from its
 * shape: the handshake's section "The vendor's words against the contract's sample" (contracts.md §12, Amendment 38).
 * Here, and not in the handshake's own source set, so that a spec reaches it with words it gives (S3.15 T6).
 */
object VendorSample {
    /**
     * The pinned adapter's `_meta["_claude/rateLimit"]` as it came on the office, word for word from the transcript of the
     * granted handshake of 2026-11-24 (`handshakes/handshake-0.106.0-2026-11-24T193913Z.md`). The SDK's declared type put a
     * fraction at the top level; the words put each window's fraction under `unifiedWindows` — what the driver reads
     * ([SeatSession]), and what a later adapter is held to.
     */
    const val SAMPLE = """{"status":"allowed","resetsAt":1795549800,"rateLimitType":"five_hour","overageStatus":"allowed","overageResetsAt":1796083200,""" +
        """"isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.46,"resetsAt":1795549800,"status":"allowed"},""" +
        """"seven_day":{"utilization":0.23,"resetsAt":1795950000,"status":"allowed"}}}"""
    const val WINDOWS = "unifiedWindows"

    /**
     * The section, as the handshake's evidence holds it: what a run [heard] of the vendor's words — each as it came, once —
     * against the sample, then the sample itself.
     */
    fun section(heard: List<String>): String = buildString {
        appendLine("### The vendor's words against the contract's sample")
        appendLine()
        appendLine(when {
            heard.isEmpty() -> "Not heard in this run: the sample stands, unchecked."
            else -> heard.map { said -> runCatching { differences(Json.parseToJsonElement(said)) }.getOrElse { listOf("not JSON: ${it.message}") } }.distinct()
                .joinToString("\n") { differ -> if (differ.isEmpty()) "Heard in the sample's shape: each window's fraction, reset and status under `unifiedWindows`." else "Heard in another shape than the sample's — ${differ.joinToString("; ")}: what the driver reads of it may have moved." }
        })
        appendLine()
        appendLine("The sample — the pinned adapter's `_meta[\"_claude/rateLimit\"]` on the office, word for word from the handshake of 2026-11-24: `$SAMPLE`")
        appendLine()
    }

    /**
     * How [said] — the vendor's words heard beside a `usage_update` — differs from [SAMPLE]'s shape: each path to a value with
     * its kind, a window's name under `unifiedWindows` taken as any window; and each window heard that lacks what each of the
     * sample's windows has, by its name — a window whose `utilization` is gone while another's is there. Empty when it has
     * the sample's shape.
     */
    fun differences(said: JsonElement): List<String> {
        val heard = shape(said)
        val sample = shape(Json.parseToJsonElement(SAMPLE))
        val window = sample.filter { it.startsWith("$WINDOWS.*.") }.map { it.removePrefix("$WINDOWS.*.") }.toSet()
        val lacking = ((said as? JsonObject)?.get(WINDOWS) as? JsonObject).orEmpty().flatMap { (name, words) ->
            (window - shape(words)).filter { "$WINDOWS.*.$it" in heard }.map { "$WINDOWS.$name.$it" }
        }
        return ((sample - heard) + lacking).sorted().map { "no $it" } + (heard - sample).sorted().map { "a new $it" }
    }

    /** Each path to a value of [words], with the value's kind: `unifiedWindows.*.utilization number`. */
    private fun shape(words: JsonElement, at: String = ""): Set<String> = when (words) {
        is JsonObject -> words.entries.flatMap { (key, value) -> shape(value, if (at == WINDOWS) "$at.*" else if (at.isEmpty()) key else "$at.$key") }.toSet()
        is JsonArray -> setOf("$at array")
        is JsonNull -> setOf("$at null")
        is JsonPrimitive -> setOf("$at ${if (words.isString) "text" else if (words.content == "true" || words.content == "false") "boolean" else "number"}")
    }
}
