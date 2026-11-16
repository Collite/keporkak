package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val REVIEW = "m-panda-000020"
private fun waiting(state: String) = fold(log(move("stage", STAGE, "building", "ready_for_review"), *sent(REVIEW, "review-request", from = "coder-1"),
    move("stage", STAGE, "ready_for_review", state, cause = "m-panda-000021")))

/** S1.3, Amendment 13 (contracts.md §4.2): `{wait: again}` holds only when this expiry fired a transition that moved the counter its `if` reads. */
class WaitAgainSpec : StringSpec({
    "a review-request whose stage is paused expires once: the expiry counts nothing, so the wait is over" {
        val decision = supervised().judge(waiting("paused"), Trigger.Expiry(REVIEW), table("now" to "2026-11-15T11:00:00Z"))

        decision.moves shouldBe emptyList()
        decision.deadline shouldBe null
        decision.effects.map { (it as? Requested.Emit)?.kind } shouldBe listOf("nudge")
    }

    "the same review-request in ready_for_review counts its expiry, and the wait starts again for the kind's deadline" {
        val decision = supervised().judge(waiting("ready_for_review"), Trigger.Expiry(REVIEW), table("now" to "2026-11-15T11:00:00Z"))

        decision.counted shouldBe mapOf("review_request_expiries" to 1L)
        decision.deadline shouldBe "2026-11-15T12:00:00Z"
    }
})
