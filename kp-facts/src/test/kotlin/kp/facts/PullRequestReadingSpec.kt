package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.facts.RecordedGitHub.Companion.jsonList
import kp.facts.RecordedGitHub.Companion.recorded

/** The slices of T4, for the next developer: which pull request is the stage's, every page, what is sent, what is refused. */
class PullRequestReadingSpec : FreeSpec({
    "the stage's pull request is the open one, else the one made last; two open ones are unknown, citing both" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull("number" to 3, "created_at" to "2026-11-15T09:00:00Z", "merged_at" to null), pull(), pull("number" to 2, "created_at" to "2026-11-14T09:00:00Z", "merged_at" to null))
            toy.observe("pr.closed").evidence shouldBe "Collite/kp-toy#3: closed, head 028e9e2"

            toy.pullRequests(pull("number" to 3, "state" to "open", "merged_at" to null), pull())
            toy.observe("pr.ready").evidence shouldBe "Collite/kp-toy#3: open, head 028e9e2"

            toy.pullRequests(pull("number" to 3, "state" to "open", "merged_at" to null), pull("number" to 4, "state" to "open", "merged_at" to null))
            toy.observe("pr.ready").missing shouldBe "Collite/kp-toy holds 2 open pull requests whose head is $TOY_BRANCH: #3, #4"
        }
    }

    "a list of pull requests is read to its last page" {
        Toy(tempdir()).use { toy ->
            toy.github.onPages("/repos/Collite/kp-toy/pulls", jsonList(pull("number" to 2, "created_at" to "2026-11-12T09:00:00Z", "merged_at" to null)), jsonList(pull()),
                query = mapOf("head" to "Collite:$TOY_BRANCH"))

            toy.observe("pr.merged").evidence shouldBe "Collite/kp-toy#1: closed, merged 2026-11-14T09:16:10Z, head 028e9e2"
            toy.github.requests.map { it.target } shouldBe listOf(
                "/repos/Collite/kp-toy/pulls?head=Collite%3Afeat%2Ftoy-kp-toy-p0-s0.1-skeleton&state=all&per_page=100",
                "/repos/Collite/kp-toy/pulls?head=Collite%3Afeat%2Ftoy-kp-toy-p0-s0.1-skeleton&state=all&per_page=100&page=2",
            )
        }
    }

    "each request carries the token handed over for `<organization>/<repo>`, GitHub's media type and the API's version" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())

            toy.observe("pr.head")

            toy.asked shouldBe listOf("Collite/kp-toy")
            toy.github.requests.single().headers.filterKeys { it in setOf("Authorization", "Accept", "X-github-api-version") } shouldBe
                mapOf("Authorization" to listOf("Bearer $TEST_TOKEN"), "Accept" to listOf("application/vnd.github+json"), "X-github-api-version" to listOf("2022-11-28"))
        }
    }

    "no token, a list that names no branch or repository, a sha that is none, a next page off GitHub, an answer not in GitHub's shape: each is unknown and says so" {
        Toy(tempdir()).use { toy ->
            toy.token = Handed.Refused("GitHub answered 422 to POST /app/installations/184045472/access_tokens: The permissions requested are not granted to this installation.")
            toy.observe("pr.ready").missing shouldBe "no token to read Collite/kp-toy: GitHub answered 422 to POST /app/installations/184045472/access_tokens: The permissions requested are not granted to this installation."
            toy.token = Handed.Over("Bearer $TEST_TOKEN")

            toy.observe("ci.state", "sha" to "main").missing shouldBe "the parameter 'sha' is 'main', which is no sha"
            toy.github.on("/repos/Collite/kp-toy/pulls", answer = Answer(200, jsonList(pull()), mapOf("Link" to "<https://elsewhere.example/repos/x?page=2>; rel=\"next\"")))
            toy.observe("pr.ready").missing shouldBe "GitHub's next page of GET repos/Collite/kp-toy/pulls is not under ${toy.github.api}"
            toy.pullRequests(pull("head" to null))
            toy.observe("pr.head").missing shouldBe "a pull request in GitHub's answer to GET repos/Collite/kp-toy/pulls has no head.sha"
            toy.github.on("/repos/Collite/kp-toy/commits/$TOY_HEAD/check-runs", answer = Answer(200, recorded("pulls-none.json")))
            toy.observe("ci.state", "sha" to TOY_HEAD).missing shouldBe "GitHub's answer to GET repos/Collite/kp-toy/commits/$TOY_HEAD/check-runs is no list in 'check_runs'"

            toy.office.project.write("toy/tasks-p0-s0.1-skeleton.md", "# TOY · S0.1 — The skeleton\n")
            toy.observe("pr.ready").missing shouldBe "toy/tasks-p0-s0.1-skeleton.md#L1 names no branch '(PR: <branch>)'"
            toy.office.project.write("toy/tasks-p0-s0.1-skeleton.md", "# TOY · S0.1 — The skeleton (PR: $TOY_BRANCH)\n")
            toy.observe("pr.ready").missing shouldBe "toy/tasks-p0-s0.1-skeleton.md#L1 names no repository '# TOY-<repo>-P0'"
        }
    }

    "`ci.state` says in its evidence what each check run is" {
        Toy(tempdir()).use { toy ->
            toy.checkRuns(TOY_HEAD, recorded("check-runs.json"))

            toy.observe("ci.state", "sha" to TOY_HEAD).evidence shouldBe "Collite/kp-toy 028e9e2: 2 check runs — check: completed success; check: completed success"
            toy.observe("ci.state", "sha" to "0000000").evidence shouldContain "GitHub answered 404 to GET repos/Collite/kp-toy/commits/0000000/check-runs: Not Found"
        }
    }
})
