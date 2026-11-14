package kp.model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny
import kotlinx.serialization.json.*

class ModelCodecSpec : StringSpec({
    "message serialization retains absent and explicit null fields distinctly" {
        val source = JsonObject(validMessage() + mapOf("re" to JsonNull, "args" to buildJsonObject { put("open", true) }))
        val message = Json.decodeFromJsonElement(Message.serializer(), source)
        Json.encodeToJsonElement(Message.serializer(), message) shouldBe source
        message.id.value shouldBe "m-panda-000001"
        message.args?.get("open") shouldBe JsonPrimitive(true)
    }
    "record serialization retains extensions allowed by the schema" {
        val source = Json.parseToJsonElement("""{"v":1,"seq":2,"at":"2026-11-13T12:00:01Z","event":"fact","fact":"branch","subject":"KP/P0/S0.2","value":null,"future":{"nested":true}}""").jsonObject
        val record = Json.decodeFromJsonElement(Record.serializer(), source)
        (record is Fact) shouldBe true
        Json.encodeToJsonElement(Record.serializer(), record) shouldBe source
    }
    "model validation agrees with all independently labelled examples" {
        sharedExamples().forEach { modelAccepts(it) shouldBe it.accepted }
    }
    "a record timestamp with a trailing newline is rejected" {
        val source = Json.parseToJsonElement("""{"v":1,"seq":2,"at":"2026-11-13T12:00:01Z\n","event":"expired","id":"m-panda-000001"}""").jsonObject
        shouldThrowAny { Record.read(source) }
    }
    "ids reject malformed values at construction" {
        shouldThrowAny { MessageId("m-panda-1") }
        shouldThrowAny { StageId("KP/S0.2") }
        shouldThrowAny { Party("Coder") }
        shouldThrowAny { Pointer("ab") }
    }
})
