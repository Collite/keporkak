package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival
import kp.rules.MessageClass

private val passed = fold(log(move("stage", STAGE, "in_review", "passed")))
private val merge = Arrival(message("merge", from = "teamlead", to = "daemon"))
private val audit = arrayOf("now" to NOW, "pr.evidence_present" to true, "list.tasks_open" to 0, "project.proof_files" to 0, "list.found_untriaged" to 0, "git.stray_changes" to 0,
    "roster.rank(of=reviewer)" to 2, "roster.rank(of=coder)" to 1, "roster.model_rank(of=reviewer)" to 2, "roster.floor_rank" to 2, "seat.name(of=coder)" to "coder-1")
private val secondKeyHolds = table(*audit, "seat.name(of=reviewer)" to "reviewer-1")
private val sameSeatReviewed = table(*audit, "seat.name(of=reviewer)" to "coder-1")

private fun Decision.cls(): Pair<MessageClass?, Boolean> = messageClass to waitsForBora

/** T6: classes (contracts.md §4.2, §4.3; engagement.yaml: classes, second_key, autonomy_profiles). */
class ClassesSpec : StringSpec({
    "a kind's default class, when no case holds; class B waits for Bora" {
        val assigned = fold(log(move("stage", STAGE, "planned", "assigned")))
        val proposed = fold(log(move("stage", STAGE, "planned", "proposed")))
        val building = fold(log(move("stage", STAGE, "assigned", "building")))

        supervised().judge(assigned, Arrival(message("started", from = "coder-1")), table("now" to NOW, "git.branch_exists" to true)).cls() shouldBe (MessageClass.A to false)
        supervised().judge(building, Arrival(message("stop", from = "coder-1", to = "principal", args = """{"sc": "SC-3"}""")), table("now" to NOW, "list.stop_conditions" to listOf("SC-3"))).cls() shouldBe (MessageClass.C to false)
        supervised().judge(proposed, Arrival(message("decline", from = "bora"), signed = true), table("now" to NOW)).cls() shouldBe (MessageClass.B to true)
    }
    "a kind's first case that holds gives its class" {
        val stopped = fold(log(move("stage", STAGE, "building", "stopped")))
        fun triage(outcome: String) = supervised().judge(stopped, Arrival(message("triage", from = "principal", to = "coder", args = """{"outcome": "$outcome", "found": "F1", "since": "t0"}""")),
            table("now" to NOW, "list.sections_changed(since=t0)" to listOf("Found while doing"))).cls()

        triage("stage") shouldBe (MessageClass.C to false)
        triage("recut") shouldBe (MessageClass.B to true)
        triage("drop") shouldBe (MessageClass.A to false)
    }
    "the second key: a merge whose reviewer is the coder's own seat is class B, and waits for Bora" {
        supervised().judge(passed, merge, secondKeyHolds).cls() shouldBe (MessageClass.C to false)
        supervised().judge(passed, merge, sameSeatReviewed).cls() shouldBe (MessageClass.B to true)
    }
    "the autonomy profile in force sets a kind's class where no case holds; a case still decides where it holds" {
        val assisted = Engine(master, "assisted")

        assisted.judge(passed, merge, secondKeyHolds).cls() shouldBe (MessageClass.A to false)
        assisted.judge(passed, merge, sameSeatReviewed).cls() shouldBe (MessageClass.B to true)
    }
    "a profile's word that is not a class leaves the kind its default" {
        val planned = State()
        val proposal = Arrival(message("assignment-proposal", from = "teamlead", to = "bora", args = """{"role": "coder", "level": "senior", "seat": "coder-1"}"""))
        val facts = table("now" to NOW, "plan.predecessors_exited" to true, "pool.seat_available(level=senior, role=coder)" to true, "budget.slot_open(dimension=office)" to true,
            "budget.slot_open(dimension=subscription)" to true, "budget.slot_open(dimension=effort)" to true, "list.fence_complete" to true, "list.tasks_total" to 8, "list.lines" to 90)

        Engine(master, "autonomous").judge(planned, proposal, facts).cls() shouldBe (MessageClass.B to true)
    }
})
