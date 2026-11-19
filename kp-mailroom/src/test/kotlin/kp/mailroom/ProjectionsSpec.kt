package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.State

/**
 * Every projection of the state in kp-mailroom's specs names each field of [State] — compared, or left out with its
 * reason (the retro of P1, candidate 2; S2.8 T4). A field the state gains turns this red until the projection says which.
 */
class ProjectionsSpec : StringSpec({
    "every projection of the state in kp-mailroom's specs compares each of its fields or lists it with a reason" {
        WHERE_THE_LOG_ENDS.unaccounted(fieldsOf(State::class)) shouldBe emptyList()
    }
})
