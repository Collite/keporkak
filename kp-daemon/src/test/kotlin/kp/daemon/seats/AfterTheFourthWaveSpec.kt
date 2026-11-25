package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kp.daemon.refusedBy
import kp.mailroom.Status

/**
 * KP/P3/S3.14 T1 — after the fourth wave's reviews (contracts.md §4.4, Amendment 47; §2.2). Bora reads these. The seam: a
 * daemon composed as the service composes it — the project's, GitHub's and the pool's fact sources, no walk's table — with
 * S2.1's fake agent as every seat's process, its seats started as the rules start them, asked as the mailroom asks
 * ([ServiceOffice]). Its stages: S3.1 ready for review, S3.2 assigned, S3.3 waiting for a grant. The office check's sentence is in
 * `tests/test_s3_14_after_the_fourth_wave.py`.
 */
class AfterTheFourthWaveSpec : StringSpec({
    "a message addressed to a role, and one copied to it, reaches the seat last started for that role in that stage and not retired — one seat, a re-seated one, two stages with a coder each; with no such seat the role stays as written and nothing is woken" {
        ServiceOffice(STAGES, budgets = FOUR_IN_FLIGHT).use { office ->
            office.started("coder-1", "coder", S31, level = "junior")
            office.started("reviewer-1", "reviewer", S31)
            office.started("coder-2", "coder", S32, level = "junior")

            // Addressed to the reviewer, copied to the coder: T-18 reads `seat.name` of both, and each wakes its own seat.
            val bind = office.send("teamlead", "review-bind", """{"reviewer": "reviewer-1"}""", S31)
            listOf(bind.status, bind.failed?.json()) shouldBe listOf(Status.DELIVERED, null)
            office.observed(bind.id)["seat.name(of=reviewer-1)"] shouldBe "\"reviewer-1\""
            office.observed(bind.id)["seat.name(of=coder)"] shouldBe "\"coder-1\""
            office.delivered(bind.id).to shouldBe listOf("reviewer-1", "coder-1")
            office.delivered(bind.id).woke shouldBe listOf("reviewer-1", "coder-1")

            // Two stages with a coder each: S3.2's coder is woken, S3.1's is not.
            val assign = office.send("teamlead", "assign", """{"seat": "coder-2"}""", S32)
            office.delivered(assign.id).to shouldBe listOf("coder-2")
            office.until("coder-2 prompted with the assign") { office.prompts("coder-2").any { assign.id in it.second } }
            office.prompts("coder-1").map { it.second }.none { assign.id in it } shouldBe true

            // No seat in S3.3: a grant to its reviewer, copied to its coder, keeps each role as written, and wakes nothing.
            val none = office.send("principal", "grant", """{"outcome": "grant"}""", S33)
            office.delivered(none.id).to shouldBe listOf("reviewer", "coder", "teamlead")
            office.delivered(none.id).woke shouldBe null

            // coder-2's process dies and the rules re-seat it; the Pool Manager's `re-seated`, copied to S3.2's coder, wakes its new process.
            val first = office.agents.pid("coder-2")
            office.seats.end("coder-2")
            office.until("coder-2 re-seated, and re-seated sent") { office.messages("re-seated").isNotEmpty() }
            val reseated = office.messages("re-seated").single()
            office.delivered(reseated).to shouldBe listOf("teamlead", "coder-2")
            office.until("coder-2's new process prompted with re-seated") { office.prompts("coder-2").any { reseated in it.second } }
            office.prompts("coder-2").single { reseated in it.second }.first shouldNotBe first
        }
    }

    "`seat.name`, `seat.role`, `seat.model` and `seat.effort` of a role in a stage are those of that seat, and unknown with the role and the stage when there is none" {
        ServiceOffice(STAGES, budgets = FOUR_IN_FLIGHT).use { office ->
            office.started("coder-1", "coder", S31, level = "junior")
            office.started("reviewer-1", "reviewer", S31)

            office.ask("seat.name", "of" to "coder", stage = S31) shouldBe "coder-1"
            office.ask("seat.role", "of" to "coder", stage = S31) shouldBe "coder"
            office.ask("seat.model", "of" to "coder", stage = S31) shouldBe "claude-haiku-5-5"
            office.ask("seat.effort", "of" to "coder", stage = S31) shouldBe "medium"
            office.ask("seat.name", "of" to "reviewer", stage = S31) shouldBe "reviewer-1"
            office.ask("seat.model", "of" to "reviewer", stage = S31) shouldBe "claude-opus-5-5"
            office.ask("seat.effort", "of" to "reviewer", stage = S31) shouldBe "medium"

            office.ask("seat.name", "of" to "coder", stage = S33) shouldBe null
            office.ask("seat.effort", "of" to "reviewer", stage = S32) shouldBe null
            office.journal shouldContain "facts: seat.name(of=coder) for KP/P3/S3.3 is unknown — no seat is bound to the role coder in KP/P3/S3.3"
            office.journal shouldContain "facts: seat.effort(of=reviewer) for KP/P3/S3.2 is unknown — no seat is bound to the role reviewer in KP/P3/S3.2"
        }
    }

    "a `session-report` for a seat not yet started is judged with `roster.model_allowed` known — true for a model that is not reserved, false for one reserved for another role — and SEAT-01 then reads the slots" {
        // The Team Lead asks for a seat by level, as a day's request about no stage; the Pool Manager reports on the stage.
        // One seat per office: two coders at junior — claude-haiku-5-5, reserved for no role.
        ServiceOffice(mapOf(S32 to "assigned"), budgets = { it.replace("per_office: {panda: 4,", "per_office: {panda: 1,") }).use { office ->
            val first = office.request("coder-1", "coder", stage = null, level = "junior")
            val second = office.request("coder-2", "coder", stage = null, level = "junior")

            val delivered = office.report("coder-1", "coder", S32, re = first)
            listOf(delivered.status, delivered.failed?.json()) shouldBe listOf(Status.DELIVERED, null)
            office.observed(delivered.id)["roster.model_allowed(seat=coder-1)"] shouldBe "true"
            office.observed(delivered.id)["budget.slot_open(dimension=office)"] shouldBe "true"

            // With coder-1 in the one slot: refused at the limit, naming the slot — not for an unknown.
            val full = office.report("coder-2", "coder", S32, re = second)
            full should refusedBy("SEAT-01", """{"fact":"budget.slot_open","dimension":"office"}""")
            office.observed(full.id)["roster.model_allowed(seat=coder-2)"] shouldBe "true"
            office.ops("seat.start").map { it.with?.get("seat").toString() } shouldNotContain "\"coder-2\""
        }
        // A fixture pool: the coder is put on claude-fable-5-2, reserved for the Principal and the coder — so the reviewer,
        // whose level is at or above the coder's, would run it too.
        val fable = { roster: String ->
            roster.replace("  claude-fable-5-2: [principal]", "  claude-fable-5-2: [principal, coder]")
                .replace("role_models: {}", "role_models: {coder: {model: claude-fable-5-2, effort: high}}")
        }
        ServiceOffice(mapOf(S32 to "assigned"), roster = fable).use { office ->
            val coder = office.request("coder-1", "coder", stage = null)
            val reviewer = office.request("reviewer-1", "reviewer", stage = null)

            office.report("coder-1", "coder", S32, re = coder).status shouldBe Status.DELIVERED
            val refused = office.report("reviewer-1", "reviewer", S32, re = reviewer)
            refused should refusedBy("SEAT-01", """{"fact":"roster.model_allowed","seat":{"arg":"seat"}}""")
            office.observed(refused.id)["roster.model_allowed(seat=reviewer-1)"] shouldBe "false"
        }
    }
})

private const val S31 = "KP/P3/S3.1"
private const val S32 = "KP/P3/S3.2"
private const val S33 = "KP/P3/S3.3"

/** The three stages: S3.1 ready for review, S3.2 assigned, S3.3 waiting for the Principal's grant. */
private val STAGES = mapOf(S31 to "ready_for_review", S32 to "assigned", S33 to "grant_pending")

/** Master's budgets with four stages of an effort in flight, not two: a seat can be asked for while the three are. */
private val FOUR_IN_FLIGHT = { budgets: String -> budgets.replace("stages_in_flight_per_effort: 2", "stages_in_flight_per_effort: 4") }
