package kp.mailroom

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.model.Delivered
import kp.mailroom.Status.DELIVERED
import kp.mailroom.Status.REFUSED
import kotlin.time.Duration.Companion.hours

private val H1_PRIME = listOf(
    "assignment-proposal", "approval", "assign", "started", "stop", "triage", "review-request",
    "review-bind", "verdict", "repair-done", "re-verdict", "merge", "stage-exit",
)

/**
 * KP/P1/S1.3 — the mailroom. One spec per behaviour the stage list names (T1), observed at the seam it gives: a
 * message submitted in a sender's name, and the clock — with facts, operations and seats faked, the log in a
 * throwaway project, and master's rules. `Office` (MailroomSupport.kt) is that office; `play` sends the next step of
 * each kind of `rules/walks/h1-prime.yaml` from its sender, with its args and facts. After every spec, its log is read
 * back line by line: `seq` has no gap, and no operation is requested twice for one request.
 */
class TheMailroomSpec : FreeSpec({
    afterTest { Office.checkAndCloseAll() }

    "H-1′'s first seven steps pass through with scripted senders and leave the stage `exited`" {
        val office = Office()

        val proposed = office.play("assignment-proposal", "approval", "assign")
        val started = office.send("coder-1", """{"kind": "started", "stage": "$STAGE", "subject": "started", "from": "teamlead"}""")
        val built = office.play("stop", "triage", "review-request", "review-bind", "verdict", "repair-done", "re-verdict", "merge", "stage-exit")

        proposed shouldBe listOf("proposed", "assigned", "assigned")
        started.status shouldBe DELIVERED
        built shouldBe listOf("stopped", "building", "ready_for_review", "in_review", "fix_round", "re_review", "passed", "merged", "exited")
        office.readBack().message(started.id).from.value shouldBe "coder-1"
        office.readBack().sent("found-batch").single().by shouldBe "effect:T-17"
        office.readBack().recordsBeforeTheirMessage() shouldBe emptyList()
    }

    "a mailroom stopped in the middle and started again on the same log ends in the same state as one that was not" {
        val uninterrupted = Office().statesAfter(H1_PRIME)

        val restarted = H1_PRIME.indices.map { step -> Office().apply { play(H1_PRIME.take(step + 1)); restart() }.mailroom.state }
        val carriedOn = Office().apply { play(H1_PRIME.take(5)); restart(); play(H1_PRIME.drop(5)) }

        // The start writes its own `rules` record: one line more, and nothing else.
        restarted.map { it.copy(seq = 0) } shouldBe uninterrupted.map { it.copy(seq = 0) }
        carriedOn.stage() shouldBe "exited"
    }

    "a deadline that passes does what its kind's expiry says" - {
        "a review-request's first expiry counts, sends its nudge and waits again; the second reports to Bora, class C, with a ticket" {
            val office = Office(seed = "building")
            office.play("review-request")
            val request = office.readBack().sent("review-request").single().id.value

            office.clock.advance(1.hours)
            office.mailroom.tick()
            office.clock.advance(1.hours)
            office.mailroom.tick()

            val (first, second) = office.readBack().expired(request)
            first.deadline?.value shouldBe "2026-11-16T11:01:00Z"
            val nudge = office.readBack().sent("nudge").single()
            "${nudge.to.value} ${nudge.by} ${nudge.re?.value}" shouldBe "teamlead expiry:review-request $request"
            // Judged like any other, the nudge is delivered: the rule that declares it addresses it (S1.8, contracts.md §4.1).
            office.readBack().records<Delivered>().single { it.id == nudge.id.value }.to shouldBe listOf("teamlead", "board")
            listOf(second.report, second.deliveryClass, second.deadline) shouldBe listOf("bora", "C", null)
            office.mailroom.state.openTickets().getValue(second.ticket!!.toLong()).item shouldBe request
            office.mailroom.state.deadlines shouldBe emptyMap()
        }

        "a review-request whose stage is paused expires once, with no new deadline" {
            val office = Office(seed = "building")
            office.play("review-request")
            val request = office.readBack().sent("review-request").single().id.value
            office.facts.set("seat.process_gone", true)
            office.send("pool-1", """{"kind": "seat-gone", "stage": "$STAGE", "subject": "coder-1 is gone", "args": {"seat": "coder-1"}}""")

            office.clock.advance(1.hours)
            office.mailroom.tick()
            office.clock.advance(1.hours)
            office.mailroom.tick()

            office.stage() shouldBe "paused"
            office.readBack().expired(request).map { it.deadline } shouldBe listOf(null)
            office.mailroom.state.deadlines shouldBe emptyMap()
        }

        "a reply of a kind the waiting kind does not expect leaves the wait open; the expected one closes it and its ticket" {
            val office = Office(seed = "ready_for_review")
            office.facts.set("plan.stage_has_list", true)
            val request = office.send("principal", """{"kind": "ruling-request", "stage": "$STAGE", "subject": "who owns the Found line?", "args": {"default": "cut the owning stage", "citation": "contracts.md §4.5"}}""")

            val unexpected = office.send("principal", """{"kind": "pin-stage", "re": "${request.id}", "stage": "$STAGE", "subject": "pin it", "args": {"stage": "reader-pin", "reason": "the Found line needs its owning stage"}}""")
            val stillWaiting = office.mailroom.state.deadlines.keys
            val stillOpen = office.mailroom.state.openTickets().values.map { it.item }
            val expected = office.bora("""{"kind": "ruling", "re": "${request.id}", "subject": "cut it", "args": {"outcome": "stage"}}""")

            unexpected.status shouldBe DELIVERED
            stillWaiting shouldBe setOf(request.id)
            stillOpen shouldBe listOf(request.id)
            expected.status shouldBe DELIVERED
            office.mailroom.state.deadlines.keys shouldNotContain request.id
            office.mailroom.state.tickets.values.single { it.item == request.id }.answeredBy shouldBe expected.id
        }

        "a ticket for an item that does not wait is closed by Bora's signed answer, and a second answer to it is refused" {
            val office = Office(seed = "recut")
            val notice = office.send("teamlead", """{"kind": "recut-notice", "stage": "$STAGE", "subject": "S2.1 is recut"}""")
            val ticket = office.mailroom.state.openTickets().entries.single { it.value.item == notice.id }.key

            val answer = office.bora("""{"kind": "ruling", "re": "${notice.id}", "subject": "noted", "args": {"outcome": "recut"}}""")
            val again = office.bora("""{"kind": "ruling", "re": "${notice.id}", "subject": "noted again", "args": {"outcome": "recut"}}""")

            office.mailroom.state.deadlines.keys shouldNotContain notice.id
            answer.status shouldBe DELIVERED
            office.mailroom.state.tickets.getValue(ticket).answeredBy shouldBe answer.id
            listOf(again.status, again.requirement) shouldBe listOf(REFUSED, "ticket #$ticket: answered once")
        }
    }

    "an operation is requested once, and its result fires the transition declared for it" - {
        "Bora's approval requests worktree.create once, and its `ok` fires T-03" {
            val office = Office(seed = "proposed")

            val approval = office.bora("""{"kind": "approval", "stage": "$STAGE", "subject": "approved"}""")

            office.operations.requested.filter { it.cause == approval.id } shouldBe
                listOf(OperationRequest("worktree.create", approval.id, Datum.Fields(mapOf("stage" to Datum.Text(STAGE)))))
            office.readBack().ops(approval.id).withoutWakes() shouldBe listOf("worktree.create requested", "worktree.create ok")
            office.readBack().transitions(approval.id) shouldBe listOf("T-02 proposed→assigned", "T-03 assigned→assigned")
        }

        "a mailroom started on a log with an operation requested and not finished records it `failed`, `interrupted`, and performs nothing" {
            val office = Office(seed = "proposed")
            office.operations.holding = true
            val approval = office.bora("""{"kind": "approval", "stage": "$STAGE", "subject": "approved"}""")
            val performed = office.operations.requested.toList()

            office.restart()

            office.operations.requested shouldBe performed
            office.readBack().ops(approval.id).withoutWakes() shouldBe listOf("worktree.create requested", "worktree.create failed interrupted")
            office.stage() shouldBe "assigned"
        }
    }

    "an invalid rules file leaves the old set in force and is reported; a valid one is in force from the next message, with its `rules` record" {
        val office = Office()
        val twoLines = """{"kind": "assignment-proposal", "stage": "$STAGE", "subject": "propose", "body": "one\ntwo", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}"""

        office.texts.edit("rules/engagement.yaml", "body_lines: 40", "body_lines: 1")
        val accepted = office.mailroom.reload()
        val underTheNewSet = office.send("teamlead", twoLines)
        office.texts.edit("rules/engagement.yaml", "caps:", "caps: [")
        val rejected = office.mailroom.reload()
        val underTheOldSet = office.send("teamlead", twoLines)

        office.readBack().rules().map { it.hash } shouldBe listOf(office.readBack().rules().first().hash, (accepted as Reload.Accepted).hash)
        underTheNewSet.requirement shouldBe "caps.body_lines: 1"
        (rejected as Reload.Rejected).problems.map { it.file }.toSet() shouldBe setOf("rules/engagement.yaml")
        underTheOldSet.requirement shouldBe "caps.body_lines: 1"
    }
})
