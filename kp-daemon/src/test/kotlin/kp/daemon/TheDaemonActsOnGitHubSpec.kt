package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kp.engine.Datum
import kp.facts.Answer
import kp.facts.MOVED_HEAD
import kp.facts.RecordedGitHub.Companion.jsonList
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_HEAD
import kp.facts.byTheReviewer
import kp.facts.toyReviews
import kp.facts.filedIssue
import kp.facts.mergesOnItsHead
import kp.facts.pull
import kp.facts.toyCheckRuns
import kp.facts.toyPullRequests
import kp.mailroom.Status
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * KP/P3/S3.9 — the daemon acts on GitHub (T1's wiring sentences; T5), on master's rules since D-108 (S3.11 T4): a `merge`
 * reads the reviewer's review and the checks on `pr.head`, and so observes the head it merges on. The office is the service's on GitHub
 * ([ActingOffice]): a judgement through [Office] requests the operation, the daemon's [GitHubOperations] performs it with
 * `kp-ops` as `kp-merger`, in front of one local server that answers with what was recorded from `Collite/kp-toy` and as
 * GitHub's App endpoints do. Its seams are the daemon's own: a message judged, the `op` records on the log, the journal,
 * a start on a log that ends with an operation in flight. The performers' own ends are `kp-ops`' `OperationsOnGitHubSpec`.
 */
class TheDaemonActsOnGitHubSpec : FreeSpec({
    "the head a merge is asked on is the one its judgement observed, handed from the log; the `op` record carries the merge commit, and no token is in it, the journal or anything the daemon printed" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-22T08:30:00Z"))
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

    "a head that moved after the judgement observed it: the merge is asked on the head handed from the log, not on the one GitHub lists when it runs, and ends `stale-ci`" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            val head = AtomicReference(TOY_HEAD)
            val listed = CopyOnWriteArrayList<String>()
            office.github.onRequest("GET", "/repos/Collite/kp-toy/pulls") {
                Answer(200, jsonList(pull("state" to "open", "closed_at" to null, "merged_at" to null, "head.sha" to head.get().also(listed::add))))
            }
            // A push lands on the branch once the judgement has read the check runs of the head it observed.
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-22T08:30:00Z"))
            office.github.onRequest("GET", "/repos/Collite/kp-toy/commits/$TOY_HEAD/check-runs") { Answer(200, recorded("check-runs.json")).also { head.set(MOVED_HEAD) } }
            office.github.mergesOnItsHead("/repos/Collite/kp-toy/pulls/1/merge") { head.get() }

            office.send("teamlead", MERGE_THE_TOY).status shouldBe Status.DELIVERED
            office.awaitResult("git.merge")

            office.observedOf("merge")["pr.head"] shouldBe "\"$TOY_HEAD\""
            listed.last() shouldBe MOVED_HEAD
            Json.parseToJsonElement(office.github.requests.single { it.method == "PUT" }.body) shouldBe
                Json.parseToJsonElement("""{"sha": "$TOY_HEAD", "merge_method": "merge"}""")
            office.ops("git.merge") shouldBe listOf("requested null",
                "failed stale-ci: GitHub answered 409 to PUT /repos/Collite/kp-toy/pulls/1/merge: Head branch was modified. Review and try the merge again.")
        }
    }

    "a `merge` on a head no review of the reviewer's stands on is refused by master's rules, naming the rule: no `git.merge` is requested, and the local server saw no merge (D-108)" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews()
            office.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", Answer(200, recorded("merge-200.json")))

            office.send("teamlead", MERGE_THE_TOY) should refusedBy("merge", """{"fact":"pr.review_on","sha":{"fact":"pr.head"},"in":["approved","commented"]}""")

            office.observedOf("merge")["pr.head"] shouldBe "\"$TOY_HEAD\""
            office.ops("git.merge").shouldBeEmpty()
            office.github.requests.filter { it.method == "PUT" }.shouldBeEmpty()
        }
    }

    "a daemon killed while a held merge runs records it `failed: interrupted` at its start and sends GitHub nothing: its server saw the one merge, before the kill" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-22T08:30:00Z"))
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
                Json.parseToJsonElement("""{"title": "Count a cache read once", "body": "Count a cache read once\nA cache read is counted as a turn.\n\nFiled for TOY/P0/S0.1 · asked by $verdict · pull request Collite/kp-toy#1"}"""),
                Json.parseToJsonElement("""{"title": "Name the stage in a wake's prompt", "body": "Name the stage in a wake's prompt\n\nFiled for TOY/P0/S0.1 · asked by $verdict · pull request Collite/kp-toy#1"}"""),
            )
            office.state() shouldBe "passed"
        }
    }
})
