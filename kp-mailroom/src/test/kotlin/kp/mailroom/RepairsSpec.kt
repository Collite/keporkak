package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kp.model.Fact
import kp.model.Held
import kp.model.Refused
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

    "T5 — refusals.today is a seat's: Bora's and a robot's refusals are not reported, and pause no one; a seat's third is, and pauses it" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        val digest = "  - send: digest\n    to: bora\n    args: {}\n"
        office.texts.edit("robots/deputy.yaml", digest, digest + digest) // DEP-8's second digest of a return is refused
        office.restart()

        repeat(3) { office.bora("""{"kind": "decline", "stage": "$STAGE"}""", signed = false) }
        repeat(3) {
            office.bora("""{"kind": "away"}""")
            office.clock.advance(1.hours)
            office.command(signed = true)
        }
        repeat(3) { office.send("coder-1", """{"kind": "started", "stage": "$STAGE", "args": {"colour": "blue"}}""") }

        val refused = office.readBack().records<Refused>()
        refused.map { it.refusalsToday?.toInt() } shouldBe listOf(null, null, null, null, null, null, 1, 2, 3)
        refused.take(3).forEach { it should refusedBy("decline", "{signed: bora}") }
        refused.slice(3..5).forEach { it should refusedBy("digest", "max_fires: 1, window: return") }
        refused.drop(6).forEach { it should refusedBy("started", "args: 'colour' is not declared") }
        office.readBack().records<Fact>().filter { it.fact == "refusals.today" }.map { it.subject } shouldBe listOf("coder-1")
        office.readBack().sent("pause").map { it.to.value } shouldBe listOf("coder-1")
    }

    "T5 — a hold by QA's queue names the queue's own id, not one of its prose rules" {
        val office = Office(walk = "H-3", others = mapOf("KP/P3/S3.1" to "building"), robots = true)
        office.facts.set("KP/P3/S3.1", "live.namespace_absent", false)

        office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}""")

        office.readBack().records<Held>().map { it.by } shouldBe listOf("robot:QA-6")
    }

    "T5 — active robots are asked in the order of their files' names: QA's file renamed before the Deputy's, QA's queue takes a deploy while Bora is away, and it is refused at once" {
        // Renamed, a file goes to the end of the texts the rules are loaded from; `a-qa.yaml` is still first by name. Only the
        // order of the names puts QA's queue before the Deputy: a load that kept the texts' order would ask the Deputy first
        // (S2.7 T6, issue #34 — a spec that stands on the order it names, not on its control).
        fun deployWhileAway(qa: String?): List<String> {
            val office = Office(walk = "H-3", others = mapOf("KP/P3/S3.3" to "released"), robots = true, at = "2026-11-13T22:00:00Z")
            qa?.let { office.texts.rename("robots/qa.yaml", it) }
            office.restart()
            office.bora("""{"kind": "away"}""")
            office.send("teamlead", """{"kind": "deploy-request", "stage": "KP/P3/S3.3", "args": {"pin": "image-head-1", "smoke": "the stage smoke"}}""")
            return office.readBack().story().drop(2)
        }

        deployWhileAway(null) shouldBe listOf("deploy-request held by robot:DEP-15", "hold by robot:DEP-15 delivered")
        deployWhileAway("robots/a-qa.yaml") shouldBe listOf("deploy-request refused") // a deploy needs Bora present: the Deputy no longer keeps it for him
    }
})
