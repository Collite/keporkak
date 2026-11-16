package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Rules
import kp.model.Delivered
import kp.model.Message
import kp.model.Op
import kp.model.Read

private const val BIND = """{"kind": "review-bind", "stage": "$STAGE", "subject": "bind", "args": {"reviewer": "reviewer-1"}}"""

/** T3 — routing: to and cc, who is woken and who finds it in the inbox, tickets and `notify.bora`, `read` records. */
class RoutingSpec : StringSpec({
    afterTest { Office.closeAll() }

    "the addressee and the kind's cc are delivered to, a role as the seat the stage binds to it; only the addressee is woken, and has then read it" {
        val office = Office(seed = "ready_for_review")

        val bind = office.send("teamlead", BIND)

        val delivered = office.readBack().records<Delivered>().single { it.id == bind.id }
        listOf(delivered.to, delivered.woke) shouldBe listOf(listOf("reviewer-1", "coder-1"), listOf("reviewer-1"))
        office.seats.woken shouldBe listOf("reviewer-1" to bind.id)
        office.readBack().records<Read>().map { it.seat to it.ids } shouldBe listOf("reviewer-1" to listOf(bind.id))
        office.mailroom.state.unread shouldBe mapOf("coder-1" to listOf(bind.id))
    }

    "a busy addressee is not woken: the message waits in its inbox, and the inbox that returns it records it read" {
        val office = Office(seed = "ready_for_review")
        office.seats.busy += "reviewer-1"
        val bind = office.send("teamlead", BIND)

        val first = office.mailroom.inbox("reviewer-1")
        val second = office.mailroom.inbox("reviewer-1")
        val everything = office.mailroom.inbox("reviewer-1", unread = false)

        office.readBack().records<Delivered>().single { it.id == bind.id }.woke shouldBe null
        office.seats.woken shouldBe emptyList()
        first.messages.map { it.id.value } shouldBe listOf(bind.id)
        second.messages shouldBe emptyList()
        everything.messages.map { it.id.value } shouldBe listOf(bind.id)
        office.readBack().records<Read>().map { it.seat to it.ids } shouldBe listOf("reviewer-1" to listOf(bind.id))
    }

    "an inbox returns at most its limit, oldest first, and says that more are left" {
        val office = Office(seed = "ready_for_review")
        office.seats.busy += "coder-1"
        office.facts.set("pr.head", "head-1")
        office.play("review-bind", "verdict")
        val (bind, verdict) = office.readBack().messages.map { it.id.value }

        val page = office.mailroom.inbox("coder-1", limit = 1)

        page.messages.map { it.json } shouldBe listOf(office.readBack().message(bind).json)
        page.more shouldBe true
        office.mailroom.state.unread("coder-1") shouldBe listOf(verdict)
    }

    "a seat is woken only once its message's lines are on disk" {
        val office = Office(seed = "ready_for_review")
        val onDisk = ArrayList<List<String>>()
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, office.operations, object : Deliveries {
            override fun idle(seat: String) = true
            override fun wake(seat: String, message: Message): Boolean {
                onDisk += office.readBack().lines.filter { it.seq.toLong() > 3 && it !is Rules }.map { it::class.simpleName!! }
                return true
            }
        }, office.clock)

        office.send("teamlead", BIND)

        onDisk shouldBe listOf(listOf("Message", "Delivered", "Transition"))
    }

    "a class-B delivery raises a ticket — the seq of the record that carries it — and requests notify.bora for it; a party is never woken" {
        val office = Office()

        val proposal = office.send("teamlead", """{"kind": "assignment-proposal", "stage": "$STAGE", "subject": "propose", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}""")

        val delivered = office.readBack().records<Delivered>().single { it.id == proposal.id }
        listOf(delivered.deliveryClass, delivered.ticket?.toLong(), delivered.woke) shouldBe listOf("B", delivered.seq.toLong(), null)
        office.mailroom.state.openTickets().keys shouldBe setOf(delivered.seq.toLong())
        office.readBack().records<Op>().map { "${it.op} ${it.phase} ${it.cause}" } shouldBe
            listOf("notify.bora requested #${delivered.seq}", "notify.bora ok #${delivered.seq}")
        office.operations.requested.single().with.json() shouldBe """{"ticket":${delivered.seq},"item":"${proposal.id}"}"""
    }

    "a class-A delivery raises no ticket and notifies no one" {
        val office = Office(seed = "ready_for_review")

        val bind = office.send("teamlead", BIND)

        office.readBack().records<Delivered>().single { it.id == bind.id }.ticket shouldBe null
        office.operations.requested shouldBe emptyList()
    }
})
