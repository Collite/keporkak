package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * S3.2 T2 — `pool/offices.yaml`'s block `github:` (contracts.md §9, Amendment 33), typed and validated; the three broken
 * blocks of T1 are `kp-daemon`'s `TheDaemonReadsGitHubSpec` and `tests/test_s3_2_github_block.py`.
 */
class GitHubBlockSpec : StringSpec({
    "master's block loads typed: the organization, the four Apps' ids, and each role's App and permissions" {
        // Rules that do not load stop here with their problems, each naming its line — not a bare ClassCastException (Collite/keporkak#69, 4).
        val github = when (val loaded = Rules.load(filesOnMaster())) {
            is Loaded.Valid -> loaded.ruleSet.pool.github!!
            else -> error("master's rules do not load: ${loaded.problems.joinToString("; ")}")
        }

        github.organization shouldBe "Collite"
        github.apps shouldBe mapOf(
            "kp-merger" to GitHubApp(5849229, 184045472), "kp-coder" to GitHubApp(5849303, 184046595),
            "kp-reviewer" to GitHubApp(5849353, 184047637), "kp-gardener" to GitHubApp(5849393, 184048423),
        )
        github.seats.keys shouldBe setOf("coder", "reviewer", "gardener", "principal", "teamlead", "deputy", "retro")
        github.seats.getValue("reviewer") shouldBe GitHubSeat("kp-reviewer", mapOf("contents" to "read", "pull_requests" to "write", "checks" to "read"))
    }

    "a file with no block loads, with none" {
        val files = filesOnMaster()
        val without = withoutTheGitHubBlock(files.getValue("pool/offices.yaml"))

        (Rules.load(files + ("pool/offices.yaml" to without)) as Loaded.Valid).ruleSet.pool.github shouldBe null
    }

    "no organization, no installation id, an unknown field: each a problem naming its line" {
        // The lines as master's file holds them: the block's first, which the organization opens, and the coder's App.
        val organization = lineOf(filesOnMaster().getValue("pool/offices.yaml"), "  organization: Collite")
        val coder = lineOf(filesOnMaster().getValue("pool/offices.yaml"), "    kp-coder: {app: 5849303, installation: 184046595}")

        problemsAfter("pool/offices.yaml", "  organization: Collite\n", "") shouldBe listOf("pool/offices.yaml: github: organization must be a name (line $organization)")
        problemsAfter("pool/offices.yaml", "    kp-coder: {app: 5849303, installation: 184046595}", "    kp-coder: {app: 5849303, installation: 0}") shouldBe
            listOf("pool/offices.yaml: github: apps.kp-coder has no installation id (line $coder)")
        problemsAfter("pool/offices.yaml", "  organization: Collite\n", "  organization: Collite\n  owner: bora\n") shouldBe
            listOf("pool/offices.yaml: github: unknown field 'owner' (line ${organization + 1})")
    }

    "a problem of an entry that is not its block's first names that entry's line, not the block's: the reviewer's seat, the gardener's App (Collite/keporkak#69, 5)" {
        // In a YAML block map the map's node starts on its first entry's line: `seats` on the coder's, `apps` on kp-merger's.
        val offices = filesOnMaster().getValue("pool/offices.yaml")
        val reviewer = "    reviewer: {app: kp-reviewer, permissions: {contents: read, pull_requests: write, checks: read}}"
        val gardener = "    kp-gardener: {app: 5849393, installation: 184048423}"

        problemsAfter("pool/offices.yaml", reviewer, reviewer.replace("app: kp-reviewer", "app: kp-reviewr")) shouldBe
            listOf("pool/offices.yaml: github: seats.reviewer names the App 'kp-reviewr', which github.apps does not hold (line ${lineOf(offices, reviewer)})")
        problemsAfter("pool/offices.yaml", gardener, gardener.replace("app: 5849393", "app: 0")) shouldBe
            listOf("pool/offices.yaml: github: apps.kp-gardener has no app id (line ${lineOf(offices, gardener)})")
        lineOf(offices, reviewer) shouldBeGreaterThan lineOf(offices, "  seats:") + 1
        lineOf(offices, gardener) shouldBeGreaterThan lineOf(offices, "  apps:") + 1
    }
})
