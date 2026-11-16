package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Expired
import kp.model.Op
import kp.model.Delivered
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val STOP = """{"kind": "stop", "stage": "$STAGE", "subject": "stop", "args": {"sc": "SC-3"}}"""

/** T6 — deadlines and expiry: timers from the state, rebuilt at start; the `expired` record and what §4.2's forms say. */
class ExpiriesSpec : StringSpec({
    afterTest {
        Office.everyLogIsWhole() shouldBe emptyList()
        Office.closeAll()
    }

    "a deadline fires when the clock has passed it, not before; the next deadline says when" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", STOP)

        office.clock.advance(4.hours - 1.minutes)
        office.mailroom.tick()
        val early = office.readBack().expired(stop.id)
        office.clock.advance(1.minutes)
        val next = office.mailroom.nextDeadline()
        office.mailroom.tick()

        early shouldBe emptyList()
        next shouldBe Instant.parse("2026-11-16T13:00:00Z")
        office.readBack().expired(stop.id).map { it.at.value } shouldBe listOf("2026-11-16T13:00:00Z")
        office.mailroom.nextDeadline() shouldBe null
    }

    "the timers are the log's: a wait written before a restart fires after it" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", STOP)
        office.restart()

        office.clock.advance(4.hours)
        office.mailroom.tick()

        office.readBack().expired(stop.id).size shouldBe 1
    }

    "a class-B report to Bora is the inbox item: its expired record carries report, class and ticket, and notify.bora is requested for it" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", STOP)

        office.clock.advance(4.hours)
        office.mailroom.tick()

        val expired = office.readBack().expired(stop.id).single()
        listOf(expired.report, expired.deliveryClass, expired.ticket?.toLong(), expired.deadline) shouldBe listOf("bora", "B", expired.seq.toLong(), null)
        office.mailroom.state.openTickets().getValue(expired.seq.toLong()).item shouldBe stop.id
        office.readBack().records<Op>().map { "${it.op} ${it.phase} ${it.cause}" } shouldBe listOf("notify.bora requested #${expired.seq}", "notify.bora ok #${expired.seq}")
    }

    "an expiry's emit is by the expiry of its kind, in reply to the waiting message, judged and delivered like any other" {
        val office = Office(seed = "ready_for_review")
        val bind = office.send("teamlead", """{"kind": "review-bind", "stage": "$STAGE", "subject": "bind", "args": {"reviewer": "reviewer-1"}}""")

        office.clock.advance(8.hours)
        office.mailroom.tick()

        val nudge = office.readBack().sent("nudge").single()
        listOf(nudge.by, nudge.re?.value, nudge.thread.value, nudge.to.value, nudge.from.value) shouldBe listOf("expiry:review-bind", bind.id, bind.id, "teamlead", "mailroom")
        office.readBack().records<Delivered>().single { it.id == nudge.id.value }.woke shouldBe listOf("teamlead")
    }

    "an expiry of a kind whose expiry names no transition is recorded, and its emit is sent by the expiry of its kind" {
        val office = Office()
        val request = office.send("teamlead", """{"kind": "session-request", "subject": "a coder", "args": {"action": "add", "seat": "coder-2", "role": "coder"}}""")

        office.clock.advance(30.minutes)
        office.mailroom.tick()

        office.readBack().expired(request.id).map { it.deadline } shouldBe listOf(null)
        office.readBack().sent("nudge").map { "${it.by} ${it.to.value} ${it.re?.value}" } shouldBe listOf("expiry:session-request pool ${request.id}")
    }

    "an expired record that ends the wait writes its deadline as null" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", STOP)

        office.clock.advance(4.hours)
        office.mailroom.tick()

        office.readBack().records<Expired>().single().json["deadline"].toString() shouldBe "null"
        office.mailroom.state.deadlines.keys shouldBe emptySet()
        stop.status shouldBe Status.DELIVERED
    }
})
