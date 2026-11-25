package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kp.facts.Answer

/**
 * The slices of T6, for the next developer: `just apps merge` ([AppsMerge]) in front of the local GitHub server. Its
 * steps on the toy are its live run on the office, in S3.9's and S3.11's hand-offs; here, the stop that comes before it
 * makes anything, and one after it has made its branches (S3.11 T6).
 */
class AppsMergeSpec : FreeSpec({
    "where no rule holds the coder's App to `update`, the proof says SC-10 and makes nothing; it prints no token" {
        GitHubOffice().use { office ->
            office.github.on("/repos/Collite/kp-toy", answer = Answer(200, """{"default_branch": "master"}"""))
            office.github.on("/repos/Collite/kp-toy/git/ref/heads/master", answer = Answer(200, """{"object": {"sha": "25d4f09bb1b5041db31fe59eb68e07463248280e"}}"""))
            office.github.on("/repos/Collite/kp-toy/rules/branches/master", answer = Answer(200, "[]"))
            val printed = mutableListOf<String>()

            AppsMerge.run(office.office.rules, "panda", office.api) { printed += it } shouldBe 1

            printed.drop(1) shouldBe listOf(
                "  the default branch master is at 25d4f09bb1b5041db31fe59eb68e07463248280e",
                "  the repository's rules hold kp-merger to green checks: no — no second ruleset; its step is not run",
                "SC-10: no rule of master holds kp-coder to `update` — its merge would not be refused; nothing was made",
            )
            office.github.requests.filter { it.method != "GET" }.shouldBeEmpty()
            office.apps.issued.map { it.token }.filter { token -> printed.any { token in it } }.shouldBeEmpty()
        }
    }

    "a stop after the proof has made its branches is said where it comes, and the clean-up runs whole after it: a branch whose deletion GitHub cut off is said, and the next ones are still deleted (Collite/keporkak#64, 4)" {
        GitHubOffice().use { office ->
            val prefix = "kp-apps-merge-20261124-090000"
            office.github.on("/repos/Collite/kp-toy", answer = Answer(200, """{"default_branch": "master"}"""))
            office.github.on("/repos/Collite/kp-toy/git/ref/heads/master", answer = Answer(200, """{"object": {"sha": "25d4f09bb1b5041db31fe59eb68e07463248280e"}}"""))
            office.github.on("/repos/Collite/kp-toy/rules/branches/master", answer = Answer(200, """[{"type": "update", "ruleset_id": 7}]"""))
            office.github.on("/repos/Collite/kp-toy/rulesets/7", answer = Answer(200, """{"name": "only kp-merger moves master", "current_user_can_bypass": "never"}"""))
            office.github.onRequests("POST", "/repos/Collite/kp-toy/git/refs", Answer(201, "{}"))
            listOf("merged.txt", "never.txt").forEach { file ->
                office.github.onRequests("PUT", "/repos/Collite/kp-toy/contents/kp-apps-merge/$file", Answer(201, """{"commit": {"sha": "5d1a7961f450b02339f8a464e544b3ef53f55d9f"}}"""))
            }
            office.github.onRequests("POST", "/repos/Collite/kp-toy/pulls", Answer(422, """{"message": "Validation Failed"}"""))
            office.github.onRequest("DELETE", "/repos/Collite/kp-toy/git/refs/heads/$prefix-base") { error("GitHub cuts the connection") }
            office.github.onRequests("DELETE", "/repos/Collite/kp-toy/git/refs/heads/$prefix-head", Answer(204, ""))
            office.github.onRequests("DELETE", "/repos/Collite/kp-toy/git/refs/heads/$prefix-main", Answer(204, ""))
            val printed = mutableListOf<String>()

            AppsMerge.run(office.office.rules, "panda", office.api, clock = { java.time.Instant.parse("2026-11-24T09:00:00Z") }) { printed += it } shouldBe 1

            printed.filter { "stopped" in it }.map { it.substringBefore(": IOException").substringBefore(": java.io") } shouldBe listOf(
                "  stopped: GitHub answered 422 to POST /repos/Collite/kp-toy/pulls: Validation Failed",
                "  NO   the branch $prefix-base is deleted — stopped: GitHub did not answer DELETE /repos/Collite/kp-toy/git/refs/heads/$prefix-base",
            )
            printed.filter { "is deleted" in it }.drop(1) shouldBe listOf(
                "  yes  the branch $prefix-head is deleted — GitHub answered 204",
                "  yes  the branch $prefix-main is deleted — GitHub answered 204",
            )
            printed.last() shouldBe "  yes  no pull request the proof made is open — # closed"
            office.github.requests.filter { it.method == "DELETE" }.size shouldBe 3
        }
    }
})
