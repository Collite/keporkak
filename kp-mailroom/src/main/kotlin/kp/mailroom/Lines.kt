package kp.mailroom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kp.engine.Datum

/** A datum as a JSON value — what a line carries of a decision. */
internal fun Datum.element(): JsonElement = Json.parseToJsonElement(json())

internal fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

internal fun texts(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

/**
 * A line without `seq`, which the log assigns: `v`, `at`, then [fields] in order. A field whose value is null is left
 * out, but for the keys in [keepNull] — a `deadline` that is over is written as null (contracts.md §2.2).
 */
internal fun line(at: String, vararg fields: Pair<String, JsonElement?>, keepNull: Set<String> = emptySet()): JsonObject = buildJsonObject {
    put("v", JsonPrimitive(1))
    put("at", JsonPrimitive(at))
    fields.forEach { (key, value) ->
        when {
            value != null && value != JsonNull -> put(key, value)
            key in keepNull -> put(key, JsonNull)
        }
    }
}

/** Counters as a record carries them: only when the judgement moved one. */
internal fun counted(values: Map<String, Long>): JsonElement? =
    values.takeIf { it.isNotEmpty() }?.let { JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) }) }

internal fun fields(values: Map<String, Datum>): JsonObject = JsonObject(values.mapValues { it.value.element() })
