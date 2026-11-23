package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.facts.RecordedGitHub.Companion.recorded

/** S3.10 T3's slices, for the next developer (Amendment 38; Collite/keporkak#57): GitHub's reader on its edges. */
class GitHubReadingEdgesSpec : FreeSpec({
    "a refused read is not tried again: one request for a refusal, one for what is not there, one for a limit" {
        Toy(tempdir()).use { toy ->
            for (answer in listOf(Answer(403, """{"message":"Resource not accessible by integration"}"""), Answer(404, recorded("repo-404.json")),
                Answer(429, """{"message":"API rate limit exceeded"}""", mapOf("retry-after" to "60")))) {
                toy.github.onEvery(answer)
                toy.github.requests.clear()

                toy.observe("pr.ready").missing!!.startsWith("GitHub") shouldBe true
                toy.github.requests.map { it.target.substringBefore('?') } shouldBe listOf("/repos/Collite/kp-toy/pulls")
            }
        }
    }

    "a time GitHub gives in another shape is unknown, with what it was and where it stood; nothing is thrown" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            toy.reviews(byTheReviewer(TOY_HEAD, "APPROVED", "13 Nov 2026 12:10"))
            toy.comments(comment("created_at" to "2026-11-13 11:52"))

            toy.observe("pr.review_on", "sha" to TOY_HEAD).missing shouldBe
                "a review's submitted_at in GitHub's answer to GET repos/Collite/kp-toy/pulls/1/reviews is '13 Nov 2026 12:10', which is no time in the shape GitHub gives"
            toy.observe("pr.blocker_responses", "ids" to listOf("B1")).missing shouldBe
                "a comment's created_at in GitHub's answer to GET repos/Collite/kp-toy/issues/1/comments is '2026-11-13 11:52', which is no time in the shape GitHub gives"
        }
    }

    "the recorded comparison holds both of GitHub's commits, as many as it says it holds" {
        val compared = Json.parseToJsonElement(recorded("compare.json")).jsonObject

        compared.getValue("commits").jsonArray.map { it.jsonObject.getValue("sha").jsonPrimitive.content } shouldBe listOf(ONE_BACK, TOY_HEAD)
        compared.getValue("total_commits").jsonPrimitive.int shouldBe 2
    }
})
