package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival

/**
 * S1.2's five behaviours at the engine's `judge`: master's rule set as S1.0 loads it, a state folded from fixture
 * records, the facts given as a table.
 */
class EngineJudgesSpec : StringSpec({
    "a message whose requirements hold is delivered, and names the transition it fires and the counters it moves" {
        val state = fold(log(move("stage", STAGE, "assigned", "building")))
        val review = message("review-request", from = "coder-1", args = """{"attest": ["done_lines_hold"]}""")
        val facts = table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "green",
            "list.tasks_open" to 0, "list.found_untriaged" to 0)

        val decision = supervised().judge(state, Arrival(review), facts)

        decision.outcome shouldBe Outcome.DELIVER
        decision.rule shouldBe "T-17"
        decision.moves shouldBe listOf(Move("stage", STAGE, "building", "ready_for_review", "T-17", mapOf("review_requests" to 1L)))
        decision.counted shouldBe mapOf("review_requests" to 1L)
    }

    "a message whose requirement fails is refused, naming the rule's id and the first failed requirement in the rules' own words" {
        val state = fold(log(move("stage", STAGE, "building", "stopped", extra = ""","counted":{"resumes":2}""")))
        val resume = message("triage", from = "principal", to = "coder", args = """{"outcome": "resume", "since": "2026-11-15T09:00:00Z"}""")
        val facts = table("now" to NOW, "list.sections_changed(since=2026-11-15T09:00:00Z)" to listOf("Found while doing"), "list.tasks_total" to 8)

        val decision = supervised().judge(state, Arrival(resume), facts)

        decision.outcome shouldBe Outcome.REFUSE
        decision.rule shouldBe "T-13"
        decision.requirement shouldBe "outcome=resume · task-count diff within R4.11 · no edit outside the Found section (observed) · counter resumes ≤ 2, the 3rd needs Bora"
        decision.failed?.flow() shouldBe "{counter: resumes, lt: {cap: resumes_per_stage}}"
    }

    "a kind its sender's role does not own, a kind past its max_fires in its window, a thread past its hop cap and a body past its line cap are refused though no requirement says so" {
        val assigned = fold(log(move("stage", STAGE, "planned", "assigned")))
        val startedOnce = fold(log(move("stage", STAGE, "planned", "assigned"), *sent("m-panda-000050", "started", from = "coder-1")))
        val eightHops = fold(log(move("stage", STAGE, "planned", "assigned"),
            *sent("m-panda-000051", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000052", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000053", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000054", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000055", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000056", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000057", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000058", "stop", from = "coder-1", thread = "m-panda-000051")))
        val fortyOneLines = (1..41).joinToString("\n")
        val facts = table("now" to NOW, "git.branch_exists" to true)
        val engine = supervised()

        engine.judge(assigned, Arrival(message("started", from = "teamlead")), facts).refusal() shouldBe ("started" to "from: coder")
        engine.judge(startedOnce, Arrival(message("started", from = "coder-1")), facts).refusal() shouldBe ("started" to "max_fires: 1, window: stage")
        engine.judge(eightHops, Arrival(message("started", from = "coder-1", thread = "m-panda-000051")), facts).refusal() shouldBe ("started" to "caps.hops_per_thread: 8")
        engine.judge(assigned, Arrival(message("started", from = "coder-1", body = fortyOneLines)), facts).refusal() shouldBe ("started" to "caps.body_lines: 40")
    }

    "of two candidate transitions, the first in file order whose requirements hold is the one that fires" {
        val passed = fold(log(move("stage", STAGE, "in_review", "passed")))
        val merge = message("merge", from = "teamlead", to = "daemon")
        val audit = arrayOf("now" to NOW, "pr.evidence_present" to true, "list.tasks_open" to 0, "project.proof_files" to 0, "list.found_untriaged" to 0)
        val strayChange = table(*audit, "git.stray_changes" to 1)
        val clean = table(*audit, "git.stray_changes" to 0)
        val frozenOnNoChangeToo = ruleSet(Edit("rules/stage-lifecycle.yaml", "  - fact: git.stray_changes\n    gt: 0", "  - fact: git.stray_changes\n    gte: 0"))

        supervised().judge(passed, Arrival(merge), strayChange).rule shouldBe "T-38"
        supervised().judge(passed, Arrival(merge), clean).rule shouldBe "T-35"
        supervised(frozenOnNoChangeToo).judge(passed, Arrival(merge), clean).rule shouldBe "T-35"
    }

    "every walk that needs no robot passes through the engine with the states the Python walker gives it" {
        engineTrace("approval-create-failure") shouldBe walkerTrace("approval-create-failure")
        engineTrace("body-lines-cap") shouldBe walkerTrace("body-lines-cap")
        engineTrace("H-3") shouldBe walkerTrace("H-3")
        engineTrace("H-3-rollback") shouldBe walkerTrace("H-3-rollback")
        engineTrace("no-roll-live-D-42") shouldBe walkerTrace("no-roll-live-D-42")
    }
})
