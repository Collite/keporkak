package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kp.engine.fold
import kp.model.toLine
import kotlin.time.Duration.Companion.hours

private const val QA_STAGE = "KP/P3/S3.1"
private const val QA_REQUEST = """{"kind": "qa-request", "stage": "$QA_STAGE", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}"""

/**
 * S2.7 T7 — a judgement's edges (contracts.md §3, Amendment 25; issue #39, items 1 and 2): a judgement that cannot be
 * finished writes nothing and leaves the state the fold of what is on disk; every way in turns to the world after its
 * judgement. Item 5 — every line belongs to a judgement — is checked after `kp-daemon`'s specs as after these.
 */
class AJudgementsEdgesSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "an arrival that fails inside its judgement writes no line, the state is the fold of what is on disk, and the next arrival's judgement holds its own lines only" {
        val office = Office(seed = "assigned")
        var failing = true
        office.stop()
        // Whom the message goes to is asked of the world after its line is written: the arrival fails inside its judgement.
        office.mailroom = Mailroom.start(office.project, panda, office.texts,
            FactSource { fact, stage -> if (failing && fact.name == "seat.name") error("the world cannot say") else office.facts.observe(fact, stage) },
            office.operations, office.seats, office.clock)
        val onDisk = office.readBack().lines
        val before = office.mailroom.state

        shouldThrow<IllegalStateException> { office.send("teamlead", """{"kind": "assign", "stage": "$STAGE", "args": {"seat": "coder-1"}}""") }

        office.readBack().lines.map { it.toLine() } shouldBe onDisk.map { it.toLine() }
        office.mailroom.state shouldBe fold(onDisk)
        office.mailroom.state shouldBe before
        failing = false
        val next = office.send("teamlead", """{"kind": "assign", "stage": "$STAGE", "args": {"seat": "coder-1"}}""")
        next.id shouldBe "m-panda-%06d".format(onDisk.size + 1)
        office.readBack().judgements().filter { "assign by seat:teamlead" in it } shouldBe listOf(listOf("assign by seat:teamlead", "delivered", "transition ready→busy", "op seat.wake requested", "judged"))
        office.mailroom.state shouldBe fold(office.readBack().lines)
    }

    "inbox turns to the world after its judgement: what a release in it asked for is done then, not at the next arrival" {
        val office = Office(walk = "H-3", others = mapOf(QA_STAGE to "building"), robots = true)
        office.seats.busy += "coder-1"
        val job = waitingInQasQueue(office) // and the first job's nudge waits, unread, for the busy coder-1

        office.mailroom.inbox("coder-1") // its `read` judgement asks the robots what they let go

        office.readBack().story().count { it == "qa-request delivered" } shouldBe 2
        office.seats.woken shouldContain ("qa" to job.id)
    }

    "an accepted reload turns to the world after its judgement: what a release in it asked for is done then, not at the next arrival" {
        val office = Office(walk = "H-3", others = mapOf(QA_STAGE to "building"), robots = true)
        val job = waitingInQasQueue(office)

        office.mailroom.reload().shouldBeAccepted()

        office.readBack().story().count { it == "qa-request delivered" } shouldBe 2
        office.seats.woken shouldContain ("qa" to job.id)
    }
})

/**
 * A second QA job kept by QA's queue, free to go: the first was handed over and its wait has expired — QA no longer has
 * it — and the live namespace is gone, as the world now says without reporting it. Nothing has asked the queue since.
 */
private fun waitingInQasQueue(office: Office): Answer {
    namespaceAbsent(office, false)
    val first = office.send("coder-1", QA_REQUEST)
    val job = office.send("coder-1", QA_REQUEST)
    office.clock.advance(3.hours)
    office.mailroom.tick()
    namespaceAbsent(office, true)
    office.readBack().story().count { it == "qa-request delivered" } shouldBe 1 // the first: held, and let go in its own judgement
    listOf(first.status, job.status) shouldBe listOf(Status.HELD, Status.HELD)
    office.seats.woken shouldNotContain ("qa" to job.id)
    return job
}

/** The live namespace of QA's stage, as the world has it — asked about the job's stage and, before any job, about none. */
private fun namespaceAbsent(office: Office, absent: Boolean) {
    office.facts.set(QA_STAGE, "live.namespace_absent", absent)
    office.facts.set("live.namespace_absent", absent)
}

private fun Reload.shouldBeAccepted() = (this is Reload.Accepted) shouldBe true
