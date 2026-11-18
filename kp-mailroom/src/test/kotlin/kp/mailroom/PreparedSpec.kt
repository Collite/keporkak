package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kp.model.Refused

/** T8 — what the mailroom writes that S1.7 prepared (contracts.md §2.1, §2.2, §7). */
class PreparedSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a refusal's record carries the requirement that failed, in its file form" {
        val office = Office(seed = "building")

        val answer = office.send("coder-1", """{"kind": "stop", "stage": "$STAGE", "subject": "stop", "args": {"sc": "SC-9"}}""")

        office.readBack().records<Refused>().single { it.id == answer.id }.json["failed"].toString() shouldBe
            """{"arg":"sc","in":{"fact":"list.stop_conditions"}}"""
    }

    "a delivery carries what it answers and the deadline it sets, on its record as on the message's line" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", """{"kind": "stop", "stage": "$STAGE", "subject": "stop", "args": {"sc": "SC-3"}}""")

        val triage = office.send("principal", """{"kind": "triage", "re": "${stop.id}", "args": {"outcome": "resume", "found": "f", "since": "$START"}}""")

        val delivered = office.readBack().records<Delivered>()
        delivered.single { it.id == stop.id }.deadline?.value shouldBe "2026-11-16T13:00:00Z"
        office.readBack().message(stop.id).deadline?.value shouldBe "2026-11-16T13:00:00Z"
        delivered.single { it.id == triage.id }.answers shouldBe stop.id
        office.mailroom.state.deadlines shouldBe emptyMap()
    }

    "an operation interrupted by a stop is recorded failed once: a second start finds nothing in flight" {
        val office = Office(seed = "proposed")
        office.operations.holding = true
        val approval = office.bora("""{"kind": "approval", "stage": "$STAGE", "subject": "approved"}""")

        office.restart()
        office.restart()

        office.readBack().ops(approval.id).withoutWakes() shouldBe listOf("worktree.create requested", "worktree.create failed interrupted")
        office.mailroom.state.operations shouldBe emptyList()
    }

    "an interrupted operation's failure is judged as its result: a merge interrupted is a merge failed, T-37" {
        val office = Office(seed = "passed")
        office.facts.set("pr.head", "head-2")
        office.facts.set("list.found_untriaged", 0)
        office.facts.set("list.tasks_open", 0)
        office.operations.holding = true
        office.play("merge")
        val merge = office.readBack().sent("merge").single().id.value

        office.restart()

        office.readBack().ops(merge) shouldBe listOf("git.merge requested", "git.merge failed interrupted")
        office.readBack().transitions(merge).last() shouldBe "T-37 passed→passed"
    }
})
