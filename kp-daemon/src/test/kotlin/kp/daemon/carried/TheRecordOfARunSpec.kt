@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.carried

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kp.daemon.seats.Seats
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * S3.5 T5 — the record of a run on the office (`SeatsAtPlay`; contracts.md §12, Amendments 27, 38, 46): the stage's
 * records of the office's log named by `seq` and not copied, both transcripts, each seat's turns weighted and by their
 * parts, every message a seat sent beyond the walk, each operation with its result, the pull request and the merge
 * commit — and no token of any kind: no session token, no installation token, no key, not the Claude token.
 */
class TheRecordOfARunSpec : StringSpec({
    "the record of the fixture's run holds what T5 names, and a scan of it finds no token of any kind" {
        Carried().use { carried ->
            val rehearsal = carried.start()
            rehearsal.awaitPlayed(180_000) shouldBe true
            rehearsal.passed shouldBe true
            val minted = rehearsal.seated!!.seats.mintedDigests()
            rehearsal.close()

            val evidence = carried.evidence.listDirectoryEntries("first-carried-stage-*.md").single()
            val record = evidence.resolveSibling(evidence.name.removeSuffix(".md"))
            val text = evidence.readText()
            // The log is the office's: named by seq, not copied.
            record.resolve("mail").exists() shouldBe false
            text shouldContain "## The stage's records on the office's log, by seq"
            val proposal = carried.lines().filterIsInstance<kp.model.Message>().first { it.kind == "assignment-proposal" }
            text shouldContain "- seq ${proposal.seq} · assignment-proposal ${proposal.id.value} from teamlead (script:first-carried-stage) to bora"
            // Both transcripts, copied beside the evidence; each seat's turns weighted and by their parts.
            listOf("coder-1", "reviewer-1").forEach { seat -> record.resolve("transcripts/$seat.log").isRegularFile() shouldBe true }
            text shouldContain "## What each turn used, by its parts"
            text shouldContain Regex("\\| coder-1 \\| [0-9]+ turns? \\| ")
            text shouldContain Regex("\\| reviewer-1 \\| [0-9]+ turns? \\| ")
            text shouldContain "## What the seats sent beyond the walk's steps"
            // Each operation with its result; the pull request and the merge commit.
            text shouldContain "## Each operation, with its result"
            listOf("worktree.create", "seat.start", "token.mint", "seat.reseat", "git.merge", "worktree.remove", "register.rebuild", "project.commit_push")
                .forEach { op -> text shouldContain Regex("- ${Regex.escape(op)} for m-panda-[0-9]+ \\(seq [0-9]+\\): ok \\(seq [0-9]+\\)") }
            val (merge, _) = carried.office.github.merged()!!
            text shouldContain "the merge commit: $merge"
            text shouldContain "- the pull request: the stage's, in kp-toy from $CARRIED_BRANCH — its head at the merge's judgement: "

            // No token of any kind, in the evidence or in anything the record keeps.
            val kept = listOf(evidence) + record.walk().filter { it.isRegularFile() }.toList()
            val secrets = carried.office.apps.issued.map { it.token } + listOf("kp-merger", "kp-coder", "kp-reviewer", "kp-gardener").map { identity ->
                carried.office.apps.pem(identity).lines().first { it.isNotBlank() && !it.startsWith("-----") }
            } + carried.office.secrets.resolve("claude-token").readText().trim()
            kept.forEach { file ->
                val content = file.readText()
                secrets.forEach { secret -> content shouldNotContain secret }
                content shouldNotContain "PRIVATE KEY"
                Regex("[A-Za-z0-9_-]{20,}").findAll(content).map { it.value }.filter { Seats.sha256(it) in minted }.toList().shouldBeEmpty()
            }
        }
    }
})
