package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kp.facts.Answer
import kp.facts.CLOSED_BRANCH
import kp.facts.MERGED_BRANCH
import kp.facts.MERGED_HEAD
import kp.facts.MOVED_HEAD
import kp.facts.RecordedGitHub
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.closedPull
import kp.facts.filedIssue
import kp.facts.mergedPull
import kp.facts.mergesOnItsHead
import kp.facts.openPull
import kp.facts.pullsOf
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Path

/** `kp-merger` as Bora created it (`bora-github-apps.md` §1, §6): what it holds, the three repositories it reaches. */
val KP_MERGER = FakeApp("kp-merger", 5849229, 184045472, slug = "kp-merger",
    holds = mapOf("contents" to "write", "pull_requests" to "write", "issues" to "write", "checks" to "read", "actions" to "read"),
    reaches = listOf("keporkak", "kp-toy", "project"))

/**
 * KP/P3/S3.9 — operations on GitHub (T1). The seam: each performer of `kp-ops` in front of the local GitHub server — the
 * App endpoints ([FakeApps]) and the repository's ([RecordedGitHub]) on one port — that answers what was recorded on Panda
 * from `Collite/kp-toy` as `kp-merger` (`kp.facts.OperationRecordings`): pull request #3 open and merged, #4 closed, the
 * merge's five answers, a filed issue and a refused one. Where the toy gave none, a recording is changed in the fields
 * GitHub documents (a number, a state). The daemon's wiring — the head from the log, a request with no result at a start,
 * the `op` records and the journal — is `kp-daemon`'s `TheDaemonActsOnGitHubSpec`.
 */
