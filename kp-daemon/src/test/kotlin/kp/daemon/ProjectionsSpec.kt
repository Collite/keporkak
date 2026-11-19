package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.daemon.hero.Restarts
import kp.engine.State

/**
 * Every projection of the state in kp-daemon's specs names each field of [State] — compared, or left out with its reason
 * (the retro of P1, candidate 2; S2.8 T4). A field the state gains turns this red until the projection says which. It
 * runs in `test`; the restart it reads of is `hero`'s.
 */
class ProjectionsSpec : StringSpec({
    "every projection of the state in kp-daemon's specs compares each of its fields or lists it with a reason" {
        Restarts.Ending.PROJECTION.unaccounted(fieldsOf(State::class)) shouldBe emptyList()
    }
})
