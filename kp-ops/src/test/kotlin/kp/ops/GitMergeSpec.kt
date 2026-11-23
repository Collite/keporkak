package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kp.facts.Answer
import kp.facts.MERGED_BRANCH
import kp.facts.MERGED_HEAD
import kp.facts.RecordedGitHub
import kp.facts.RecordedGitHub.Companion.jsonList
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.closedPull
import kp.facts.mergedPull
import kp.facts.openPull
import kp.facts.pullsOf

/** The slices of T2, for the next developer: which pull request a merge asks for, and GitHub's answers past the four ends. */
class GitMergeSpec : FreeSpec({
    val merger = App("kp-merger", 5849229, 184045472)
    val at = StageOnGitHub("TOY/P0/S0.9", "Collite/kp-toy", MERGED_BRANCH)

    fun withToy(test: (RecordedGitHub, GitMerge) -> Unit) {
        val keys = tempdir().toPath()
        val server = RecordedGitHub.local()
        val github = RecordedGitHub(server)
        FakeApps(server, keys).add(KP_MERGER)
        server.start()
        try {
            test(github, GitMerge(GitHubApps(keys, github.api), merger, github.api))
        } finally {
            server.stop(0)
        }
    }

    "a stage's pull request that is merged, or closed, is not asked to merge: refused, naming it" {
        withToy { github, merge ->
            github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(405, """{"message": "Pull Request is not mergeable"}"""))
            github.pullsOf(MERGED_BRANCH, mergedPull())
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: Collite/kp-toy#3 is merged: only an open pull request is merged")
            github.pullsOf(MERGED_BRANCH, mergedPull("merged_at" to null))
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: Collite/kp-toy#3 is closed: only an open pull request is merged")
            github.requests.filter { it.method == "PUT" }.size shouldBe 0
        }
    }

    "of the pull requests whose head is the branch, the open one is merged — a closed one made later is not; two open are no answer" {
        withToy { github, merge ->
            github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(200, recorded("merge-200.json")))
            github.pullsOf(MERGED_BRANCH, openPull(), closedPull("number" to 9, "created_at" to "2026-11-22T20:00:00Z"))
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Ok(mapOf("sha" to "0c6b33afb43dd4addb3147ad73edf4c53087a17a"))
            github.pullsOf(MERGED_BRANCH, openPull(), openPull("number" to 9))
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: Collite/kp-toy holds 2 open pull requests whose head is kp-proof-s3.9-rec-head: #3, #9")
        }
    }

    "the list of pull requests is read to its last page" {
        withToy { github, merge ->
            github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(200, recorded("merge-200.json")))
            github.onPages("/repos/Collite/kp-toy/pulls", jsonList(closedPull("number" to 2)), jsonList(openPull()), query = mapOf("head" to "Collite:$MERGED_BRANCH"))
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Ok(mapOf("sha" to "0c6b33afb43dd4addb3147ad73edf4c53087a17a"))
            github.requests.filter { it.method == "GET" }.size shouldBe 2
        }
    }

    "a 200 that names no merge commit, a 422 and a 404 are refused with what GitHub said" {
        withToy { github, merge ->
            github.pullsOf(MERGED_BRANCH, openPull())
            github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(200, """{"merged": false, "message": "Not merged"}"""),
                Answer(422, """{"message": "Validation Failed"}"""), Answer(404, recorded("repo-404.json")))
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: GitHub's answer to PUT /repos/Collite/kp-toy/pulls/3/merge names no merge commit")
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: GitHub answered 422 to PUT /repos/Collite/kp-toy/pulls/3/merge: Validation Failed")
            merge.perform(at, "m-1", MERGED_HEAD) shouldBe Performed.Failed("refused: GitHub answered 404 to PUT /repos/Collite/kp-toy/pulls/3/merge: Not Found")
        }
    }
})
