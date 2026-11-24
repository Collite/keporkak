package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kp.engine.Datum
import kp.facts.GitHubFacts
import kp.facts.ONE_BACK
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_HEAD
import kp.facts.TOY_STAGE
import kp.facts.TWO_BACK
import kp.facts.byTheReviewer
import kp.facts.comment
import kp.facts.pull
import kp.facts.toyCheckRuns
import kp.facts.toyComments
import kp.facts.toyCompare
import kp.facts.toyPullRequests
import kp.facts.toyReviews
import kp.facts.Answer as Recorded
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * KP/P3/S3.2 — the daemon reads GitHub (T1's fourth and fifth sentences, the daemon's half). The office's world is the
 * service's ([GitHubOffice]): `kp-facts` reads the toy's pull request with a token `kp-ops` mints from a key of the
 * spec's, in front of one local server that answers with what was recorded from `Collite/kp-toy` and as GitHub's App
 * endpoints do. Its seams are the daemon's own: a judgement through [Office], the world a judgement asks, the journal,
 * the load of the rules, and `just apps` ([Apps]). The block `github:` is master's `pool/offices.yaml`'s.
 */
class TheDaemonReadsGitHubSpec : FreeSpec({
    "the daemon mints as `kp-merger` for exactly the stage's repository and reading, and neither token, key nor claim is in any output, error, journal line or request but the one that carries it" {
        GitHubOffice().use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:10:00Z"))
            office.github.toyComments(comment("body" to "B1: fixed"))
            office.github.toyCompare(TWO_BACK, TOY_HEAD, Recorded(200, recorded("compare.json")))
            val answers = mutableListOf<String>()
            val mints = mutableListOf<kp.ops.AppsRequest>()

            val printed = printedDuring {
                answers += office.send("coder-1", """{"kind": "review-request", "stage": "$TOY_STAGE", "subject": "review", "args": {"attest": ["done_lines_hold"]}}""").toString()
                GitHubFacts.ANSWERED.keys.forEach { fact -> answers += "$fact ${office.observe(fact, "of" to Datum.Text(TOY_HEAD), "sha" to Datum.Text(ONE_BACK), "message" to Datum.Text("m-none"), "ids" to Datum.Items(listOf(Datum.Text("B1"))))}" }
                office.apps.withdraw("kp-merger", "checks")
                answers += "pr.ready ${office.observe("pr.ready")}"
                mints += office.apps.requests.filter { it.target.startsWith("/app/installations/") }
                Apps.run(office.office.rules, "panda", office.api) { answers += it }
            }

            mints.map { Json.parseToJsonElement(it.body) }.distinct() shouldBe
                listOf(Json.parseToJsonElement("""{"repositories": ["kp-toy"], "permissions": {"checks": "read", "contents": "read", "pull_requests": "read"}}"""))
            mints.map { it.target }.distinct() shouldBe listOf("/app/installations/184045472/access_tokens")
            office.journal.joinToString("\n") shouldContain "GitHub answered 422 to POST /app/installations/184045472/access_tokens: The permissions requested are not granted to this installation."

            val tokens = office.apps.issued.map { it.token }.also { it.shouldNotBeEmpty() }
            val claims = office.apps.claims.toList().also { it.shouldNotBeEmpty() }
            val keys = THE_APPS.flatMap { app -> keyParts(office.apps.pem(app.identity)) }
            val outputs = listOf(printed, office.logText()) + office.journal + answers + fixtures()
            (tokens + claims + keys).filter { secret -> outputs.any { it.contains(secret) } }.shouldBeEmpty()
            office.github.requests.filter { request -> (tokens + claims + keys).any { request.target.contains(it) || request.body.contains(it) } }.shouldBeEmpty()
            office.github.requests.map { it.authorization?.removePrefix("Bearer ") }.filter { it !in tokens }.shouldBeEmpty()
            office.apps.requests.filter { it.target.startsWith("/app") }.map { it.authorization?.removePrefix("Bearer ") }.filter { it !in claims }.shouldBeEmpty()
            office.apps.requests.filter { request -> (tokens + claims + keys).any { request.target.contains(it) || request.body.contains(it) } }.shouldBeEmpty()
        }
    }

    "with the local server down, a judgement that reads `pr.ready` is written whole: refused, the fact unknown, the reason in the journal" {
        GitHubOffice(up = false).use { office ->
            office.send("coder-1", """{"kind": "review-request", "stage": "$TOY_STAGE", "subject": "review", "args": {"attest": ["done_lines_hold"]}}""") should
                refusedBy("review-request", """{"fact":"pr.ready"}""")

            office.observedOf("review-request")["pr.ready"] shouldBe NOT_SUPPLIED
            office.journal.joinToString("\n") shouldContain "facts: pr.ready for $TOY_STAGE is unknown — no token to read Collite/kp-toy: GitHub did not answer POST /app/installations/184045472/access_tokens"
            office.endsWithAJudgedLine() shouldBe true
        }
    }

    "the block `github:` with an App that has no id, a role naming an App the block lacks, or a permission neither read nor write fails the daemon's load, naming the line" {
        fun problemsWith(old: String, new: String): List<String> {
            val rules = TestOffice.copyOfTheRules()
            val offices = rules.resolve("pool/offices.yaml")
            offices.readText() shouldContain old
            offices.writeText(offices.readText().replace(old, new))
            return InForceRules.of(RuleFiles(rules).read()).second.problems.map { it.toString() }
        }

        // The lines as master's file holds them: each change keeps its line where it is.
        val merger = lineOf(checkout.resolve("pool/offices.yaml"), "    kp-merger: {app: 5849229, installation: 184045472}")
        val coder = lineOf(checkout.resolve("pool/offices.yaml"), "    coder: {app: kp-coder,")

        problemsWith("    kp-merger: {app: 5849229, installation: 184045472}", "    kp-merger: {installation: 184045472}") shouldContain
            "pool/offices.yaml: github: apps.kp-merger has no app id (line $merger)"
        problemsWith("    coder: {app: kp-coder,", "    coder: {app: kp-coders,") shouldContain
            "pool/offices.yaml: github: seats.coder names the App 'kp-coders', which github.apps does not hold (line $coder)"
        problemsWith("    coder: {app: kp-coder, permissions: {contents: write,", "    coder: {app: kp-coder, permissions: {contents: admin,") shouldContain
            "pool/offices.yaml: github: seats.coder gives contents 'admin', which is neither read nor write (line $coder)"
    }
})

/** A key's PEM text, its base64 body whole, and each of its lines: a key leaks in any of these shapes. */
private fun keyParts(pem: String): List<String> {
    val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }
    return listOf(pem.trim(), body.joinToString("")) + body.filter { it.length >= 32 }
}

/** The text of every fixture of the stage: the recorded responses and the test fixtures' sources of `kp-facts` and `kp-ops`. */
private fun fixtures(): List<String> = listOf("kp-facts/src/testFixtures", "kp-ops/src/testFixtures", "kp-facts/src/test", "kp-ops/src/test").flatMap { folder ->
    Path.of(System.getProperty("kp.root")).resolve(folder).walk().filter { it.isRegularFile() }.map { it.readText() }.toList()
}
