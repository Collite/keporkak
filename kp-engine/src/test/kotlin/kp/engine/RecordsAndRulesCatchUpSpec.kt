package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival
import kp.model.Message
import kp.model.Refused
import kp.model.parseLine
import kp.model.plain

private const val PROPOSAL = "m-panda-000010"
private const val REVIEW = "m-panda-000020"

/**
 * S1.7's five behaviours, at the engine's `judge` and the fold, on this stage's rule set. What the mailroom would write
 * for a decision is written as lines, read back through kp-model — which checks each against contracts.md §2 — and
 * folded.
 */
class RecordsAndRulesCatchUpSpec : StringSpec({
    "a reply of a kind the waiting kind names closes the wait and its ticket; a reply of another kind leaves both open" {
        val proposed = fold(log(move("stage", STAGE, "planned", "proposed"),
            """{"id":"$PROPOSAL","thread":"$PROPOSAL","from":"teamlead","to":"bora","kind":"assignment-proposal","stage":"$STAGE","subject":"p","deadline":"2026-11-16T09:00:00Z","created":"$NOW","by":"seat:teamlead"}""",
            """{"event":"delivered","id":"$PROPOSAL","to":["bora"],"class":"B","ticket":3}"""))
        val decline = message("decline", from = "bora", re = PROPOSAL, id = "m-panda-000011", thread = PROPOSAL, signed = true)
        // Unsigned: since Amendment 13 a signed delivery whose `re` names the item closes its ticket, whatever its kind (TicketsSpec).
        val pin = message("pin-stage", from = "principal", to = "teamlead", re = PROPOSAL, id = "m-panda-000012", thread = PROPOSAL, args = """{"stage": "x", "reason": "y"}""")

        val declined = supervised().judge(proposed, Arrival(decline), table("now" to NOW))
        val pinned = supervised().judge(proposed, Arrival(pin), table("now" to NOW, "plan.stage_has_list(stage=x)" to true))

        declined.answers shouldBe PROPOSAL
        proposed.recorded(decline, declined).deadlines.keys shouldBe emptySet()
        proposed.recorded(decline, declined).openTickets().keys shouldBe emptySet()
        pinned.outcome shouldBe Outcome.DELIVER
        pinned.answers shouldBe null
        proposed.recorded(pin, pinned).deadlines.keys shouldBe setOf(PROPOSAL)
        proposed.recorded(pin, pinned).openTickets().keys shouldBe setOf(3L)
    }

    "a refusal carries the rule's guard in the rules' words and, beside it, the one requirement that failed" {
        val stopped = fold(log(move("stage", STAGE, "building", "stopped", extra = ""","counted":{"resumes":2}""")))
        val resume = message("triage", from = "principal", to = "coder", args = """{"outcome": "resume", "since": "2026-11-15T09:00:00Z"}""")
        val facts = table("now" to NOW, "list.sections_changed(since=2026-11-15T09:00:00Z)" to listOf("Found while doing"), "list.tasks_total" to 8)

        val refused = parseLine(stamped(refusedRecord(supervised().judge(stopped, Arrival(resume), facts), resume.id.value))) as Refused

        refused.rule shouldBe "T-13"
        refused.requirement shouldBe "outcome=resume · task-count diff within R4.11 · no edit outside the Found section (observed) · counter resumes ≤ 2, the 3rd needs Bora"
        refused.plain()["failed"] shouldBe mapOf("counter" to "resumes", "lt" to mapOf("cap" to "resumes_per_stage"))
    }

    "a signed message read back from the log is judged as it was; the same message unsigned is refused" {
        val proposed = fold(log(move("stage", STAGE, "planned", "proposed")))
        val signed = parseLine("""{"v":1,"seq":9,"at":"$NOW","id":"m-panda-000011","thread":"m-panda-000011","from":"bora","to":"teamlead","kind":"decline","stage":"$STAGE","subject":"no","created":"$NOW","signed":true,"by":"bora-cli"}""") as Message
        val unsigned = parseLine("""{"v":1,"seq":9,"at":"$NOW","id":"m-panda-000011","thread":"m-panda-000011","from":"bora","to":"teamlead","kind":"decline","stage":"$STAGE","subject":"no","created":"$NOW","by":"bora-cli"}""") as Message

        supervised().judge(proposed, Arrival(signed), table("now" to NOW)).rule shouldBe "T-06"
        supervised().judge(proposed, Arrival(unsigned), table("now" to NOW)).refusal() shouldBe ("decline" to "signed by Bora's CLI")
    }

    "an expiry that says the wait goes on gives it a new deadline; at the cap the wait is over" {
        val waiting = fold(log(move("stage", STAGE, "building", "ready_for_review"),
            """{"id":"$REVIEW","thread":"$REVIEW","from":"coder-1","to":"teamlead","kind":"review-request","stage":"$STAGE","subject":"r","deadline":"2026-11-15T10:00:00Z","created":"$NOW","by":"seat:coder-1"}""",
            """{"event":"delivered","id":"$REVIEW","to":["teamlead"],"class":"A"}"""))

        val first = supervised().judge(waiting, Trigger.Expiry(REVIEW), table("now" to "2026-11-15T10:00:00Z"))
        val once = waiting.expired(REVIEW, first)
        val second = supervised().judge(once, Trigger.Expiry(REVIEW), table("now" to "2026-11-15T11:00:00Z"))
        val twice = once.expired(REVIEW, second)

        first.deadline shouldBe "2026-11-15T11:00:00Z"
        once.deadlines.getValue(REVIEW).deadline shouldBe "2026-11-15T11:00:00Z"
        second.deadline shouldBe null
        twice.deadlines shouldBe emptyMap()
    }

    "a message addressed to a party its kind does not name is refused" {
        val assigned = fold(log(move("stage", STAGE, "planned", "assigned")))
        val branch = table("now" to NOW, "git.branch_exists" to true)

        supervised().judge(assigned, Arrival(message("started", from = "coder-1", to = "principal")), branch).refusal() shouldBe ("started" to "to: teamlead")
        supervised().judge(assigned, Arrival(message("started", from = "coder-1", to = "teamlead")), branch).outcome shouldBe Outcome.DELIVER
    }
})
