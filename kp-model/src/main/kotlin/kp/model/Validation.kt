package kp.model

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate

internal typealias Check = (JsonElement) -> Boolean
internal val text: Check = { it is JsonPrimitive && it.isString }
internal fun pattern(regex: String): Check = { text(it) && Regex(regex).containsMatchIn(it.jsonPrimitive.content) }
internal fun integer(min: Int? = null): Check = { value ->
    value is JsonPrimitive && !value.isString && runCatching {
        val number = value.content.toBigDecimal().toBigIntegerExact()
        min == null || number >= min.toBigInteger()
    }.getOrDefault(false)
}
internal fun nullable(check: Check): Check = { it == JsonNull || check(it) }
internal fun array(check: Check, unique: Boolean = false): Check = {
    it is JsonArray && it.all(check) && (!unique || it.distinct().size == it.size)
}
internal fun dictionary(check: Check): Check = { it is JsonObject && it.values.all(check) }
internal fun choice(vararg values: String): Check = { text(it) && it.jsonPrimitive.content in values }
internal val objectValue: Check = { it is JsonObject }
internal val pointer: Check = { text(it) && it.jsonPrimitive.content.let { s -> s.codePointCount(0, s.length) >= 3 } }
internal val messageId = pattern("^m-[a-z0-9]+-[0-9]{6,}$")
internal val party = pattern("^(bora|daemon|mailroom|ledger|board|[a-z]+(-[a-z0-9]+)*)$")
internal val stageId = pattern("^[A-Z][A-Z0-9-]*/P[0-9]+/S[0-9]+\\.[0-9]+$")
internal val timestamp: Check = { value ->
    if (!text(value)) false else {
        val match = Regex("^(\\d{4})-(0[1-9]|1[0-2])-(\\d{2})[Tt](?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?(?:[Zz]|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)$").find(value.jsonPrimitive.content)
        match != null && runCatching {
            val (year, month, day) = match.destructured
            require(year.toInt() > 0)
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        }.isSuccess
    }
}
private val common = mapOf<String, Check>(
    "v" to { integer()(it) && it.jsonPrimitive.content.toBigDecimal().compareTo(BigDecimal.ONE) == 0 },
    "seq" to integer(1), "at" to timestamp
)
private val messageFields = common + mapOf(
    "id" to messageId, "thread" to messageId, "re" to nullable(messageId),
    "from" to party, "to" to party, "cc" to array(party, unique = true),
    "kind" to pattern("^[a-z][a-z-]*$"), "stage" to nullable(stageId),
    "subject" to { value: JsonElement -> text(value) && value.jsonPrimitive.content.let { it.codePointCount(0, it.length) in 1..120 } },
    "body" to text,
    "pointers" to array(pointer),
    "args" to objectValue, "deadline" to nullable(timestamp), "created" to timestamp,
    "by" to pattern("^(seat:[a-z][a-z0-9-]*|robot:[A-Z]+-[0-9]+|bora-cli|effect:T-[0-9]+|hand:[a-z][a-z0-9-]*)$")
)
internal fun validateMessage(value: JsonObject) = validateObject(
    value, setOf("v", "seq", "at", "id", "thread", "from", "to", "kind", "subject", "created", "by"), messageFields, false
)
private data class Shape(val required: Set<String>, val fields: Map<String, Check>)
private fun shape(required: String, vararg fields: Pair<String, Check>) = Shape(required.split(' ').toSet(), fields.toMap())
private val recordShapes = mapOf(
    "delivered" to shape("id to class", "id" to text, "to" to array(text), "woke" to array(text), "class" to choice("A", "B", "C"), "rule" to text, "ticket" to nullable(integer()), "counted" to dictionary(integer()), "observed" to objectValue, "attested" to array(text)),
    "refused" to shape("id rule requirement", "id" to text, "rule" to text, "requirement" to text, "observed" to objectValue, "refusals_today" to integer()),
    "held" to shape("id by until", "id" to text, "by" to text, "until" to text, "ticket" to nullable(integer())),
    "expired" to shape("id", "id" to text, "rule" to text),
    "transition" to shape("machine subject from to cause", "machine" to choice("stage", "found", "flag", "seat", "phase", "presence"), "subject" to text, "from" to text, "to" to text, "rule" to text, "cause" to text),
    "fact" to shape("fact subject value", "fact" to text, "subject" to text, "value" to { true }, "evidence" to text),
    "op" to shape("op phase cause", "op" to text, "phase" to choice("requested", "ok", "failed"), "cause" to text, "with" to objectValue, "result" to objectValue, "reason" to text, "attempt" to integer(1)),
    "rules" to shape("hash files", "hash" to text, "files" to dictionary(text))
)
internal fun validateRecord(value: JsonObject) {
    val event = value["event"]
    require(event != null && text(event)) { "record event must be a string" }
    val shape = requireNotNull(recordShapes[event.jsonPrimitive.content]) { "unknown record event" }
    validateObject(value, shape.required + setOf("v", "seq", "at", "event"), common + shape.fields, true)
}
private fun validateObject(value: JsonObject, required: Set<String>, fields: Map<String, Check>, extra: Boolean) {
    require(value.keys.containsAll(required)) { "missing required fields: ${required - value.keys}" }
    value.forEach { (name, item) ->
        val check = fields[name]
        require(check != null || extra) { "unknown field: $name" }
        require(check == null || check(item)) { "invalid field: $name" }
    }
}
// Retain field presence, numeric spelling, and record extensions without retaining caller-owned mutable maps.
internal fun freeze(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { freeze(it.value) })
    is JsonArray -> JsonArray(value.map(::freeze))
    else -> value
}
