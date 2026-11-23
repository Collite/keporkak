package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.facts.RecordedGitHub.Companion.checkRuns
import kp.facts.RecordedGitHub.Companion.recorded
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * KP/P3/S3.2 — facts from GitHub (T1's first three sentences). The seam is `kp-facts`' entry for GitHub's facts in front
 * of a local HTTP server that answers with what was recorded on Panda from `Collite/kp-toy`'s merged pull request #1
 * ([RecordedGitHub]; the recordings are under `kp-facts/src/testFixtures/resources/github/kp-toy/`). A state the toy has
 * none of is that recording with the fields GitHub documents changed, as each spec shows. The stage is the toy's
 * `TOY/P0/S0.1`, whose list names `kp-toy` and the branch of #1 ([Toy]). Minting is `kp-ops`' (`MintSpec`); the daemon's
 * asking, its journal and what no output holds are `kp-daemon`'s `TheDaemonReadsGitHubSpec`.
 */
class GitHubFactsSpec : FreeSpec({
    "the facts `kp-facts` answers from GitHub are exactly the `pr.*` facts and `ci.state` of the vocabulary, and each has a spec named after it" {
        val vocabulary = vocabularyFacts().filter { it.startsWith("pr.") || it == "ci.state" }

        vocabulary.size shouldBe 9
        GitHubFacts.ANSWERED.keys.sorted() shouldBe vocabulary.sorted()
        gitHubSpecNames() shouldContainAll vocabulary
    }

    "`pr.ready`, `pr.closed` and `pr.merged` on the five states of a stage's pull request — open, a draft, merged, closed unmerged, none" {
        Toy(tempdir()).use { toy ->
            fun states() = listOf("pr.ready", "pr.closed", "pr.merged").map { toy.observe(it).value }

            toy.pullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            states() shouldBe listOf(true, false, false)
            toy.pullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null, "draft" to true))
            states() shouldBe listOf(false, false, false)
            toy.pullRequests(pull())
            states() shouldBe listOf(false, false, true)
            toy.pullRequests(pull("merged_at" to null))
            states() shouldBe listOf(false, true, false)
            toy.pullRequests()
            states() shouldBe listOf(false, false, false)
        }
    }

    "`pr.head` is the sha of the head of the stage's pull request; with none, it and every other `pr.*` fact is unknown" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.observe("pr.head").value shouldBe TOY_HEAD

            toy.pullRequests()
            toy.log.heads["m-panda-000040"] = TWO_BACK
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            listOf(
                toy.observe("pr.head"), toy.observe("pr.review_on", "sha" to TOY_HEAD), toy.observe("pr.commits_since", "message" to "m-panda-000040"),
                toy.observe("pr.blocker_responses", "ids" to listOf("B1")), toy.observe("pr.evidence_present"),
            ).map { it.missing } shouldBe List(5) { "Collite/kp-toy holds no pull request whose head is $TOY_BRANCH" }
        }
    }

    "each of the nine facts is unknown, with GitHub's reason, when GitHub refuses, when what it reads is not there, and when it limits" {
        Toy(tempdir()).use { toy ->
            toy.log.heads["m-panda-000040"] = TWO_BACK
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            fun reasons() = GitHubFacts.ANSWERED.keys.sorted().map { fact ->
                toy.observe(fact, "sha" to TOY_HEAD, "message" to "m-panda-000040", "ids" to listOf("B1")).missing.orEmpty()
            }

            toy.github.onEvery(Answer(403, """{"message":"Resource not accessible by integration","documentation_url":"https://docs.github.com/rest","status":"403"}"""))
            reasons().forEach { it shouldContain "GitHub answered 403 to GET repos/Collite/kp-toy/" ; it shouldContain ": Resource not accessible by integration" }
            toy.github.onEvery(Answer(404, recorded("repo-404.json")))
            reasons().forEach { it shouldContain "GitHub answered 404 to GET repos/Collite/kp-toy/"; it shouldContain ": Not Found" }
            toy.github.onEvery(Answer(403, """{"message":"API rate limit exceeded for installation ID 184045472.","status":"403"}""",
                mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1795255200")))
            reasons().forEach { it shouldStartWith "GitHub limits: GitHub answered 403 to GET repos/Collite/kp-toy/"; it shouldContain "(x-ratelimit-remaining 0, resets 2026-11-21T10:00:00Z)" }
        }
    }

    "`ci.state` of a sha, from its check runs: none, pending, red and green — and red when the only failed run is on the second of two pages" {
        Toy(tempdir()).use { toy ->
            fun ciState() = toy.observe("ci.state", "sha" to TOY_HEAD).value

            toy.checkRuns(TOY_HEAD, checkRuns())
            ciState() shouldBe "none"
            toy.checkRuns(TOY_HEAD, checkRuns(run(), run("status" to "in_progress", "conclusion" to null), run()))
            ciState() shouldBe "pending"
            toy.checkRuns(TOY_HEAD, checkRuns(run(), run("conclusion" to "failure"), run()))
            ciState() shouldBe "red"
            toy.checkRuns(TOY_HEAD, recorded("check-runs.json"))
            ciState() shouldBe "green"
            toy.checkRuns(TOY_HEAD, checkRuns(run(), run("conclusion" to "skipped"), run("conclusion" to "neutral")))
            ciState() shouldBe "green"
            toy.checkRuns(TOY_HEAD, checkRuns(run(), run()), checkRuns(run("conclusion" to "failure")))
            ciState() shouldBe "red"
        }
    }

    "`pr.review_on` a sha is the reviewer's App's last review on that commit: on the head, on an older commit, two on the head — and an approval by the author or by anyone else is not read" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            fun reviewOn(sha: String) = toy.observe("pr.review_on", "sha" to sha).value

            toy.reviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:10:00Z"))
            reviewOn(TOY_HEAD) shouldBe "approved"
            toy.reviews(byTheReviewer(ONE_BACK, "CHANGES_REQUESTED", "2026-11-13T11:40:00Z"))
            reviewOn(TOY_HEAD) shouldBe "none"
            reviewOn(ONE_BACK) shouldBe "changes_requested"
            toy.reviews(byTheReviewer(TOY_HEAD, "CHANGES_REQUESTED", "2026-11-13T12:10:00Z"), byTheReviewer(TOY_HEAD, "COMMENTED", "2026-11-13T12:20:00Z"))
            reviewOn(TOY_HEAD) shouldBe "commented"
            toy.reviews(review("state" to "APPROVED"), review("user.login" to "someone-else", "state" to "APPROVED"))
            reviewOn(TOY_HEAD) shouldBe "none"
        }
    }

    "`pr.commits_since` a message whose judgement observed a head two commits back is 2; one that observed no head, or a head GitHub no longer knows, is unknown" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.compare(TWO_BACK, TOY_HEAD, Answer(200, recorded("compare.json")))
            toy.log.heads["m-panda-000040"] = TWO_BACK
            toy.log.heads["m-panda-000042"] = "0000000000000000000000000000000000000001"
            toy.compare("0000000000000000000000000000000000000001", TOY_HEAD, Answer(404, recorded("compare-404.json")))

            toy.observe("pr.commits_since", "message" to "m-panda-000040").value shouldBe 2
            toy.observe("pr.commits_since", "message" to "m-panda-000041").missing shouldBe "the judgement of m-panda-000041 observed no pr.head"
            toy.observe("pr.commits_since", "message" to "m-panda-000042").missing!! shouldContain "GitHub answered 404 to GET repos/Collite/kp-toy/compare/"
        }
    }

    "`pr.blocker_responses` for B1 and B2: the ids a line of the author's comments after the last verdict begins with — not before it, not anyone else's, and B10 answers no B1" {
        Toy(tempdir()).use { toy ->
            toy.pullRequests(pull())
            toy.log.delivered(TOY_STAGE, "verdict", "2026-11-13T11:30:00Z")
            toy.comments(
                comment("created_at" to "2026-11-13T11:20:00Z", "body" to "B2: answered before the verdict"),
                comment("created_at" to "2026-11-13T11:52:00Z", "body" to "Fixed:\nB1 — the empty run fails\nB10: not B1"),
                comment("created_at" to "2026-11-13T11:53:00Z", "user.login" to "someone-else", "body" to "B2: answered by someone else"),
            )

            toy.observe("pr.blocker_responses", "ids" to listOf("B1", "B2")).value shouldBe listOf("B1")
            // The recorded answer, `- B1 — Fixed in …`, answers B1: a list marker may come before the id (Amendment 38, S3.10).
            toy.comments(comment())
            toy.observe("pr.blocker_responses", "ids" to listOf("B1")).value shouldBe listOf("B1")
        }
    }

    "`pr.evidence_present` is true when the description holds the hand-off's six headings and text under `## Verified`; false when a heading is missing or nothing is under it" {
        Toy(tempdir()).use { toy ->
            val body = pull()["body"].toString().let { kotlinx.serialization.json.Json.decodeFromString<String>(it) }

            toy.pullRequests(pull())
            toy.observe("pr.evidence_present").value shouldBe true
            toy.pullRequests(pull("body" to body.replace("## Not in this stage", "## Out of scope")))
            toy.observe("pr.evidence_present").value shouldBe false
            toy.pullRequests(pull("body" to body.replace(Regex("(?s)## Verified\n.*?(?=## Not in this stage)"), "## Verified\n\n")))
            toy.observe("pr.evidence_present").value shouldBe false
        }
    }
})

