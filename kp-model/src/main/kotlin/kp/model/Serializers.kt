package kp.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

abstract class EnvelopeSerializer<T : Envelope>(name: String, private val read: (JsonObject) -> T) : KSerializer<T> {
    override val descriptor = buildClassSerialDescriptor("kp.model.$name")
    override fun serialize(encoder: Encoder, value: T) {
        require(encoder is JsonEncoder) { "Keporkak wire values require JSON" }
        encoder.encodeJsonElement(value.json)
    }
    override fun deserialize(decoder: Decoder): T {
        require(decoder is JsonDecoder) { "Keporkak wire values require JSON" }
        return read(decoder.decodeJsonElement().jsonObject)
    }
}
object MessageSerializer : EnvelopeSerializer<Message>("Message", Message::read)
object RecordSerializer : EnvelopeSerializer<Record>("Record", Record::read)
object DeliveredSerializer : EnvelopeSerializer<Delivered>("Delivered", { Record.read(it) as? Delivered ?: throw IllegalArgumentException("expected Delivered record") })
object RefusedSerializer : EnvelopeSerializer<Refused>("Refused", { Record.read(it) as? Refused ?: throw IllegalArgumentException("expected Refused record") })
object HeldSerializer : EnvelopeSerializer<Held>("Held", { Record.read(it) as? Held ?: throw IllegalArgumentException("expected Held record") })
object ExpiredSerializer : EnvelopeSerializer<Expired>("Expired", { Record.read(it) as? Expired ?: throw IllegalArgumentException("expected Expired record") })
object TransitionSerializer : EnvelopeSerializer<Transition>("Transition", { Record.read(it) as? Transition ?: throw IllegalArgumentException("expected Transition record") })
object FactSerializer : EnvelopeSerializer<Fact>("Fact", { Record.read(it) as? Fact ?: throw IllegalArgumentException("expected Fact record") })
object OpSerializer : EnvelopeSerializer<Op>("Op", { Record.read(it) as? Op ?: throw IllegalArgumentException("expected Op record") })
object RulesSerializer : EnvelopeSerializer<Rules>("Rules", { Record.read(it) as? Rules ?: throw IllegalArgumentException("expected Rules record") })
