package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kp.facts.Answer
import kp.facts.filedIssue
import kp.facts.pull
import kp.facts.toyPullRequests
import kp.mailroom.Status

/** The slices of T5, for the next developer: a triage's `found` through the wiring, and a stage whose list cannot be read. */
class GitHubOperationsSpec : FreeSpec({
    "a triage whose outcome is `issue` files its `found` as one issue, its text the title" {
        ActingOffice(mapOf("TOY/P0/S0.1" to "building")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 9)))

            office.send("principal-1", """{"kind": "triage", "stage": "TOY/P0/S0.1", "subject": "triage", "args": {"outcome": "issue", "found": "a wake's prompt names its stage as StageId(value=…)", "since": "m-panda-000001"}}""").status shouldBe Status.DELIVERED
            office.awaitResult("issue.file")

            office.ops("issue.file").last() shouldBe """ok {"numbers":[9]}"""
            Json.parseToJsonElement(office.github.requests.single { it.method == "POST" }.body) shouldBe Json.parseToJsonElement(
                """{"title": "a wake's prompt names its stage as StageId(value=…)", "body": "Filed for TOY/P0/S0.1 · asked by ${office.idOf("triage")} · pull request Collite/kp-toy#1"}""")
        }
    }

    "an operation for a stage with no `worktree.create` record — here, no list either — is failed with what was missing, the journal says so, and GitHub is asked nothing (Amendment 43)" {
        ActingOffice(mapOf("TOY/P0/S0.2" to "building")).use { office ->
            office.send("principal-1", """{"kind": "triage", "stage": "TOY/P0/S0.2", "subject": "triage", "args": {"outcome": "issue", "found": "one", "since": "m-panda-000001"}}""").status shouldBe Status.DELIVERED
            office.awaitResult("issue.file")

            val missing = "TOY/P0/S0.2 has no worktree.create that ended ok: no repository and no branch are fixed for it (contracts.md §4.4, Amendment 40)"
            office.ops("issue.file").last() shouldBe "failed where TOY/P0/S0.2 acts on GitHub is unknown: $missing"
            office.journal.last() shouldBe "github: issue.file for ${office.idOf("triage")} failed — where TOY/P0/S0.2 acts on GitHub is unknown: $missing"
            office.github.requests.size shouldBe 0
            office.apps.requests.size shouldBe 0
        }
    }
})
