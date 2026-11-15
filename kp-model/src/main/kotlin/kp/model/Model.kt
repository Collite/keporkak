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
    /** `args` as standard-library values (see [plain]): what a reader with no JSON library reads — the engine. */
    val plainArgs: Map<String, Any?> get() = json["args"]?.let { plainValue(it) as Map<String, Any?> }.orEmpty()
    val deadline get() = json.optionalString("deadline")?.let(::Timestamp)
    /** Whether the line carries `deadline`, null included: a wait's deadline is the last one a line carries (§2.2). */
    val carriesDeadline get() = "deadline" in json
    val created get() = Timestamp(json.string("created"))
    /** Set by the mailroom: the message came from `kp` over a root peer (§2.1, §6). Absent is false. */
    val signed get() = json["signed"]?.jsonPrimitive?.boolean ?: false
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
            "read" -> Read(value)
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
    /** The waiting message this delivery answers: it closes that wait and its ticket (Amendment 8). */
    val answers get() = json.optionalString("answers")
    /** The deadline that applies from this delivery — a held message's, counted from its release (Amendment 8). */
    val deadline get() = json.optionalString("deadline")?.let(::Timestamp)
    val carriesDeadline get() = "deadline" in json
}
@Serializable(with = RefusedSerializer::class)
class Refused internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val rule get() = json.string("rule")
    /** The rule's guard in the rules' own prose. */
    val requirement get() = json.string("requirement")
    /** The first requirement that failed, in its file form (Amendment 12). */
    val failed get() = json["failed"]?.jsonObject
    val observed get() = json["observed"]?.jsonObject
    val refusalsToday get() = json.number("refusals_today")
}
@Serializable(with = HeldSerializer::class)
class Held internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val by get() = json.string("by")
    val until get() = json.string("until")
    val ticket get() = json.number("ticket")
    /** Null while held: holding a message suspends its deadline (Amendment 8). */
    val deadline get() = json.optionalString("deadline")?.let(::Timestamp)
    val carriesDeadline get() = "deadline" in json
}
@Serializable(with = ExpiredSerializer::class)
class Expired internal constructor(value: JsonObject) : Record(value) {
    val id get() = json.string("id")
    val rule get() = json.optionalString("rule")
    /** `bora` when the kind's expiry reports to Bora; the record is then the inbox item. */
    val report get() = json.optionalString("report")
    val deliveryClass get() = json.optionalString("class")
    val ticket get() = json.number("ticket")
    /** When the wait starts again; null when it is over (Amendment 6). */
    val deadline get() = json.optionalString("deadline")?.let(::Timestamp)
}
@Serializable(with = TransitionSerializer::class)
class Transition internal constructor(value: JsonObject) : Record(value) {
    val machine get() = json.string("machine")
    val subject get() = json.string("subject")
    val from get() = json.string("from")
    val to get() = json.string("to")
    val rule get() = json.optionalString("rule")
    val cause get() = json.string("cause")
    /** The new values of the counters this transition moved, whatever fired it (Amendment 6). */
    val counted get() = json["counted"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content.toBigDecimal().toBigIntegerExact() }
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
/** A seat has seen these messages: its `inbox` returned them, or it was woken with one (Amendment 5). */
@Serializable(with = ReadSerializer::class)
class Read internal constructor(value: JsonObject) : Record(value) {
    val seat get() = json.string("seat")
    val ids get() = json.strings("ids")!!
}
