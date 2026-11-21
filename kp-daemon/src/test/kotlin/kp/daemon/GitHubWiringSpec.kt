package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.engine.Datum
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_HEAD
import kp.facts.byTheReviewer
import kp.facts.pull
import kp.facts.toyCheckRuns
import kp.facts.toyPullRequests
import kp.facts.toyReviews
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The slices of T6, for the next developer: `just apps`, and how the service's world asks GitHub. */
class GitHubWiringSpec : FreeSpec({
    "`just apps` prints each App's identity, the name GitHub shows, the repositories it reaches, and for each role whether its permissions can be minted" {
        GitHubOffice().use { office ->
            val lines = mutableListOf<String>()

            Apps.run(office.office.rules, "panda", office.api) { lines += it } shouldBe 0

            lines.drop(1) shouldBe listOf(
                "  kp-merger — GitHub shows kp-merger — app 5849229, installation 184045472 — reaches Collite/keporkak, Collite/kp-toy, Collite/project",
                "  kp-coder — GitHub shows collite-kp-coder — app 5849303, installation 184046595 — reaches Collite/keporkak, Collite/kp-toy, Collite/project",
                "  kp-reviewer — GitHub shows kp-reviewer — app 5849353, installation 184047637 — reaches Collite/keporkak, Collite/kp-toy, Collite/project",
                "  kp-gardener — GitHub shows kp-gardener — app 5849393, installation 184048423 — reaches Collite/keporkak, Collite/kp-toy, Collite/project",
                "Each role's permissions (github.seats), minted for every repository its App reaches:",
                "  coder as kp-coder — contents write, pull_requests write — can be minted",
                "  reviewer as kp-reviewer — checks read, contents read, pull_requests write — can be minted",
                "  gardener as kp-gardener — contents read, issues write, pull_requests read — can be minted",
                "  principal as kp-reviewer — contents read, pull_requests read — can be minted",
                "  teamlead as kp-reviewer — contents read, pull_requests read — can be minted",
                "  deputy as kp-reviewer — contents read, pull_requests read — can be minted",
                "  retro as kp-reviewer — contents read, pull_requests read — can be minted",
                "apps: every answer is yes",
            )
            office.apps.requests.filter { it.target.endsWith("/access_tokens") }.take(4).map { it.body } shouldBe List(4) { """{"permissions":{"metadata":"read"}}""" }

            office.apps.withdraw("kp-gardener", "issues")
            lines.clear()
            Apps.run(office.office.rules, "panda", office.api) { lines += it } shouldBe 1
            lines shouldContainAll listOf(
                "  gardener as kp-gardener — contents read, issues write, pull_requests read — cannot be minted: GitHub answered 422 to POST /app/installations/184048423/access_tokens: The permissions requested are not granted to this installation.",
                "apps: 1 answer is no",
            )
        }
    }

    "the world takes `ci.state`'s sha as the rules write it, `of`, and the stage from the message; `pr.review_on` asks GitHub the reviewer's name with kp-reviewer's claim" {
        GitHubOffice().use { office ->
            office.github.toyPullRequests(pull())
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-13T12:10:00Z"))

            office.observe("ci.state", "of" to Datum.Text(TOY_HEAD)) shouldBe Datum.Text("green")
            office.observe("pr.review_on", "sha" to Datum.Text(TOY_HEAD)) shouldBe Datum.Text("approved")
            office.apps.requests.filter { it.target == "/app" }.size shouldBe 1
            office.apps.issued.map { "${it.app.identity} ${it.repositories} ${it.permissions}" }.distinct() shouldBe
                listOf("kp-merger [kp-toy] {checks=read, contents=read, pull_requests=read}")
        }
    }

    "with no block `github:` in force, every GitHub fact is unknown and the journal says why; a fact it does not hold is not its to answer" {
        GitHubOffice().use { office ->
            val offices = office.root.resolve("pool/offices.yaml")
            offices.writeText(offices.readText().substringBefore("# GitHub (contracts.md §9"))
            office.office.reloadIfChanged()

            office.observe("pr.ready") shouldBe null
            office.journal.joinToString("\n") shouldContain "facts: pr.ready for TOY/P0/S0.1 is unknown — pool/offices.yaml has no block github:"
            office.world.github.observe(kp.engine.FactRef("stage.rolls"), "TOY/P0/S0.1") shouldBe null
            office.apps.requests.size shouldBe 0
        }
    }
})
