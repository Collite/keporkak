package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kotlin.time.Duration.Companion.hours

private const val PROPOSAL = """{"kind": "assignment-proposal", "stage": "$STAGE", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}"""

/** S1.9's slices in the mailroom (T3–T5): what S1.4 and S1.5 left open, below the T1 specs of `kp-daemon`. */
class RepairsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "T3 — the Deputy's queued approval answers the proposal: its delivery says so, the ticket is answered, nothing expires a day later" {
        val office = Office(walk = "H-2-prime", robots = true, at = "2026-11-13T22:00:00Z")
        office.bora("""{"kind": "queue-approval", "args": {"stages": ["$STAGE"]}}""")
        office.bora("""{"kind": "away"}""")
        val proposal = office.send("teamlead", PROPOSAL).id

        office.clock.advance(25.hours)
        office.mailroom.tick()

        val approval = office.readBack().sent("queued-approval").single()
        office.readBack().records<Delivered>().single { it.id == approval.id.value }.answers shouldBe proposal
        office.mailroom.state.tickets.values.single { it.item == proposal }.answeredBy shouldBe approval.id.value
        office.readBack().expired(proposal).shouldBeEmpty()
    }

    "T4 — a held message refused when it is let go: its sender is sent a nudge by the mailroom, in reply to it, with the rule and its guard" {
        val office = Office(walk = "H-2-prime", robots = true, at = "2026-11-13T22:00:00Z")
        office.bora("""{"kind": "away"}""")
        val held = office.send("teamlead", PROPOSAL)
        office.facts.set("list.fence_complete", false)

        office.command(signed = true)

        val nudge = office.readBack().sent("nudge").single()
        held.status shouldBe Status.HELD
        office.readBack().story().takeLast(3) shouldBe listOf("digest by robot:DEP-8 delivered", "assignment-proposal refused", "nudge delivered")
        listOf(nudge.from.value, nudge.to.value, nudge.re?.value, nudge.by, nudge.stage?.value) shouldBe listOf("mailroom", "teamlead", held.id, "mailroom", STAGE)
        nudge.plainArgs shouldBe mapOf("rule" to "assignment-proposal", "failed_guard" to "R4.8 predecessors exited · seat free · slots open · fence has SC ids + touches: (all observed)")
        office.readBack().records<Delivered>().single { it.id == nudge.id.value }.to shouldBe listOf("teamlead", "board")
    }

    "T4 — a held message delivered when it is let go, or one refused on arrival, is told no more than its tool result" {
        val office = Office(walk = "H-2-prime", robots = true, at = "2026-11-13T22:00:00Z")
        office.bora("""{"kind": "away"}""")
        office.send("teamlead", PROPOSAL)
        office.send("teamlead", PROPOSAL.replace("senior", "galactic").replace("\"seat\": \"coder-1\"", "\"seat\": \"coder-1\", \"colour\": \"blue\""))

        office.command(signed = true)

        office.readBack().story().filter { it.startsWith("assignment-proposal") } shouldBe listOf("assignment-proposal held by robot:DEP-1", "assignment-proposal refused", "assignment-proposal delivered")
        office.readBack().sent("nudge").shouldBeEmpty()
    }
})
