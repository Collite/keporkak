package kp.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * S2.8 — the checks P1's retro asked for, on the specs' own support and a fixture (T1; Bora reads these). A walk's refused
 * step names the rule that refuses it, and the Python walker and the engine's walk harness both hold it to that rule; a
 * projection of the state names each of its fields — compared, or left out with a reason. The build, the filter and the
 * changelog check are `tests/test_s2_8_retro_checks.py`.
 */
class RetroChecksSpec : StringSpec({
    "a walk step refused by another rule than the one it names fails the walker and the engine's harness alike" {
        val walk = fixtureWalk(
            """
            name: refused-by-another-rule
            stage: no-branch-yet
            initial: assigned
            final: assigned
            seats:
              coder-1: coder
            facts:
              git.branch_exists: false
            steps:
            - who: coder-1
              kind: started
              args: {}
              expect:
                refused: true
                refused_by: T-01
            """.trimIndent(),
        )

        walkerFailures(walk) shouldBe listOf("walk refused-by-another-rule step 1: refused by started, the walk names T-01")
        shouldThrow<AssertionError> { engineTrace(walk) }.message shouldBe "walk refused-by-another-rule step 1: refused by started, the walk names T-01"
    }

    "a projection of the state fails when the state gains a field it neither compares nor lists" {
        val grown = fieldsOf(State::class) + "lastWake"

        TIMES_IN_THE_STATE.unaccounted(fieldsOf(State::class)) shouldBe emptyList()
        TIMES_IN_THE_STATE.unaccounted(grown) shouldBe listOf("lastWake")
    }
})
