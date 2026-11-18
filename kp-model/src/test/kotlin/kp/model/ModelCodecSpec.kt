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
    "the fields of Amendments 8 and 12: a message's signed, delivered's answers and deadline, held's suspended deadline, refused's failed requirement" {
        val signed = parseLine("""{"v":1,"seq":1,"at":"2026-11-15T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","from":"bora","to":"teamlead","kind":"decline","subject":"no","created":"2026-11-15T12:00:00Z","signed":true,"by":"bora-cli"}""") as Message
        val delivered = parseLine("""{"v":1,"seq":2,"at":"2026-11-15T12:00:00Z","event":"delivered","id":"m-panda-000002","to":["teamlead"],"class":"B","answers":"m-panda-000001","deadline":"2026-11-15T13:00:00Z"}""") as Delivered
        val held = parseLine("""{"v":1,"seq":3,"at":"2026-11-15T12:00:00Z","event":"held","id":"m-panda-000003","by":"robot:DEP-1","until":"presence","deadline":null}""") as Held
        val refused = parseLine("""{"v":1,"seq":4,"at":"2026-11-15T12:00:00Z","event":"refused","id":"m-panda-000004","rule":"T-13","requirement":"counter resumes ≤ 2","failed":{"counter":"resumes","lt":{"cap":"resumes_per_stage"}}}""") as Refused

        listOf(signed.signed, (parseLine(validMessage().toString()) as Message).signed) shouldBe listOf(true, false)
        listOf(delivered.answers, delivered.deadline?.value, delivered.carriesDeadline) shouldBe listOf("m-panda-000001", "2026-11-15T13:00:00Z", true)
        listOf(held.deadline, held.carriesDeadline) shouldBe listOf(null, true)
        refused.failed.toString() shouldBe """{"counter":"resumes","lt":{"cap":"resumes_per_stage"}}"""
        shouldThrowAny { Message.read(JsonObject(validMessage() + ("signed" to JsonPrimitive("bora")))) }
    }
    "the records of Amendment 23 give their fields: judged's first line, voided's first and last" {
        val judged = parseLine("""{"v":1,"seq":9,"at":"2026-11-17T12:00:00Z","event":"judged","first":5}""") as Judged
        val voided = parseLine("""{"v":1,"seq":10,"at":"2026-11-17T12:00:00Z","event":"voided","first":5,"last":9}""") as Voided

        judged.first shouldBe 5L
        listOf(voided.first, voided.last) shouldBe listOf(5L, 9L)
        judged.toLine() shouldBe """{"v":1,"seq":9,"at":"2026-11-17T12:00:00Z","event":"judged","first":5}"""
    }
    "a line's plain view is standard-library values: numbers exact, null kept apart from absent" {
        val line = parseLine("""{"v":1,"seq":1,"at":"2026-11-15T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","re":null,"from":"teamlead","to":"coder-1","kind":"assign","subject":"go","created":"2026-11-15T12:00:00Z","by":"seat:teamlead","args":{"seat":"coder-1","tries":[1,2.50],"open":true,"none":null,"of":{"x":"y"}}}""") as Message

        line.plainArgs shouldBe mapOf("seat" to "coder-1", "tries" to listOf("1".toBigDecimal(), "2.50".toBigDecimal()), "open" to true, "none" to null, "of" to mapOf("x" to "y"))
        line.plain().containsKey("re") shouldBe true
        line.plain()["re"] shouldBe null
        line.plain().containsKey("cc") shouldBe false
        plain("""[1, "a", null]""") shouldBe listOf("1".toBigDecimal(), "a", null)
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
