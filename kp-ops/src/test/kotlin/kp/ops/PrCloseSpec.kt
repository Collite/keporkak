package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kp.facts.Answer
import kp.facts.CLOSED_BRANCH
import kp.facts.RecordedGitHub
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.closedPull
import kp.facts.pullsOf

/** The slices of T3, for the next developer: GitHub refusing the close, or answering it with a pull request still open. */
class PrCloseSpec : FreeSpec({
    "a close GitHub refuses — recorded from the toy with a token that may not — or answers with the pull request still open, is failed" {
        val keys = tempdir().toPath()
        val server = RecordedGitHub.local()
        val github = RecordedGitHub(server)
        FakeApps(server, keys).add(KP_MERGER)
        server.start()
        try {
            github.pullsOf(CLOSED_BRANCH, closedPull("state" to "open", "closed_at" to null))
            github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/4", Answer(403, recorded("close-403.json")), Answer(200, closedPull("state" to "open").toString()))
            val close = PrClose(GitHubApps(keys, github.api), App("kp-merger", 5849229, 184045472), github.api)
            val at = StageOnGitHub("TOY/P0/S0.9", "Collite/kp-toy", CLOSED_BRANCH)

            close.perform(at) shouldBe Performed.Failed("GitHub answered 403 to PATCH /repos/Collite/kp-toy/pulls/4: Resource not accessible by integration")
            close.perform(at) shouldBe Performed.Failed("GitHub's answer to PATCH /repos/Collite/kp-toy/pulls/4 leaves Collite/kp-toy#4 open")
        } finally {
            server.stop(0)
        }
    }
})
