package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kotlin.time.Duration.Companion.hours

/** T4 — the declared emits: what the rules make the mailroom send goes through the same loop, `by` naming the rule that declares it. */
class EmitsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a review-request with a Found line untriaged makes the mailroom send found-batch to the principal, by T-17, in reply to it" {
        val office = Office(seed = "building")

        office.play("review-request")

        val (request, batch) = office.readBack().messages
        listOf(batch.kind, batch.from.value, batch.to.value, batch.by, batch.re?.value, batch.thread.value, batch.stage?.value) shouldBe
            listOf("found-batch", "mailroom", "principal", "effect:T-17", request.id.value, request.thread.value, STAGE)
        office.readBack().records<Delivered>().map { it.id } shouldBe listOf(request.id.value, batch.id.value)
        office.readBack().records<Delivered>().last().observed!!.keys shouldBe setOf("list.found_untriaged")
        // The teamlead and the principal it copies are woken with the review-request; the principal then with the found-batch (S2.4)
        // — two seats side by side, one seat's wakes in the order requested (contracts.md §7, Amendment 36).
        office.readBack()
        office.seats.woken shouldContainExactlyInAnyOrder listOf("teamlead" to request.id.value, "principal" to request.id.value, "principal" to batch.id.value)
        office.seats.woken.filter { it.first == "principal" } shouldBe listOf("principal" to request.id.value, "principal" to batch.id.value)
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

    "the fourth FIX FIRST makes the mailroom send ruling-request to Bora, by T-29, with the default and the citation T-29 declares (S1.8, D-68)" {
        val office = Office(seed = "re_review", counters = """{"fix_rounds": 3}""")
        office.facts.set("pr.head", "head-4")
        office.facts.set("pr.review_on", "changes_requested")

        val fixFirst = office.send("reviewer-1", """{"kind": "re-verdict", "stage": "$STAGE", "subject": "still blocked",
            "args": {"verdict": "FIX FIRST", "sha": "head-4", "blockers": [{"id": "B4"}], "new_findings": [], "follow_ups": [], "reviewed": []}}""")

        val ruling = office.readBack().sent("ruling-request").single()
        listOf(fixFirst.transition, ruling.by, ruling.to.value, ruling.args?.toString()) shouldBe
            listOf("T-29", "effect:T-29", "bora", """{"default":"recut","citation":"T-29"}""")
        office.readBack().records<Delivered>().single { it.id == ruling.id.value }.to shouldBe listOf("bora")
        office.stage() shouldBe "ruling_pending"
    }

    "an assignment-proposal that expires with its proposals spent goes back to planned, and T-07's nudge reaches the principal (S1.8)" {
        val office = Office(seed = "planned", counters = """{"proposals": 1}""")
        office.send("teamlead", """{"kind": "assignment-proposal", "stage": "$STAGE", "subject": "propose", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}""")

        office.clock.advance(24.hours)
        office.mailroom.tick()

        val nudge = office.readBack().sent("nudge").single()
        office.stage() shouldBe "planned"
        listOf(nudge.by, nudge.to.value) shouldBe listOf("effect:T-07", "principal")
        office.readBack().records<Delivered>().single { it.id == nudge.id.value }.to shouldBe listOf("principal", "board")
    }
})