class OperationsOnGitHubSpec : FreeSpec({
    "a merge asks GitHub for a merge commit on the head the `merge` message's judgement observed: merged — ok with the sha; the head moved — stale-ci; not mergeable — conflict; a rule's refusal, a missing permission, no pull request, GitHub not reached — refused, each with GitHub's reason" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.pullsOf(MERGED_BRANCH, openPull())
            toy.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge",
                Answer(200, recorded("merge-200.json")), Answer(409, recorded("merge-409.json")), Answer(405, recorded("merge-405-conflict.json")),
                Answer(405, recorded("merge-405-rule.json")), Answer(403, recorded("merge-403.json")))
            val merge = GitMerge(toy.apps, MERGER, toy.api)

            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe Performed.Ok(mapOf("sha" to "0c6b33afb43dd4addb3147ad73edf4c53087a17a"))
            Json.parseToJsonElement(toy.github.requests.last().body) shouldBe
                Json.parseToJsonElement("""{"sha": "83a09916860abd43cc175ba39c2f8d9afe2b6d46", "merge_method": "merge"}""")
            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("stale-ci: GitHub answered 409 to PUT /repos/Collite/kp-toy/pulls/3/merge: Head branch was modified. Review and try the merge again.")
            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("conflict: GitHub answered 405 to PUT /repos/Collite/kp-toy/pulls/3/merge: Pull Request has merge conflicts")
            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("refused: GitHub answered 405 to PUT /repos/Collite/kp-toy/pulls/3/merge: Repository rule violations found · Cannot update this protected ref. · Required status check \"check\" is in progress.")
            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("refused: GitHub answered 403 to PUT /repos/Collite/kp-toy/pulls/3/merge: Resource not accessible by integration")

            toy.github.pullsOf(MERGED_BRANCH)
            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("refused: Collite/kp-toy holds no pull request whose head is kp-proof-s3.9-rec-head")

            val nobody = URI.create("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/")
            (GitMerge(GitHubApps(toy.keys, nobody), MERGER, nobody).perform(AT_PULL_3, "m-000041", MERGED_HEAD) as Performed.Failed).reason shouldStartWith
                "refused: GitHub did not answer POST /app/installations/184045472/access_tokens: ConnectException"
            toy.github.requests.filter { it.method == "PUT" }.size shouldBe 5
        }
    }

    "a judgement that observed no head leaves nothing to merge on: `refused`, and GitHub is asked nothing" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.pullsOf(MERGED_BRANCH, openPull())
            toy.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(200, recorded("merge-200.json")))

            GitMerge(toy.apps, MERGER, toy.api).perform(AT_PULL_3, "m-000041", null) shouldBe
                Performed.Failed("refused: the judgement of m-000041 observed no pr.head: there is no head to merge on")

            toy.github.requests.shouldBeEmpty()
            toy.fake.requests.shouldBeEmpty()
        }
    }

    "a head that moved after the judgement observed it: the merge is asked on the observed head, never on the one GitHub lists now — and GitHub, which merges only on the head it holds, answers `stale-ci` (Amendment 37: handed over from the log, never read again)" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.pullsOf(MERGED_BRANCH, openPull("head.sha" to MOVED_HEAD))
            toy.github.mergesOnItsHead("/repos/Collite/kp-toy/pulls/3/merge") { MOVED_HEAD }
            val merge = GitMerge(toy.apps, MERGER, toy.api)

            merge.perform(AT_PULL_3, "m-000041", MERGED_HEAD) shouldBe
                Performed.Failed("stale-ci: GitHub answered 409 to PUT /repos/Collite/kp-toy/pulls/3/merge: Head branch was modified. Review and try the merge again.")
            Json.parseToJsonElement(toy.github.requests.single { it.method == "PUT" }.body) shouldBe
                Json.parseToJsonElement("""{"sha": "$MERGED_HEAD", "merge_method": "merge"}""")

            // The same GitHub merges a request on the head it lists: what a merge that read the head again would get.
            merge.perform(AT_PULL_3, "m-000041", MOVED_HEAD) shouldBe Performed.Ok(mapOf("sha" to "0c6b33afb43dd4addb3147ad73edf4c53087a17a"))
        }
    }

    "a close: an open pull request is closed; one already closed and none at all are `ok` and say so; a merged one is `failed`" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/4", Answer(200, recorded("pull-closed.json")))
            val close = PrClose(toy.apps, MERGER, toy.api)

            toy.github.pullsOf(CLOSED_BRANCH, closedPull("state" to "open", "closed_at" to null))
            close.perform(AT_PULL_4) shouldBe Performed.Ok(mapOf("closed" to "Collite/kp-toy#4"))
            toy.github.requests.last().body shouldBe """{"state":"closed"}"""

            toy.github.pullsOf(CLOSED_BRANCH, closedPull())
            close.perform(AT_PULL_4) shouldBe Performed.Ok(mapOf("closed" to "nothing to close: Collite/kp-toy#4 was closed already"))

            toy.github.pullsOf(CLOSED_BRANCH)
            close.perform(AT_PULL_4) shouldBe Performed.Ok(mapOf("closed" to "nothing to close: Collite/kp-toy holds no pull request whose head is kp-proof-s3.9-rec-main"))

            toy.github.pullsOf(MERGED_BRANCH, mergedPull())
            close.perform(AT_PULL_3) shouldBe Performed.Failed("Collite/kp-toy#3 is merged: a merged pull request is not closed")

            toy.github.requests.filter { it.method == "PATCH" }.size shouldBe 1
        }
    }

    "issues: no item — nothing filed; one item; three, in their order, each body ending with the stage, the message and the pull request; the second of three refused — `failed`, naming the first, and the third not asked for; a `found` files one" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.pullsOf(MERGED_BRANCH, openPull())
            val file = IssueFile(toy.apps, MERGER, toy.api)

            file.perform(AT_PULL_3, "m-000042", emptyList()) shouldBe Performed.Ok(mapOf("numbers" to emptyList<Int>()))
            toy.github.requests.shouldBeEmpty()

            toy.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 6)), Answer(201, filedIssue("number" to 7)), Answer(201, filedIssue("number" to 8)))
            file.perform(AT_PULL_3, "m-000042", listOf(Issue.followUp("Count a cache read once\nA cache read is counted as a turn."),
                Issue.followUp("Name the stage in a wake's prompt"), Issue.followUp("Say when a seat's turn ends\nThe journal has no line for it.\nNor the log."))) shouldBe
                Performed.Ok(mapOf("numbers" to listOf(6, 7, 8)))
            toy.github.requests.filter { it.method == "POST" }.map { Json.parseToJsonElement(it.body) } shouldBe listOf(
                Json.parseToJsonElement("""{"title": "Count a cache read once", "body": "A cache read is counted as a turn.\n\nFiled for TOY/P0/S0.9 · asked by m-000042 · pull request Collite/kp-toy#3"}"""),
                Json.parseToJsonElement("""{"title": "Name the stage in a wake's prompt", "body": "Filed for TOY/P0/S0.9 · asked by m-000042 · pull request Collite/kp-toy#3"}"""),
                Json.parseToJsonElement("""{"title": "Say when a seat's turn ends", "body": "The journal has no line for it.\nNor the log.\n\nFiled for TOY/P0/S0.9 · asked by m-000042 · pull request Collite/kp-toy#3"}"""),
            )

            toy.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 9)), Answer(403, recorded("issue-403.json")), Answer(201, filedIssue("number" to 10)))
            file.perform(AT_PULL_3, "m-000043", listOf(Issue.followUp("one"), Issue.followUp("two"), Issue.followUp("three"))) shouldBe
                Performed.Failed("issue 2 of 3 was not filed, nor any after it: GitHub answered 403 to POST /repos/Collite/kp-toy/issues: Resource not accessible by integration — filed: #9")
            toy.github.requests.filter { it.method == "POST" }.size shouldBe 5

            toy.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 11)))
            file.perform(AT_PULL_3, "m-000044", listOf(Issue.found("a wake's prompt names its stage as StageId(value=…)"))) shouldBe Performed.Ok(mapOf("numbers" to listOf(11)))
            Json.parseToJsonElement(toy.github.requests.last().body) shouldBe
                Json.parseToJsonElement("""{"title": "a wake's prompt names its stage as StageId(value=…)", "body": "Filed for TOY/P0/S0.9 · asked by m-000044 · pull request Collite/kp-toy#3"}""")
        }
    }

    "each performer mints for the stage's repository and for what it does, and no more; no token is in a result, an error or a request's address or body" {
        Toy(tempdir().toPath()).use { toy ->
            toy.github.pullsOf(MERGED_BRANCH, openPull())
            toy.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/3/merge", Answer(200, recorded("merge-200.json")), Answer(409, recorded("merge-409.json")))
            toy.github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/3", Answer(200, recorded("pull-closed.json")))
            toy.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue()), Answer(403, recorded("issue-403.json")))
            val outputs = listOf(
                GitMerge(toy.apps, MERGER, toy.api).perform(AT_PULL_3, "m-000041", MERGED_HEAD),
                GitMerge(toy.apps, MERGER, toy.api).perform(AT_PULL_3, "m-000041", MERGED_HEAD),
                PrClose(toy.apps, MERGER, toy.api).perform(AT_PULL_3),
                IssueFile(toy.apps, MERGER, toy.api).perform(AT_PULL_3, "m-000042", listOf(Issue.found("one"), Issue.found("two"))),
            ).map { it.toString() }

            toy.fake.issued.map { "${it.app.identity} ${it.repositories} ${it.permissions}" } shouldBe listOf(
                "kp-merger [kp-toy] {contents=write, pull_requests=read}",
                "kp-merger [kp-toy] {contents=write, pull_requests=read}",
                "kp-merger [kp-toy] {pull_requests=write}",
                "kp-merger [kp-toy] {issues=write, pull_requests=read}",
            )
            val tokens = toy.fake.issued.map { it.token }
            tokens.filter { token -> outputs.any { token in it } }.shouldBeEmpty()
            tokens.filter { token -> toy.github.requests.any { token in it.target || token in it.body } }.shouldBeEmpty()
            toy.github.requests.map { it.authorization?.removePrefix("Bearer ") }.filter { it !in tokens }.shouldBeEmpty()
        }
    }
})

/** `kp-merger`'s ids, as `pool/offices.yaml`'s block `github:` gives them. */
private val MERGER = App("kp-merger", 5849229, 184045472)

/** A stage whose branch is pull request #3's — open, then merged, in the recording. */
private val AT_PULL_3 = StageOnGitHub("TOY/P0/S0.9", "Collite/kp-toy", MERGED_BRANCH)

/** A stage whose branch is pull request #4's — closed unmerged, in the recording. */
private val AT_PULL_4 = StageOnGitHub("TOY/P0/S0.9", "Collite/kp-toy", CLOSED_BRANCH)

/** The local GitHub: `Collite/kp-toy`'s recorded answers and the App endpoints on one port, `kp-merger` made with a key in [keys]. */
private class Toy(val keys: Path) : AutoCloseable {
    private val server = RecordedGitHub.local()
    val github = RecordedGitHub(server)
    val fake = FakeApps(server, keys).also { it.add(KP_MERGER) }
    val api: URI = github.api
    val apps = GitHubApps(keys, api)

    init {
        server.start()
    }

    override fun close() = server.stop(0)
}