/** The facts of `rules/vocabulary.yaml`: the keys of its `facts:` map. */
private fun vocabularyFacts(): List<String> {
    val lines = Path.of(System.getProperty("kp.root")).resolve("rules/vocabulary.yaml").readLines()
    val section = lines.dropWhile { it != "facts:" }.drop(1).takeWhile { it.isEmpty() || it.startsWith(" ") }
    return section.mapNotNull { Regex("^  ([a-z_]+\\.[a-z_]+):$").find(it)?.groupValues?.get(1) }
}

/**
 * The facts `kp-facts`' specs are named after: a spec is named after the facts its name begins with, in backticks —
 * "`pr.ready`, `pr.closed` and `pr.merged` …".
 */
private fun gitHubSpecNames(): Set<String> {
    val sources = Path.of(System.getProperty("kp.root")).resolve("kp-facts/src/test/kotlin")
    val named = Regex("""^\s*"((?:`[a-z_]+\.[a-z_]+`(?:, | and )?)+)[^"]*" \{""", RegexOption.MULTILINE)
    return sources.walk().filter { it.isRegularFile() && it.toString().endsWith(".kt") }
        .flatMap { source -> named.findAll(source.readText()).flatMap { Regex("`([a-z_]+\\.[a-z_]+)`").findAll(it.groupValues[1]).map { f -> f.groupValues[1] } } }
        .toSet()
}
