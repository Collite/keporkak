package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.engine.fold
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Message
import kp.model.Op
import kp.model.Record

private const val APPROVAL = """{"kind": "approval", "stage": "$STAGE", "subject": "approved"}"""

/**
 * KP/P1/S1.10 — a judgement is whole. One spec per behaviour the stage list names (T1), at S1.3's seam: a message, a
 * result or a fact handed to the mailroom on a throwaway log — facts, operations and seats faked, master's rules — with
 * a writer the spec stops after a line it names, as a daemon killed there. What happened is read back from the log.
 * `Office`, `stopWriterAfter` and `judgements` are in MailroomSupport.kt; every spec's log is checked to end with a
 * `judged` line and to have every line in a judgement that one closes or one voids.
 */
class AJudgementIsWholeSpec : FreeSpec({
    afterTest { Office.checkAndCloseAll() }

    "everything one arrival produces — its record, its transitions, its requests, the messages the rules and the robots send in answer — ends with one judged line" {
        val office = Office(seed = "building", robots = true)

        office.play("review-request")
        office.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))

        val judgements = office.readBack().judgements()
        judgements shouldContain listOf("review-request by seat:coder-1", "delivered", "transition building→ready_for_review",
            "found-batch by effect:T-17", "delivered", "judged")
        judgements shouldContain listOf("fact seat.process_gone", "seat-gone by robot:POOL-1", "delivered", "transition ready_for_review→paused",
            "transition ready→gone", "op seat.reseat requested", "judged")
    }

    "a mailroom stopped between any two lines of a judgement starts with that judgement voided and its first line carried again, and ends where the uninterrupted one does" {
        val uninterrupted = Office(seed = "building", robots = true)
        uninterrupted.play("review-request")
        val stopped = Office(seed = "building", robots = true)
        stopped.stopWriterAfter { it is Delivered }

        shouldThrow<WriterStopped> { stopped.play("review-request") }
        stopped.restart()

        stopped.readBack().voidedJudgements() shouldBe listOf(listOf("review-request by seat:coder-1", "delivered"))
        stopped.readBack().judgements() shouldContain listOf("voided", "delivered", "transition building→ready_for_review",
            "found-batch by effect:T-17", "delivered", "judged")
        stopped.end() shouldBe uninterrupted.end()
    }

    "the fold of a log with a voided judgement equals the fold of the same log without the voided lines" {
        val office = Office(seed = "building", robots = true)
        office.stopWriterAfter { it is Delivered }
        shouldThrow<WriterStopped> { office.play("review-request") }
        office.restart()
        val log = office.readBack()

        log.voidedLines().map { it::class.simpleName } shouldBe listOf("Delivered")
        fold(log.lines) shouldBe fold(log.lines - log.voidedLines().toSet())
    }

    "a robot's send that was written and not judged is judged after the restart, once" {
        val uninterrupted = Office(seed = "building", robots = true)
        uninterrupted.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
        val stopped = Office(seed = "building", robots = true)
        stopped.stopWriterAfter { it is Message && it.kind == "seat-gone" }

        shouldThrow<WriterStopped> { stopped.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        stopped.restart()

        stopped.readBack().voidedJudgements() shouldBe listOf(listOf("fact seat.process_gone", "seat-gone by robot:POOL-1"))
        stopped.readBack().story() shouldBe listOf("seat-gone by robot:POOL-1 delivered", "re-seated by robot:POOL-14 delivered")
        stopped.end() shouldBe uninterrupted.end()
    }

    "a mailroom stopped with an operation in flight records it failed, interrupted, and performs nothing" {
        val office = Office(seed = "proposed")
        office.stopWriterAfter(theJudgedLineAfter("op worktree.create requested"))

        shouldThrow<WriterStopped> { office.bora(APPROVAL) }
        office.restart()

        val approval = office.readBack().sent("approval").single().id.value
        office.readBack().ops(approval) shouldBe listOf("worktree.create requested", "worktree.create failed interrupted")
        office.operations.requested.map { it.operation } shouldBe listOf("worktree.create")
    }
})

/** The `judged` line that closes the judgement in which a line told as [told] (`op worktree.create requested`) was written. */
private fun theJudgedLineAfter(told: String): (Envelope) -> Boolean {
    var seen = false
    return { line ->
        if (line is Op && "op ${line.op} ${line.phase}" == told) seen = true
        seen && (line as? Record)?.event == "judged"
    }
}
