package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Held

/** S1.4 T5 — intercepts and holds (contracts.md §2.2, Amendment 8): what is kept, what is refused instead, and how it is let go. */
class HoldsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    fun absent() = Office(walk = "H-2-prime", seed = "passed", robots = true, at = "2026-11-13T22:00:00Z").apply { bora("""{"kind": "away"}""") }

    "a message the Deputy would keep whose args break its kind is refused, not held" {
        val office = absent()

        val asked = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?"}}""")

        asked.status shouldBe Status.REFUSED
        asked.requirement shouldBe "args: 'default' is required"
        office.readBack().records<Held>() shouldBe emptyList()
    }

    "what the Deputy kept is let go in the order it was kept, after the digest, and a merge only then meets its deadman" {
        val office = absent()

        val first = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "one?", "default": "follow-up"}}""")
        val merge = office.send("teamlead", """{"kind": "merge", "stage": "$STAGE"}""")
        val second = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "two?", "default": "follow-up"}}""")
        val passedWhileAway = office.stage()
        office.command(signed = true)

        passedWhileAway shouldBe "passed"
        office.readBack().story().dropWhile { it != "presence absent→present" } shouldBe listOf(
            "presence absent→present", "digest by robot:DEP-8 delivered", "classify-request delivered", "merge delivered", "classify-request delivered")
        office.readBack().records<kp.model.Delivered>().takeLast(3).map { it.id } shouldBe listOf(first.id, merge.id, second.id)
        office.stage() shouldBe "merged"
    }

    "a deploy the Deputy kept goes to QA's queue when it is let go, and waits there its turn" {
        val office = Office(walk = "H-3", others = mapOf("KP/P3/S3.1" to "building", "KP/P3/S3.3" to "released"), robots = true, at = "2026-11-13T22:00:00Z")
        office.facts.set("KP/P3/S3.1", "live.namespace_absent", false)
        office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}""")
        office.bora("""{"kind": "away"}""")

        val deploy = office.send("teamlead", """{"kind": "deploy-request", "stage": "KP/P3/S3.3", "args": {"pin": "image-head-1", "smoke": "the stage smoke"}}""")
        office.command(signed = true)

        office.readBack().records<Held>().filter { it.id == deploy.id }.map { it.by } shouldBe listOf("robot:DEP-15", "robot:QA-1")
        office.mailroom.state.held[deploy.id]?.by shouldBe "robot:QA-1"
    }

    "Bora's ruling on a question the Deputy kept answers its ticket once the question is delivered" {
        val office = absent()
        val asked = office.send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?", "default": "follow-up"}}""")
        office.command(signed = true)
        val open = office.mailroom.state.openTickets().values.map { it.item }

        office.bora("""{"kind": "ruling", "re": "${asked.id}", "args": {"outcome": "follow-up"}}""")

        open shouldBe listOf(asked.id)
        office.mailroom.state.openTickets() shouldBe emptyMap()
    }
})
