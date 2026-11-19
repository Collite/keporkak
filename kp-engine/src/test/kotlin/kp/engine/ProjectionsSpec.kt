package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Every projection of the state in kp-engine's specs names each field of [State] — compared, or left out with its reason
 * (the retro of P1, candidate 2; S2.8 T4). A field the state gains turns this red until each projection says which.
 * The fold's determinism compares the whole state, so it has none to name.
 */
class ProjectionsSpec : StringSpec({
    "every projection of the state in kp-engine's specs compares each of its fields or lists it with a reason" {
        TIMES_IN_THE_STATE.unaccounted(fieldsOf(State::class)) shouldBe emptyList()
        QUESTIONS_THE_LOGS_EXERCISE.unaccounted(fieldsOf(State::class)) shouldBe emptyList()
    }
})
