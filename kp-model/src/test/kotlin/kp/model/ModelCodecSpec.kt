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
    "the amended records give their new fields: read's seat and ids, expired's wait, transition's counters" {
        val read = parseLine("""{"v":1,"seq":3,"at":"2026-11-14T16:00:00Z","event":"read","seat":"coder-1","ids":["m-panda-000101"]}""") as Read
        val expired = parseLine("""{"v":1,"seq":4,"at":"2026-11-14T16:00:00Z","event":"expired","id":"m-panda-000105","rule":"T-74","report":"bora","class":"C","ticket":3,"deadline":"2026-11-14T17:00:00Z"}""") as Expired
        val over = parseLine("""{"v":1,"seq":5,"at":"2026-11-14T17:00:00Z","event":"expired","id":"m-panda-000105","deadline":null}""") as Expired
        val transition = parseLine("""{"v":1,"seq":6,"at":"2026-11-14T16:00:00Z","event":"transition","machine":"stage","subject":"KP/P1/S1.1","from":"ready_for_review","to":"ready_for_review","cause":"m-panda-000105","counted":{"review_request_expiries":1}}""") as Transition
        read.seat shouldBe "coder-1"
        read.ids shouldBe listOf("m-panda-000101")
        listOf(expired.report, expired.deliveryClass, expired.ticket?.toInt(), expired.deadline?.value) shouldBe listOf("bora", "C", 3, "2026-11-14T17:00:00Z")
        over.deadline shouldBe null
        transition.counted shouldBe mapOf("review_request_expiries" to 1.toBigInteger())
    }
    "a line is a message when it has kind and a record when it has event, and writes back as the same one line" {
        val text = """{"v":1,"seq":1,"at":"2026-11-13T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"started","subject":"Skeleton","created":"2026-11-13T12:00:00Z","by":"hand:coder","body":"two\nlines"}"""
        (parseLine(text) is Message) shouldBe true
        parseLine(text).toLine() shouldBe text
        (parseLine("""{"v":1,"seq":2,"at":"2026-11-13T12:00:01Z","event":"expired","id":"m-panda-000001"}""") is Expired) shouldBe true
        shouldThrowAny { parseLine("""{"v":1,"seq":2,"at":"2026-11-13T12:00:01Z"}""") }
        shouldThrowAny { parseLine("[1]") }
    }
    "a timestamp's UTC time takes its offset and keeps nanoseconds" {
        utc("2026-12-01T00:30:00+01:00").toString() shouldBe "2026-11-30T23:30"
        utc("2026-11-14t09:00:00.1234567891z").toString() shouldBe "2026-11-14T09:00:00.123456789"
        shouldThrowAny { utc("2026-11-14") }
    }
    "ids reject malformed values at construction" {
        shouldThrowAny { MessageId("m-panda-1") }
        shouldThrowAny { StageId("KP/S0.2") }
        shouldThrowAny { Party("Coder") }
        shouldThrowAny { Pointer("ab") }
    }
})
