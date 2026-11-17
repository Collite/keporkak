package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Op
import kp.model.plain

/** S1.4 T6 — wakes: a robot's `{wake: <mode>}` is an `op` with its mode, and `log.count … since: {wake: <mode>}` counts from it. */
class WakesSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "the 5th clean exit since the last stage-retro wakes the Retro in that mode; the 6th counts from that wake and does not" {
        val stages = (1..6).map { "KP/P4/S4.$it" }
        val office = Office(walk = "H-2-prime", others = stages.associateWith { "merged" }, robots = true)
        office.facts.set("status.entry_present", true)
        office.facts.set("status.entries", 5)

        val exits = stages.map { stage -> office.send("teamlead", """{"kind": "stage-exit", "stage": "$stage"}""").id }

        stages.map { office.stage(it) } shouldBe List(6) { "exited" }
        val wakes = office.readBack().records<Op>().filter { it.op == "seat.wake" && it.phase == "requested" }
        wakes.map { listOf(it.cause, it.plain()["with"]) } shouldBe listOf(listOf(exits[4], mapOf("role" to "retro", "mode" to "stage-retro", "rule" to "RET-2")))
        office.mailroom.state.wakes shouldBe mapOf("retro" to mapOf("stage-retro" to 5))
    }

    "an exit after a FIX FIRST wakes the Retro at once" {
        val office = Office(walk = "H-2-prime", seed = "merged", counters = """{"fix_rounds": 1}""", robots = true)
        office.facts.set("status.entry_present", true)
        office.facts.set("status.entries", 5)

        val exit = office.send("teamlead", """{"kind": "stage-exit", "stage": "$STAGE"}""")

        office.readBack().ops(exit.id).filter { it.startsWith("seat.wake") } shouldBe listOf("seat.wake requested", "seat.wake ok")
        office.readBack().records<Op>().single { it.op == "seat.wake" && it.phase == "requested" }.plain()["with"] shouldBe
            mapOf("role" to "retro", "mode" to "stage-retro", "rule" to "RET-1")
    }
})
