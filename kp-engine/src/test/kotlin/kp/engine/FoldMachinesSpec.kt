package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Envelope
import kp.model.parseLine

/** Lines as the log holds them; `seq`, and `at` unless the line has one, are filled in from its place in the list. */
fun log(vararg lines: String): List<Envelope> = lines.mapIndexed { index, line ->
    val at = if (""""at":""" in line) "" else """"at":"2026-11-14T09:${"%02d".format(index)}:00Z","""
    parseLine("""{"v":1,"seq":${index + 1},$at""" + line.removePrefix("{"))
}
fun move(machine: String, subject: String, from: String, to: String, cause: String = "m-panda-000001", extra: String = "") =
    """{"event":"transition","machine":"$machine","subject":"$subject","from":"$from","to":"$to","cause":"$cause"$extra}"""

class FoldMachinesSpec : StringSpec({
    "a stage's state is the to of its last transition" {
        val state = folded(log(move("stage", "KP/P1/S1.1", "assigned", "building"), move("stage", "KP/P1/S1.1", "building", "ready_for_review")))
        state.stage("KP/P1/S1.1") shouldBe "ready_for_review"
        state.stage("KP/P1/S1.2") shouldBe null
    }
    "each of the five companion machines is folded the same way, per subject" {
        val state = folded(log(
            move("found", "KP/P1/S1.1#1", "open", "triaged"),
            move("flag", "m-panda-000007", "raised", "ruled"),
            move("seat", "coder-1", "ready", "busy"),
            move("phase", "KP/P1", "running", "exit_pending"),
            move("presence", "bora", "present", "absent"),
        ))
        state.machines.mapValues { (_, subjects) -> subjects.mapValues { it.value.state } } shouldBe mapOf(
            "found" to mapOf("KP/P1/S1.1#1" to "triaged"),
            "flag" to mapOf("m-panda-000007" to "ruled"),
            "seat" to mapOf("coder-1" to "busy"),
            "phase" to mapOf("KP/P1" to "exit_pending"),
            "presence" to mapOf("bora" to "absent"),
        )
        state.presence("bora") shouldBe "absent"
    }
    "a machine's state says since when, what caused it, by which rule, and the state before it" {
        val state = folded(log(
            move("stage", "KP/P1/S1.1", "assigned", "building", extra = ""","rule":"T-08""""),
            move("stage", "KP/P1/S1.1", "building", "paused", cause = "m-panda-000009"),
            move("stage", "KP/P1/S1.1", "paused", "paused", cause = "m-panda-000010"),
        ))
        state.machine("stage", "KP/P1/S1.1") shouldBe MachineState(state = "paused", previous = "building", since = "2026-11-14T09:02:00Z", cause = "m-panda-000010", rule = null)
    }
    "a counter's value is the last one a record carries, whatever fired the transition" {
        val state = folded(log(
            """{"id":"m-panda-000001","thread":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"review-request","stage":"KP/P1/S1.1","subject":"r","created":"2026-11-14T09:00:00Z","by":"seat:coder-1"}""",
            """{"event":"delivered","id":"m-panda-000001","to":["teamlead"],"class":"A","counted":{"review_requests":1}}""",
            move("stage", "KP/P1/S1.1", "building", "ready_for_review", extra = ""","counted":{"review_requests":1}"""),
            """{"event":"expired","id":"m-panda-000001","rule":"T-74","deadline":"2026-11-14T11:00:00Z"}""",
            move("stage", "KP/P1/S1.1", "ready_for_review", "ready_for_review", extra = ""","counted":{"review_request_expiries":1}"""),
            move("stage", "KP/P1/S1.1", "ready_for_review", "ready_for_review", extra = ""","counted":{"review_request_expiries":2}"""),
        ))
        state.counters shouldBe mapOf("KP/P1/S1.1" to mapOf("review_requests" to 1L, "review_request_expiries" to 2L))
        state.counter("KP/P1/S1.1", "review_request_expiries") shouldBe 2L
        state.counter("KP/P1/S1.1", "fix_rounds") shouldBe 0L
    }
    "counters are kept per the subject of the transition that moved them; a delivery's under its message's stage" {
        val state = folded(log(
            move("stage", "KP/P1/S1.1", "in_review", "fix_round", extra = ""","counted":{"fix_rounds":1}"""),
            move("stage", "KP/P1/S1.2", "in_review", "fix_round", extra = ""","counted":{"fix_rounds":2}"""),
            """{"id":"m-panda-000003","thread":"m-panda-000003","from":"reviewer-1","to":"coder-2","kind":"verdict","stage":"TOY/P0/S0.1","subject":"v","created":"2026-11-14T09:00:00Z","by":"seat:reviewer-1"}""",
            """{"event":"delivered","id":"m-panda-000003","to":["coder-2"],"class":"A","counted":{"fix_rounds":1}}""",
        ))
        state.counters shouldBe mapOf("KP/P1/S1.1" to mapOf("fix_rounds" to 1L), "KP/P1/S1.2" to mapOf("fix_rounds" to 2L), "TOY/P0/S0.1" to mapOf("fix_rounds" to 1L))
    }
    "the state remembers the last line folded and the rule set in force" {
        val state = folded(log("""{"event":"rules","hash":"5f0c","files":{"rules/engagement.yaml":"0a1b"}}""", move("seat", "coder-1", "ready", "busy")))
        listOf(state.seq, state.at, state.rules) shouldBe listOf(2L, "2026-11-14T09:01:00Z", "5f0c")
    }
})
