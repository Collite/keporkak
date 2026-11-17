package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Fact
import kp.model.Message

/** S1.4 T3 — the robots host: what a robot hears, what it leaves on the log, and that a mailroom without it is S1.3's. */
class RobotsHostSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a fact that triggers a robot leaves its `fact` record first, then the robot's send, from its role and by its rule" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true)

        office.changed("seat.context_used", 0.8, stage = STAGE, params = mapOf("seat" to "coder-1"))

        val log = office.readBack()
        val fact = log.records<Fact>().single()
        listOf(fact.fact, fact.subject, fact.value.toString()) shouldBe listOf("seat.context_used", "coder-1", "0.8")
        val compact = log.sent("compact").single()
        listOf(compact.from.value, compact.by, compact.to.value, compact.stage?.value) shouldBe listOf("pool", "robot:POOL-3", "coder-1", STAGE)
        (compact.seq > fact.seq) shouldBe true
    }

    "a fact that triggers nothing leaves no line" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true)
        val before = office.readBack().lines.size

        office.changed("seat.context_used", 0.5, stage = STAGE, params = mapOf("seat" to "coder-1"))

        office.readBack().lines.size shouldBe before
    }

    "with the host off the mailroom is S1.3's: Bora away, a class-B question goes to Bora and no robot speaks" {
        val office = Office(walk = "H-2-prime", seed = "building")
        office.bora("""{"kind": "away"}""")

        val asked = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?", "default": "follow-up"}}""")

        asked.status shouldBe Status.DELIVERED
        office.readBack().messages.filter { it.by.startsWith("robot:") } shouldBe emptyList<Message>()
    }

    "a robot's sends are carried after the lines of what triggered them, in the order its rules fired" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        office.bora("""{"kind": "away"}""")

        val asked = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?", "default": "follow-up"}}""")

        val log = office.readBack()
        val hold = log.sent("hold").single()
        listOf(hold.re?.value, hold.thread.value, hold.to.value) shouldBe listOf(asked.id, asked.id, "reviewer-1")
        log.ops(asked.id) shouldBe listOf("seat.wake requested", "seat.wake ok")
    }
})
