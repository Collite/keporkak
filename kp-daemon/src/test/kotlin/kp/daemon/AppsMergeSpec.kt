package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kp.facts.Answer

/**
 * The slices of T6, for the next developer: `just apps merge` ([AppsMerge]) in front of the local GitHub server. Its
 * steps on the toy are its live run on Panda, in S3.9's hand-off; here, the stop that comes before it makes anything.
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
})
