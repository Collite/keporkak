package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kp.engine.Datum
import kp.facts.Answer
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_HEAD
import kp.facts.filedIssue
import kp.facts.pull
import kp.facts.toyCheckRuns
import kp.facts.toyPullRequests
import kp.mailroom.Status
import java.util.concurrent.TimeUnit

/**
 * KP/P3/S3.9 — the daemon acts on GitHub (T1's wiring sentences; T5). The office is the service's on GitHub
 * ([ActingOffice]): a judgement through [Office] requests the operation, the daemon's [GitHubOperations] performs it with
 * `kp-ops` as `kp-merger`, in front of one local server that answers with what was recorded from `Collite/kp-toy` and as
 * GitHub's App endpoints do. Its seams are the daemon's own: a message judged, the `op` records on the log, the journal,
 * a start on a log that ends with an operation in flight. The performers' own ends are `kp-ops`' `OperationsOnGitHubSpec`.
 */
class TheDaemonActsOnGitHubSpec : FreeSpec({
    "the head a merge is asked on is the one its judgement observed, handed from the log; the `op` record carries the merge commit, and no token is in it, the journal or anything the daemon printed" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "ci.state"), mergeReadsTheHead = true).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", Answer(200, recorded("merge-200.json")))

            val printed = printedDuring {
                office.send("teamlead", MERGE_THE_TOY).status shouldBe Status.DELIVERED
                office.awaitResult("git.merge")
            }

            office.observedOf("merge")["pr.head"] shouldBe "\"028e9e2dece9c94f65f3c0da144e6f2ce3c9f013\""
            Json.parseToJsonElement(office.github.requests.single { it.method == "PUT" }.body) shouldBe
                Json.parseToJsonElement("""{"sha": "028e9e2dece9c94f65f3c0da144e6f2ce3c9f013", "merge_method": "merge"}""")
            office.ops("git.merge") shouldBe listOf("requested null", """ok {"sha":"0c6b33afb43dd4addb3147ad73edf4c53087a17a"}""")
            office.state() shouldBe "merged"
            val tokens = office.apps.issued.map { it.token }.also { it.shouldNotBeEmpty() }
            tokens.filter { token -> (listOf(printed, office.logText()) + office.journal).any { token in it } }.shouldBeEmpty()
        }
    }

    "a `merge` whose judgement observed no `pr.head` — master's rules read none — is `refused`, and the local server saw no request" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed")).use { office ->
            office.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", Answer(200, recorded("merge-200.json")))

            office.send("teamlead", MERGE_THE_TOY).status shouldBe Status.DELIVERED
            office.awaitResult("git.merge")

            office.observedOf("merge") shouldNotContainKey "pr.head"
            office.ops("git.merge") shouldBe listOf("requested null", "failed refused: the judgement of ${office.idOf("merge")} observed no pr.head: there is no head to merge on")
            office.github.requests.shouldBeEmpty()
            office.apps.requests.shouldBeEmpty()
        }
    }

    "a daemon killed while a held merge runs records it `failed: interrupted` at its start and sends GitHub nothing: its server saw the one merge, before the kill" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "ci.state"), mergeReadsTheHead = true).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            val held = office.github.hold("PUT", "/repos/Collite/kp-toy/pulls/1/merge")
            office.send("teamlead", MERGE_THE_TOY).status shouldBe Status.DELIVERED
            held.received.await(30, TimeUnit.SECONDS) shouldBe true
            val asked = office.github.requests.size + office.apps.requests.size

            office.restart()

            office.ops("git.merge") shouldBe listOf("requested null", "failed interrupted")
            office.github.requests.size + office.apps.requests.size shouldBe asked
            office.github.requests.filter { it.method == "PUT" }.size shouldBe 1
            held.release()
        }
    }

    "a `close` closes the stage's pull request, and a verdict's follow-ups are filed in their order, each body naming the stage, the verdict and the pull request" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "recut")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/1", Answer(200, recorded("pull-closed.json")))

            office.send("teamlead", """{"kind": "recut-notice", "stage": "TOY/P0/S0.1", "subject": "S0.1 is recut"}""").status shouldBe Status.DELIVERED
            office.send("teamlead", """{"kind": "close", "stage": "TOY/P0/S0.1", "subject": "close S0.1"}""").status shouldBe Status.DELIVERED
            office.awaitResult("pr.close")

            office.ops("pr.close") shouldBe listOf("requested null", """ok {"closed":"Collite/kp-toy#1"}""")
            office.github.requests.single { it.method == "PATCH" }.body shouldBe """{"state":"closed"}"""
        }
        ActingOffice(mapOf("TOY/P0/S0.1" to "in_review"), table = mapOf("pr.head" to Datum.Text(TOY_HEAD), "pr.review_on" to Datum.Text("approved"))).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 7)), Answer(201, filedIssue("number" to 8)))

            office.send("reviewer-1", """{"kind": "verdict", "stage": "TOY/P0/S0.1", "subject": "PASS-FU", "args": {"verdict": "PASS-FU", "sha": "$TOY_HEAD",
                "follow_ups": ["Count a cache read once\nA cache read is counted as a turn.", "Name the stage in a wake's prompt"]}}""").status shouldBe Status.DELIVERED
            office.awaitResult("issue.file")

            val verdict = office.idOf("verdict")
            office.ops("issue.file").last() shouldBe """ok {"numbers":[7,8]}"""
            office.github.requests.filter { it.method == "POST" }.map { Json.parseToJsonElement(it.body) } shouldBe listOf(
                Json.parseToJsonElement("""{"title": "Count a cache read once", "body": "A cache read is counted as a turn.\n\nFiled for TOY/P0/S0.1 · asked by $verdict · pull request Collite/kp-toy#1"}"""),
                Json.parseToJsonElement("""{"title": "Name the stage in a wake's prompt", "body": "Filed for TOY/P0/S0.1 · asked by $verdict · pull request Collite/kp-toy#1"}"""),
            )
            office.state() shouldBe "passed"
        }
    }
})
