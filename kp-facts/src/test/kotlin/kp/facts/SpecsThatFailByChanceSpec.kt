package kp.facts

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

/**
 * KP/P3/S3.10 T1 — GitHub's reader, as Amendment 38 corrects it (Bora reads this). The seam: `kp-facts`' entry for
 * GitHub's facts in front of S3.2's local GitHub, which answers what was recorded from `Collite/kp-toy`'s pull request #1.
 */
class SpecsThatFailByChanceSpec : StringSpec({
    "a blocker is answered by a line that begins with its id, plain or after a list marker — a dash, a star, a plus, a number and a dot — and not by the id later in a line, nor by `B1` for `B10`" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            toy.comments(comment("created_at" to "2026-11-13T11:52:00Z", "body" to
                "- B1 — fixed\n* B2: fixed\n+ B3 fixed\n1. B4 fixed\nB5 fixed\nsee B6, fixed too\nB70 fixed"))

            toy.observe("pr.blocker_responses", "ids" to listOf("B1", "B2", "B3", "B4", "B5", "B6", "B7")).value shouldBe
                listOf("B1", "B2", "B3", "B4", "B5")

            // The toy's pull request #1 as GitHub gave it: its author answered both blockers in bullets, `- B1 — Fixed in …`.
            toy.comments(comment())
            toy.observe("pr.blocker_responses", "ids" to listOf("B1", "B2")).value shouldBe listOf("B1", "B2")
        }
    }
})
