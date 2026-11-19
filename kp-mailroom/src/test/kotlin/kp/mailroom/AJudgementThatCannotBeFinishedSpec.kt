package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.engine.Datum
import kp.engine.FactRef
import kp.model.Op

private const val QA_STAGE = "KP/P3/S3.1"
private const val QA_REQUEST = """{"kind": "qa-request", "stage": "$QA_STAGE", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}"""

/**
 * S2.9 T4 — a judgement that cannot be finished (contracts.md §3, Amendments 25 and 29; issue #45, S2.7's Found line):
 * it drops what it had queued for the world — nothing of it is performed and no one it would have woken is woken, then
 * or after the next arrival — and when the arrival has no sender — a result, an expiry, a fact — its error goes to the
 * daemon's journal and to no caller: a sender is answered for its own message.
 */
class AJudgementThatCannotBeFinishedSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a result that cannot be judged, arriving while a seat's own message is being answered: that seat gets its message's answer, the error is in the journal, and nothing the failed judgement queued is performed" {
        // QA's queue keeps a second job while the first runs: every judgement ends by asking it whether to let it go, and it
        // asks the world whether the live namespace is free. Once the start of coder-3 is performed, the world cannot say, once.
        val office = Office(walk = "H-3", others = mapOf(QA_STAGE to "building"), robots = true)
        office.facts.set(QA_STAGE, "live.namespace_absent", false)
        office.facts.set("live.namespace_absent", false)
        office.send("coder-1", QA_REQUEST)
        office.send("coder-1", QA_REQUEST).status shouldBe Status.HELD
        office.facts.set("budget.slot_open", true)
        office.facts.set("roster.model_allowed", mapOf("coder-3" to true))
        val request = office.send("teamlead", """{"kind": "session-request", "stage": "$STAGE", "args": {"action": "add", "role": "coder", "seat": "coder-3"}}""")
        var started = false
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts,
            FactSource { fact, stage -> if (started && fact.name == "live.namespace_absent") { started = false; error("the world cannot say") } else office.facts.observe(fact, stage) },
            Operations { asked, done -> if (asked.operation == "seat.start") started = true; office.operations.perform(asked, done) },
            office.seats, office.clock, robots = true, journal = office.journal::add)

        // The session-report requests coder-3's start; its result arrives while the report is being answered, and its
        // judgement — which asks for coder-3's token — cannot be finished.
        val report = office.send("pool", """{"kind": "session-report", "stage": "$STAGE", "re": "${request.id}", "args": {"seat": "coder-3", "role": "coder"}}""")

        report.status shouldBe Status.DELIVERED
        office.journal.single() shouldContain "the result of seat.start for ${report.id} could not be judged"
        office.operations.requested.filter { it.cause == report.id }.map { it.operation } shouldBe listOf("seat.start")
        office.readBack().counted.filterIsInstance<Op>().filter { it.cause == report.id && it.op != "seat.wake" }.map { "${it.op} ${it.phase}" } shouldBe listOf("seat.start requested")
        // Nor at the next arrival: coder-3's token was never asked for.
        office.send("teamlead", """{"kind": "session-request", "stage": "$STAGE", "args": {"action": "add", "role": "coder", "seat": "coder-4"}}""")
        office.operations.requested.filter { it.cause == report.id }.map { it.operation } shouldBe listOf("seat.start")
    }

    "a judgement that cannot be finished wakes no one it would have woken, then or after the next arrival" {
        // The TeamLead asks the Pool Manager to remove coder-1: the request wakes the pool's idle seat, and POOL-8's retire,
        // which the judgement carries, cannot say whom it goes to.
        val office = Office(seed = "building", robots = true, seatStates = mapOf("coder-1" to "idle"))
        office.facts.set("budget.slots_over", mapOf("office" to 1))
        var failing = true
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts,
            FactSource { fact, stage -> if (failing && fact.name == "seat.name" && fact.params["of"] == Datum.Text("coder-1")) error("the world cannot say") else office.facts.observe(fact, stage) },
            office.operations, office.seats, office.clock, robots = true, journal = office.journal::add)
        val remove = """{"kind": "session-request", "stage": "$STAGE", "args": {"action": "remove", "seat": "coder-1"}}"""

        shouldThrow<IllegalStateException> { office.send("teamlead", remove) }

        office.seats.woken shouldBe emptyList()
        office.operations.requested shouldBe emptyList()
        failing = false
        val next = office.send("teamlead", remove)
        office.seats.woken.filter { it.first == "pool-1" } shouldBe listOf("pool-1" to next.id)
    }

    "a fact that cannot be judged goes to the journal and to no caller" {
        val office = Office(seed = "building", robots = true)
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts,
            FactSource { fact, stage -> if (fact.name == "seat.tool_call_open_for") error("the world cannot say") else office.facts.observe(fact, stage) },
            office.operations, office.seats, office.clock, robots = true, journal = office.journal::add)

        office.mailroom.changed(FactRef("seat.process_gone", mapOf("seat" to Datum.Text("coder-1"))), Datum.Bool(true), STAGE)

        office.journal.single() shouldContain "the fact seat.process_gone coder-1 could not be judged"
        office.readBack().story() shouldBe emptyList()
    }
})
