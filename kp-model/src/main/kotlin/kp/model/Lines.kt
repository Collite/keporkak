package kp.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
