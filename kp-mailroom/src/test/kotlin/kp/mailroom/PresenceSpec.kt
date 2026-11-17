package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.engine.FactRef
import kp.model.Fact

/** Bora's presence as the log has it: a subject no record has moved is in its machine's first state, `present` (contracts.md §2.2). */
private fun Office.presence(): String = mailroom.state.presence("bora") ?: "present"

/** S1.4 T4 — presence: moved by `bora.acted` and `bora.idle_for` through their `on: fact` transitions, and only a signed command acts. */
class PresenceSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "Bora idle past the engagement's hours is absent; a signed command brings him back; an unsigned one does not" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true)

        office.changed("bora.idle_for", "3h")
        val afterThreeHours = office.presence()
        office.changed("bora.idle_for", "4h")
        val afterFour = office.presence()
        office.command(signed = false)
        office.bora("""{"kind": "decline", "stage": "$STAGE"}""", signed = false)
        val afterUnsigned = office.presence()
        office.command(signed = true)

        listOf(afterThreeHours, afterFour, afterUnsigned, office.presence()) shouldBe listOf("present", "absent", "absent", "present")
        office.readBack().records<Fact>().map { "${it.fact} ${it.value}" } shouldBe listOf("bora.idle_for \"4h\"", "bora.acted true")
    }

    "a signed message from Bora is a command: he is back before it is judged" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        office.bora("""{"kind": "away"}""")

        office.bora("""{"kind": "queue-approval", "args": {"stages": ["$STAGE"]}}""")

        office.readBack().story() shouldBe listOf("away delivered", "presence present→absent", "presence absent→present", "digest by robot:DEP-8 delivered", "queue-approval delivered")
    }

    "`bora.acted` is no fact the world reports: only a signed command is one" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true)

        shouldThrow<IllegalArgumentException> { office.mailroom.changed(FactRef("bora.acted"), Datum.Bool(true)) }
        office.presence() shouldBe "present"
    }

    "a robot's `active_when` follows presence: the Deputy holds while Bora is absent and not once he is back" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        val question = """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?", "default": "follow-up"}}"""

        office.bora("""{"kind": "away"}""")
        val whileAway = office.send("reviewer-1", question)
        office.command(signed = true)
        val whenBack = office.send("reviewer-1", question)

        listOf(whileAway.status, whenBack.status) shouldBe listOf(Status.HELD, Status.DELIVERED)
    }
})
