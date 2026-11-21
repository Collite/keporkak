package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * S3.2 T2 — `pool/offices.yaml`'s block `github:` (contracts.md §9, Amendment 33), typed and validated; the three broken
 * blocks of T1 are `kp-daemon`'s `TheDaemonReadsGitHubSpec` and `tests/test_s3_2_github_block.py`.
 */
class GitHubBlockSpec : StringSpec({
    "master's block loads typed: the organization, the four Apps' ids, and each role's App and permissions" {
        val github = (Rules.load(filesOnMaster()) as Loaded.Valid).ruleSet.pool.github!!

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
        val offices = files.getValue("pool/offices.yaml")
        val without = offices.substring(0, offices.indexOf("# GitHub (contracts.md §9"))

        (Rules.load(files + ("pool/offices.yaml" to without)) as Loaded.Valid).ruleSet.pool.github shouldBe null
    }

    "no organization, no installation id, an unknown field: each a problem naming its line" {
        problemsAfter("pool/offices.yaml", "  organization: Collite\n", "") shouldBe listOf("pool/offices.yaml: github: organization must be a name (line 29)")
        problemsAfter("pool/offices.yaml", "    kp-coder: {app: 5849303, installation: 184046595}", "    kp-coder: {app: 5849303, installation: 0}") shouldBe
            listOf("pool/offices.yaml: github: apps.kp-coder has no installation id (line 32)")
        problemsAfter("pool/offices.yaml", "  organization: Collite\n", "  organization: Collite\n  owner: bora\n") shouldBe listOf("pool/offices.yaml: github: unknown field 'owner' (line 30)")
    }
})
