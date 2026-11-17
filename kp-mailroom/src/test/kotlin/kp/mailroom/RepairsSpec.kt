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
})
