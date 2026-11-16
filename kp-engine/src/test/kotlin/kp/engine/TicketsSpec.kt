package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival

private const val NOTICE = "m-panda-000010"

/** S1.3, Amendment 13 (contracts.md §2.2): what closes a ticket, and that a ticket is answered once (§6). */
class TicketsSpec : StringSpec({
    val recut = fold(log(
        move("stage", STAGE, "planned", "recut"),
        """{"id":"$NOTICE","thread":"$NOTICE","from":"teamlead","to":"bora","kind":"recut-notice","stage":"$STAGE","subject":"recut","created":"$NOW","by":"seat:teamlead"}""",
        """{"event":"delivered","id":"$NOTICE","to":["bora"],"class":"B","ticket":2}""",
    ))
    fun ruling(id: String, signed: Boolean) =
        message("ruling", from = "bora", to = "teamlead", re = NOTICE, id = id, thread = NOTICE, args = """{"outcome": "recut"}""", signed = signed)

    "a signed delivery whose re names an item that does not wait closes its ticket; an unsigned reply leaves it open" {
        val signed = ruling("m-panda-000011", signed = true)
        val unsigned = message("pin-stage", from = "principal", to = "teamlead", re = NOTICE, id = "m-panda-000012", thread = NOTICE,
            args = """{"stage": "x", "reason": "y"}""")
        val facts = table("now" to NOW, "plan.stage_has_list(stage=x)" to true)

        val signedDecision = supervised().judge(recut, Arrival(signed), facts)
        val unsignedDecision = supervised().judge(recut, Arrival(unsigned), facts)
        val afterSigned = recut.recorded(signed, signedDecision)
        val afterUnsigned = recut.recorded(unsigned, unsignedDecision)

        listOf(signedDecision.outcome, unsignedDecision.outcome) shouldBe listOf(Outcome.DELIVER, Outcome.DELIVER)
        afterSigned.tickets.getValue(2L).answeredBy shouldBe "m-panda-000011"
        afterUnsigned.openTickets().keys shouldBe setOf(2L)
    }

    "a second signed answer to an item whose ticket is closed is refused: a ticket is answered once" {
        val first = ruling("m-panda-000011", signed = true)
        val answered = recut.recorded(first, supervised().judge(recut, Arrival(first), table("now" to NOW)))
        val second = ruling("m-panda-000012", signed = true)

        val decision = supervised().judge(answered, Arrival(second), table("now" to NOW))

        decision.refusal() shouldBe ("ruling" to "ticket #2: answered once")
        decision.failed shouldBe null
    }

    "a signed answer to an item with a ticket still open is judged by the rules alone" {
        val first = ruling("m-panda-000011", signed = true)

        supervised().judge(recut, Arrival(first), table("now" to NOW)).outcome shouldBe Outcome.DELIVER
    }
})
