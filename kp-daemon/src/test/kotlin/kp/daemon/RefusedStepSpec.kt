package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain

/**
 * S2.9 T6 — a rehearsal's and a hero's refused step is held to its `refused_by` (S2.8's Found line; architecture.md §8:
 * a walk's refused step names its rule): the step is refused, but by another rule than the one it names, and the walk
 * stops there. A hero plays its walks through the same player, so its refused steps are held the same way.
 */
class RefusedStepSpec : StringSpec({
    "a rehearsal whose refused step names another rule than the one that refuses ends as failed, naming the step" {
        Rehearsed("refused-by-another-rule").use { office ->
            office.said.filter { it.startsWith("the rehearsal stopped") }.shouldBeEmpty()
            office.said.filter { it.startsWith("the walk stops here") }.shouldNotBeEmpty().single().let {
                it shouldContain "step 1"
                it shouldContain "expected refused_by T-08, found started"
            }
        }
    }
})
