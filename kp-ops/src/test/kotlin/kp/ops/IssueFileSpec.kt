package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.facts.Answer
import kp.facts.MERGED_BRANCH
import kp.facts.RecordedGitHub
import kp.facts.filedIssue
import kp.facts.pullsOf

/** The slices of T4, for the next developer: the last line of a stage with no pull request, and a list GitHub cannot give. */
class IssueFileSpec : FreeSpec({
    "with no pull request the last line says none; when GitHub cannot list them, nothing is filed; the first refused names none filed" {
        val keys = tempdir().toPath()
        val server = RecordedGitHub.local()
        val github = RecordedGitHub(server)
        FakeApps(server, keys).add(KP_MERGER)
        server.start()
        try {
            val file = IssueFile(GitHubApps(keys, github.api), App("kp-merger", 5849229, 184045472), github.api)
            val at = StageOnGitHub("TOY/P0/S0.9", "Collite/kp-toy", MERGED_BRANCH)
            github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 12)), Answer(422, """{"message": "Validation Failed"}"""))

            github.pullsOf(MERGED_BRANCH)
            file.perform(at, "m-7", listOf(Issue.found("one"))) shouldBe Performed.Ok(mapOf("numbers" to listOf(12)))
            Json.parseToJsonElement(github.requests.last().body).jsonObject["body"]?.jsonPrimitive?.content shouldBe "Filed for TOY/P0/S0.9 · asked by m-7 · pull request none"

            file.perform(at, "m-8", listOf(Issue.found(""))) shouldBe
                Performed.Failed("issue 1 of 1 was not filed, nor any after it: GitHub answered 422 to POST /repos/Collite/kp-toy/issues: Validation Failed — filed: none")

            github.onEvery(Answer(500, """{"message": "Server Error"}"""))
            file.perform(at, "m-9", listOf(Issue.found("two"))) shouldBe
                Performed.Failed("no issue was filed: GitHub answered 500 to GET /repos/Collite/kp-toy/pulls: Server Error")
            github.requests.filter { it.method == "POST" }.size shouldBe 2
        } finally {
            server.stop(0)
        }
    }
})
