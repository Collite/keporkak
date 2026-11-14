package kp.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class SkeletonSpec : StringSpec({
    "a message that follows the contract is read and written back unchanged" {
        val source = Json.parseToJsonElement("""{"v":1,"seq":1,"at":"2026-11-13T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"started","subject":"Skeleton","created":"2026-11-13T12:00:00Z","by":"hand:coder"}""").jsonObject
        Message.read(source).json shouldBe source
    }

    "a message with an unknown field or a malformed id is rejected" {
        val source = validMessage()
        shouldThrowAny { Message.read(JsonObject(source + ("unknown" to JsonPrimitive(true)))) }
        shouldThrowAny { Message.read(JsonObject(source + ("id" to JsonPrimitive("m-panda-1")))) }
    }

    "every record type is read and written back unchanged" {
        assertRecordRoundTrips(listOf(
            """{"event":"delivered","id":"m-panda-000001","to":["teamlead"],"class":"A"}""",
            """{"event":"refused","id":"m-panda-000001","rule":"started","requirement":"branch exists"}""",
            """{"event":"held","id":"m-panda-000001","by":"robot:DEP-1","until":"presence"}""",
            """{"event":"expired","id":"m-panda-000001"}""",
            """{"event":"transition","machine":"stage","subject":"KP/P0/S0.2","from":"planned","to":"building","cause":"m-panda-000001"}""",
            """{"event":"fact","fact":"branch_exists","subject":"KP/P0/S0.2","value":true}""",
            """{"event":"op","op":"worktree","phase":"ok","cause":"m-panda-000001"}""",
            """{"event":"rules","hash":"abc","files":{"rules/stage-lifecycle.yaml":"def"}}"""
        ))
    }

    "the model accepts exactly what the contract's schemas accept, on one shared set of examples" {
        assertSharedExamplesConform(sharedExamples())
    }

    "a dependency added to the engine module fails the build" {
        val result = engineDependencyProbe("org.example:forbidden:1.0")
        result.exitCode shouldBe 1
        result.output.contains("kp-engine dependency is forbidden: org.example:forbidden") shouldBe true
    }
})
