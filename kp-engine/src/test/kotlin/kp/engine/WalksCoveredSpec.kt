package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** For the next developer: the walks EngineJudgesSpec names are every walk that needs no robot — a new one is added there. */
class WalksCoveredSpec : StringSpec({
    "the walks the engine is compared on are every walk that needs no robot" {
        walksThatNeedNoRobot().toSet() shouldBe setOf("approval-create-failure", "body-lines-cap", "H-3", "H-3-rollback", "no-roll-live-D-42", "reserved-model", "seat-deaths-paused-D-81")
    }
})
