package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * For the next developer: the walks EngineJudgesSpec names are every walk that needs no robot — a new one is added there.
 * `seat-deaths-paused` lets the robot hear both deaths since S3.6 (T4): `just hero` plays it through the mailroom instead.
 */
class WalksCoveredSpec : StringSpec({
    "the walks the engine is compared on are every walk that needs no robot" {
        // S3.4: the two walks of D-118 that retire a seat by a message of the pool's, with no robot's rule fired.
        walksThatNeedNoRobot().toSet() shouldBe setOf("a-re-seated-seat-in-its-stage-is-not-retired", "a-seat-that-never-worked-is-retired",
            "approval-create-failure", "body-lines-cap", "H-3", "H-3-rollback", "no-roll-live-D-42", "reserved-model")
    }
})
