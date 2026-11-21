package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.mailroom.FactSource
import kp.mailroom.Status

/**
 * The slice of T5 in the daemon: what the log knows for GitHub's facts (Amendment 33) — the `pr.head` a message's judgement
 * observed, read back from the log, and when a stage's last message of some kinds was delivered, from the fold.
 */
class OfficeLogSpec : FreeSpec({
    "the head a message's judgement observed is read back from the log — refused or delivered — and a message judged with none, or not on the log, has none" {
        val head = "028e9e2dece9c94f65f3c0da144e6f2ce3c9f013"
        val github = FactSource { fact, _ ->
            when (fact.name) {
                "pr.ready" -> Datum.of(true)
                "pr.head" -> Datum.Text(head)
                "ci.state" -> Datum.Text("green")
                else -> null
            }
        }
        FactsOffice(beside = github, states = mapOf("KP/P3/S3.1" to "building")).use { office ->
            val log = OfficeLog().apply { this.office = office.office }

            office.send("coder-1", """{"kind": "review-request", "stage": "KP/P3/S3.1", "subject": "review", "args": {"attest": ["done_lines_hold"]}}""") should
                refusedBy("review-request", """{"fact":"list.tasks_open","eq":0}""")
            val requested = office.office.lines().map { it.entry }.filterIsInstance<kp.model.Message>().single { it.kind == "review-request" }.id.value
            office.send("teamlead", """{"kind": "ruling-request", "stage": "KP/P3/S3.1", "subject": "a ruling", "args": {"default": "go on", "citation": "D-95"}}""").status shouldBe Status.DELIVERED
            val ruling = office.office.lines().map { it.entry }.filterIsInstance<kp.model.Message>().single { it.kind == "ruling-request" }.id.value

            log.observedHead(requested) shouldBe head
            log.observedHead(ruling) shouldBe null
            log.observedHead("m-panda-999999") shouldBe null
            log.lastDelivered("KP/P3/S3.1", setOf("ruling-request", "verdict")) shouldBe "2026-11-20T09:00:00Z"
            log.lastDelivered("KP/P3/S3.1", setOf("verdict", "re-verdict")) shouldBe null
            log.lastDelivered("KP/P3/S3.0", setOf("ruling-request")) shouldBe null
        }
    }
})
