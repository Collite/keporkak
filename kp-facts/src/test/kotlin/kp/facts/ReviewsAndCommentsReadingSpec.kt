package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kp.facts.RecordedGitHub.Companion.jsonList

/** The slices of T5, for the next developer: what is not read, what is unknown, and every page of reviews and comments. */
class ReviewsAndCommentsReadingSpec : FreeSpec({
    "`pr.review_on`: a review not yet submitted is not read; a dismissed last one is unknown; so is the reviewer's App when its name is not known" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())

            toy.reviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:10:00Z"), byTheReviewer(TOY_HEAD, "PENDING", "2026-11-13T12:20:00Z") - "submitted_at")
            toy.observe("pr.review_on", "sha" to TOY_HEAD).value shouldBe "approved"
            toy.reviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:10:00Z"), byTheReviewer(TOY_HEAD, "DISMISSED", "2026-11-13T12:20:00Z"))
            toy.observe("pr.review_on", "sha" to TOY_HEAD).missing shouldBe
                "Collite/kp-toy#1: kp-reviewer[bot]'s last review on 028e9e2 is 'DISMISSED', a state Amendment 33 does not give"
            toy.reviewer = Handed.Withheld("GitHub did not answer GET /app: ConnectException")
            toy.observe("pr.review_on", "sha" to TOY_HEAD).missing shouldBe
                "the name GitHub shows for the reviewer's App is not known: GitHub did not answer GET /app: ConnectException"
        }
    }

    "reviews and comments are read to their last page" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            toy.github.onPages("/repos/Collite/kp-toy/pulls/1/reviews", jsonList(byTheReviewer(TOY_HEAD, "CHANGES_REQUESTED", "2026-11-13T12:10:00Z")),
                jsonList(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:20:00Z")))
            toy.github.onPages("/repos/Collite/kp-toy/issues/1/comments", jsonList(comment("created_at" to "2026-11-13T11:52:00Z", "body" to "B1: fixed")),
                jsonList(comment("created_at" to "2026-11-13T11:53:00Z", "body" to "B2: fixed")))

            toy.observe("pr.review_on", "sha" to TOY_HEAD).value shouldBe "approved"
            toy.observe("pr.blocker_responses", "ids" to listOf("B1", "B2")).value shouldBe listOf("B1", "B2")
        }
    }

    "`pr.blocker_responses`: a re-verdict is a verdict; with neither on the log, unknown; an id alone on its line answers nothing, and after a bullet it answers (Amendment 38)" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.comments(comment("created_at" to "2026-11-13T11:52:00Z", "body" to "B1\n- B2: fixed\nB3: fixed"))

            toy.observe("pr.blocker_responses", "ids" to listOf("B1")).missing shouldBe "no verdict or re-verdict of TOY/P0/S0.1 is delivered on the log"
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:00:00Z")
            toy.log.delivered(TOY_STAGE, "re-verdict", "2026-11-13T11:30:00Z")
            toy.log.delivered("TOY/P0/S0.2", "verdict", "2026-11-13T12:00:00Z")
            toy.observe("pr.blocker_responses", "ids" to listOf("B1", "B2", "B3")).value shouldBe listOf("B2", "B3")
            toy.log.delivered(TOY_STAGE, "re-verdict", "2026-11-13T12:00:00Z")
            toy.observe("pr.blocker_responses", "ids" to "B3").value shouldBe emptyList<String>()
        }
    }

    "`pr.commits_since`: a head observed that is no sha is unknown; `pr.evidence_present` with no description is false" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull("body" to null))
            toy.log.heads["m-panda-000040"] = "{\"unknown\":\"not supplied\"}"

            toy.observe("pr.commits_since", "message" to "m-panda-000040").missing shouldBe "the judgement of m-panda-000040 observed '{\"unknown\":\"not supplied\"}', which is no sha"
            toy.observe("pr.evidence_present").value shouldBe false
            toy.observe("pr.evidence_present").evidence shouldBe "the description of Collite/kp-toy#1 has no ## Stage, ## What landed, ## DONE line, ## Verified, ## Not in this stage, ## Found while doing"
        }
    }
})

/** The object without its field [name]. */
private operator fun kotlinx.serialization.json.JsonObject.minus(name: String): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(filterKeys { it != name })
