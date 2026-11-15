package kp.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

/** A line of the log (contracts.md §2): a message when it has `kind`, a record when it has `event`. */
fun readEnvelope(value: JsonObject): Envelope = when {
    "kind" in value -> Message.read(value)
    "event" in value -> Record.read(value)
    else -> throw IllegalArgumentException("a line is a message (it has kind) or a record (it has event)")
}

/** One line's text, without its line end. */
fun parseLine(text: String): Envelope =
    readEnvelope(Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("a line is one JSON object"))

/** The line's text: compact JSON on one line, without its line end. */
fun Envelope.toLine(): String = Json.encodeToString(JsonObject.serializer(), json)

/**
 * The line as standard-library values — text, [BigDecimal], [Boolean], null, [List] and [Map] — for a reader with no
 * JSON library (the engine, architecture.md §2). A field that is absent is not in the map; one that is null maps to null.
 */
@Suppress("UNCHECKED_CAST")
fun Envelope.plain(): Map<String, Any?> = plainValue(json) as Map<String, Any?>

/** One JSON text (RFC 8259) as standard-library values, as [plain] gives them. */
fun plain(text: String): Any? = plainValue(Json.parseToJsonElement(text))

internal fun plainValue(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.mapValues { plainValue(it.value) }
    is JsonArray -> value.map(::plainValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        else -> BigDecimal(value.content)
    }
}
