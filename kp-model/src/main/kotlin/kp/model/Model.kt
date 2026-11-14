package kp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigInteger

interface Envelope {
    val json: JsonObject
    val seq: BigInteger get() = json.getValue("seq").jsonPrimitive.content.toBigDecimal().toBigIntegerExact()
    val at: Timestamp get() = Timestamp(json.string("at"))
}
internal fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.optionalString(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.strings(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }
internal fun JsonObject.number(key: String) = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toBigDecimal()?.toBigIntegerExact()

/** A validated wire value; the tree preserves omitted fields separately from explicit nulls. No I/O. */
@Serializable(with = MessageSerializer::class)
class Message private constructor(override val json: JsonObject) : Envelope {
    val id get() = MessageId(json.string("id"))
    val thread get() = MessageId(json.string("thread"))
    val re get() = json.optionalString("re")?.let(::MessageId)
    val from get() = Party(json.string("from"))
    val to get() = Party(json.string("to"))
    val cc get() = json.strings("cc")?.map(::Party)
    val kind get() = json.string("kind")
    val stage get() = json.optionalString("stage")?.let(::StageId)
    val subject get() = json.string("subject")
    val body get() = json.optionalString("body")
    val pointers get() = json.strings("pointers")?.map(::Pointer)
    val args get() = json["args"]?.jsonObject
    val deadline get() = json.optionalString("deadline")?.let(::Timestamp)
    val created get() = Timestamp(json.string("created"))
    val by get() = json.string("by")
    companion object {
        fun read(value: JsonObject): Message {
            val snapshot = freeze(value).jsonObject
            validateMessage(snapshot)
            return Message(snapshot)
        }
    }
}

@Serializable(with = RecordSerializer::class)
sealed class Record protected constructor(value: JsonObject) : Envelope {
    final override val json = freeze(value).jsonObject
    init { validateRecord(json) }
    val event get() = json.string("event")
    companion object {
        fun read(value: JsonObject): Record = when (value["event"]?.jsonPrimitive?.content) {
            "delivered" -> Delivered(value)
            "refused" -> Refused(value)
            "held" -> Held(value)
            "expired" -> Expired(value)
            "transition" -> Transition(value)
            "fact" -> Fact(value)
            "op" -> Op(value)
            "rules" -> Rules(value)
            else -> throw IllegalArgumentException("unknown record event")
        }
    }
}
@Serializable(with = DeliveredSerializer::class)
class Delivered internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val to get() = json.strings("to")!!
    val woke get() = json.strings("woke")
    val deliveryClass get() = json.string("class")
    val rule get() = json.optionalString("rule")
    val ticket get() = json.number("ticket")
    val counted get() = json["counted"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toBigDecimal().toBigIntegerExact() }
    val observed get() = json["observed"]?.jsonObject
    val attested get() = json.strings("attested")
}
@Serializable(with = RefusedSerializer::class)
class Refused internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val rule get() = json.string("rule")
    val requirement get() = json.string("requirement")
    val observed get() = json["observed"]?.jsonObject
    val refusalsToday get() = json.number("refusals_today")
}
@Serializable(with = HeldSerializer::class)
class Held internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val by get() = json.string("by")
    val until get() = json.string("until")
    val ticket get() = json.number("ticket")
}
@Serializable(with = ExpiredSerializer::class)
class Expired internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val rule get() = json.optionalString("rule")
}
@Serializable(with = TransitionSerializer::class)
class Transition internal constructor(value: JsonObject) : Record(value) {
    val machine get() = json.string("machine")
    val subject get() = json.string("subject")
    val from get() = json.string("from")
    val to get() = json.string("to")
    val rule get() = json.optionalString("rule")
    val cause get() = json.string("cause")
}
@Serializable(with = FactSerializer::class)
class Fact internal constructor(value: JsonObject) : Record(value) {
    val fact get() = json.string("fact")
    val subject get() = json.string("subject")
    val value get() = json.getValue("value")
    val evidence get() = json.optionalString("evidence")
}
@Serializable(with = OpSerializer::class)
class Op internal constructor(value: JsonObject) : Record(value) {
    val op get() = json.string("op")
    val phase get() = json.string("phase")
    val cause get() = json.string("cause")
    val with get() = json["with"]?.jsonObject
    val result get() = json["result"]?.jsonObject
    val reason get() = json.optionalString("reason")
    val attempt get() = json.number("attempt")
}
@Serializable(with = RulesSerializer::class)
class Rules internal constructor(value: JsonObject) : Record(value) {
    val hash get() = json.string("hash")
    val files get() = json.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
}
