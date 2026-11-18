package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.model.Op
import kotlin.concurrent.thread

private const val APPROVAL = """{"kind": "approval", "stage": "$STAGE", "subject": "approved"}"""

/** T5 — operations (contracts.md §7): requested, then `ok` or `failed`, each a record; the result is a trigger for the engine. */
class OperationsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "an operation is performed only once its request is on disk, after the lines of the judgement that requested it" {
        val office = Office(seed = "proposed")
        val onDisk = ArrayList<List<String>>()
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, { request, done ->
            onDisk += office.readBack().lines.dropWhile { it !is kp.model.Message }.map { (it as? Op)?.let { op -> "${op.op} ${op.phase}" } ?: it::class.simpleName!! }
            done(OperationResult.Ok())
        }, office.seats, office.clock)

        office.bora(APPROVAL)

        // Bora's own approval waits for no one: no notify.bora (S1.8, contracts.md §2.2). The judgement is closed — and
        // only then are its operation performed and its addressee woken, in the order it asked for them (S1.10).
        onDisk.first() shouldBe listOf("Message", "Delivered", "Transition", "worktree.create requested", "seat.wake requested", "Judged")
    }

    "a failed result is recorded with its reason and judged: nothing fires for worktree.create failed, and the stage stays assigned" {
        val office = Office(seed = "proposed")
        office.operations.failing["worktree.create"] = "the branch exists"

        val approval = office.bora(APPROVAL)

        office.readBack().ops(approval.id).withoutWakes() shouldBe listOf("worktree.create requested", "worktree.create failed the branch exists")
        office.readBack().transitions(approval.id) shouldBe listOf("T-02 proposed→assigned")
        office.stage() shouldBe "assigned"
    }

    "a result given later, from the performer's own thread, is recorded and judged then" {
        val office = Office(seed = "proposed")
        office.operations.holding = true
        val later = ArrayList<(OperationResult) -> Unit>()
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, { _, done -> later += done }, office.seats, office.clock)
        val approval = office.bora(APPROVAL)
        val before = office.readBack().transitions(approval.id)

        thread { later.first()(OperationResult.Ok()) }.join()

        before shouldBe listOf("T-02 proposed→assigned")
        office.readBack().transitions(approval.id) shouldBe listOf("T-02 proposed→assigned", "T-03 assigned→assigned")
    }

    "a result given twice is taken once" {
        val office = Office(seed = "proposed")
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, { _, done -> done(OperationResult.Ok()); done(OperationResult.Failed("again")) },
            office.seats, office.clock)

        val approval = office.bora(APPROVAL)

        office.readBack().ops(approval.id).withoutWakes() shouldBe listOf("worktree.create requested", "worktree.create ok")
    }

    "a retry is another firing of the kind, and the kind's cap counts it: a second approval is refused, nothing is requested again" {
        val office = Office(seed = "proposed")
        office.operations.failing["worktree.create"] = "the branch exists"
        office.bora(APPROVAL)

        val retry = office.bora(APPROVAL)

        listOf(retry.status, retry.requirement) shouldBe listOf(Status.REFUSED, "max_fires: 1, window: stage")
        office.operations.requested.map { it.operation } shouldBe listOf("worktree.create")
    }

    "seat.start names its seat in with.seat, and in result.seat when ok" {
        val office = Office()
        office.facts.set("roster.model_allowed", true)
        val request = office.send("teamlead", """{"kind": "session-request", "subject": "a coder", "args": {"action": "add", "seat": "coder-2", "role": "coder", "level": "senior"}}""")

        office.send("pool-1", """{"kind": "session-report", "re": "${request.id}", "subject": "coder-2", "args": {"seat": "coder-2", "role": "coder"}}""")

        val ops = office.readBack().records<Op>().filter { it.op == "seat.start" }
        ops.map { it.phase } shouldBe listOf("requested", "ok")
        ops.map { it.with?.get("seat").toString() + " " + it.result?.get("seat") } shouldBe listOf("\"coder-2\" null", "null \"coder-2\"")
        office.mailroom.state.sessions.keys shouldBe setOf("coder-2")
    }

    "seat.reseat keeps the seat its performer names, and names the one it was asked for when the performer names none" {
        val office = Office(seed = "building")
        office.facts.set("seat.process_gone", true)
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, { request, done ->
            done(OperationResult.Ok(Datum.Fields(mapOf("seat" to Datum.Text("coder-1"), "session" to Datum.Text("s-2")))))
        }, office.seats, office.clock)

        val gone = office.send("pool-1", """{"kind": "seat-gone", "stage": "$STAGE", "subject": "coder-1 is gone", "args": {"seat": "coder-1"}}""")

        office.readBack().records<Op>().single { it.op == "seat.reseat" && it.cause == gone.id && it.phase == "ok" }.result.toString() shouldBe """{"seat":"coder-1","session":"s-2"}"""
        office.readBack().records<Op>().single { it.op == "seat.reseat" && it.cause == gone.id && it.phase == "requested" }.with.toString() shouldBe """{"seat":"coder-1"}"""
    }
})
