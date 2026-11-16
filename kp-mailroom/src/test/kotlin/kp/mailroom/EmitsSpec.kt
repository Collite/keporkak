package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Delivered

/** T4 — the declared emits: what the rules make the mailroom send goes through the same loop, `by` naming the transition. */
class EmitsSpec : StringSpec({
    afterTest { Office.closeAll() }

    "a review-request with a Found line untriaged makes the mailroom send found-batch to the principal, by T-17, in reply to it" {
        val office = Office(seed = "building")

        office.play("review-request")

        val (request, batch) = office.readBack().messages
        listOf(batch.kind, batch.from.value, batch.to.value, batch.by, batch.re?.value, batch.thread.value, batch.stage?.value) shouldBe
            listOf("found-batch", "mailroom", "principal", "effect:T-17", request.id.value, request.thread.value, STAGE)
        office.readBack().records<Delivered>().map { it.id } shouldBe listOf(request.id.value, batch.id.value)
        office.readBack().records<Delivered>().last().observed!!.keys shouldBe setOf("list.found_untriaged")
        office.seats.woken.map { it.first } shouldBe listOf("teamlead", "principal")
    }

    "with no Found line untriaged, no found-batch is sent" {
        val office = Office(seed = "building")
        office.facts.set("list.found_untriaged", 0)

        office.play("review-request")

        office.readBack().messages.map { it.kind } shouldBe listOf("review-request")
    }

    "the third FIX FIRST makes the mailroom send grant-request to the principal, by T-24, judged under its own kind's requirement" {
        val office = Office(seed = "re_review", counters = """{"fix_rounds": 2}""")
        office.facts.set("pr.head", "head-3")
        office.facts.set("pr.review_on", "changes_requested")

        val fixFirst = office.send("reviewer-1", """{"kind": "re-verdict", "stage": "$STAGE", "subject": "still blocked",
            "args": {"verdict": "FIX FIRST", "sha": "head-3", "blockers": [{"id": "B3"}], "new_findings": [], "follow_ups": [], "reviewed": []}}""")

        val grant = office.readBack().sent("grant-request").single()
        listOf(fixFirst.transition, grant.by, grant.to.value, grant.re?.value) shouldBe listOf("T-24", "effect:T-24", "principal", fixFirst.id)
        office.readBack().records<Delivered>().single { it.id == grant.id.value }.rule shouldBe null
        office.stage() shouldBe "grant_pending"
    }
})
