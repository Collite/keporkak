package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kp.facts.ProjectFacts
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.walk

/**
 * S3.8 T5 — `just facts <stage>` ([StageFacts]; contracts.md §12, Amendment 34), on the checkouts of S3.1's fixture office
 * ([FactsOffice]): every fact of the project and of git for the stage, its phase and its effort, read by the daemon's own
 * world with the log's word, and nothing written.
 */
/** GitHub, for `just facts` in a spec: a port nothing listens on — the office's keys never reach the real one (S3.11). */
private val NOWHERE = java.net.URI.create("http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}/")

class StageFactsSpec : FreeSpec({
    "`just facts` on a stage with no `worktree.create` record says, for each of GitHub's nine facts and for `git.branch_exists`, that it is unknown and why — and asks GitHub nothing (S3.11; Amendments 40, 43)" {
        FactsOffice(states = mapOf("KP/P3/S3.2" to "assigned")).use { office ->
            val noRecord = "KP/P3/S3.2 has no worktree.create that ended ok: no repository and no branch are fixed for it (contracts.md §4.4, Amendment 40)"

            val printed = StageFacts.lines(office.rules, "panda", office.project, "KP/P3/S3.2", api = NOWHERE)

            (kp.facts.GitHubFacts.ANSWERED.keys + "git.branch_exists").map { name -> printed.single { it.startsWith("$name(") } }
                .map { it.substringAfter(" unknown — ", "a value: $it") }.toSet() shouldBe setOf(noRecord)
        }
    }

    "`just facts` prints every fact of Amendments 32 and 34 once — each with its value and evidence, or unknown and what is missing — with the log's word for a stage the log knows, and changes no file or ref of the project checkout or a main checkout" {
        FactsOffice(states = mapOf("KP/P3/S3.1" to "building")).use { office ->
            val before = bytes(office.project, office.keporkak, office.toy)

            val printed = StageFacts.lines(office.rules, "panda", office.project, "KP/P3/S3.1", api = NOWHERE)

            bytes(office.project, office.keporkak, office.toy) shouldBe before
            (ProjectFacts.ANSWERED.keys + ProjectFacts.NOT_READ_YET.keys).map { name -> printed.count { it.startsWith("$name(") || it.startsWith("$name ") } }.toSet() shouldBe setOf(1)
            printed shouldContain "the log: KP/P3/S3.1 is building since 2026-11-20T08:00:00Z — the log's word · ${office.office.lines().size} lines, at seq ${office.office.state.seq}"
            printed shouldContain "plan.predecessors_exited(stage=KP/P3/S3.1) = true — plan.md#L5 (after —)"
            printed shouldContain "plan.wave_of(stage=KP/P3/S3.1) = \"current\" — plan.md#L5 (after —); log: KP/P3/S3.1 building"
            printed shouldContain "stage.touches(stage=KP/P3/S3.1) = \"kp-facts/\" — tasks-p3-s3.1-facts.md#L2"
            printed shouldContain "list.task_exists(stage=KP/P3/S3.1) unknown — no parameter 'id'"
            printed.single { it.startsWith("plan.last_stage_exited(") } shouldStartWith "plan.last_stage_exited(phase=KP/P3) = false — "
            printed.single { it.startsWith("status.entries(") } shouldStartWith "status.entries(effort=KP) = 2 — STATUS.md#L3-L4"
            printed.single { it.startsWith("git.stray_changes(") } shouldStartWith "git.stray_changes(office=panda) = 0 — keporkak: 0 changed paths"
        }
    }

    "`just facts` prints the pool's facts and the budgets' (S3.13 T7): the three slots open and over, the floor's rank, the office's computer use, and each subscription's consumption and its day against the daily median — each with its evidence, or unknown and why" {
        FactsOffice(states = mapOf("KP/P3/S3.1" to "building")).use { office ->
            val printed = StageFacts.lines(office.rules, "panda", office.project, "KP/P3/S3.1")
            val budgets = checkout.resolve("pool/budgets.yaml")

            printed shouldContain "budget.slot_open(dimension=office) = true — office panda: in use 0 — none — of pool/budgets.yaml line ${lineOf(budgets, "slots:")} (slots.per_office.panda: 4)"
            printed shouldContain "budget.slots_over(dimension=effort) = 0 — effort KP: in flight 1 — KP/P3/S3.1 building — of pool/budgets.yaml line ${lineOf(budgets, "slots:")} (slots.stages_in_flight_per_effort: 2)"
            listOf("budget.slot_open(dimension=subscription) = ", "budget.slot_open(dimension=effort) = ", "budget.slots_over(dimension=office) = 0 — ",
                "budget.slots_over(dimension=subscription) = 0 — ", "roster.floor_rank = ", "office.computer_use(office=panda) = \"off\" — ").forEach { start ->
                printed.filter { it.startsWith(start) }.size shouldBe 1
            }
            printed shouldContain "budget.consumption(subscription=claude, window=subscription) unknown — the vendor has reported no window of claude"
            printed.single { it.startsWith("budget.vs_daily_median(subscription=claude) unknown — ") } shouldStartWith
                "budget.vs_daily_median(subscription=claude) unknown — claude has turns on 0 days before "
        }
    }

    "`just facts` on a stage the log does not know reads what the project says: the overview's tick" {
        FactsOffice().use { office ->
            val printed = StageFacts.lines(office.rules, "panda", office.project, "KP/P3/S3.1", api = NOWHERE)

            printed shouldContain "the log: no record of KP/P3/S3.1 — the project's word · ${office.office.lines().size} lines, at seq ${office.office.state.seq}"
            printed.single { it.startsWith("plan.wave_of(") } shouldBe "plan.wave_of(stage=KP/P3/S3.1) = \"past\" — plan.md#L5; tasks-overview.md#L2 ticks S3.1"
        }
    }
})

/** Every file under [dirs] — their `.git` too — by path, with a digest of its bytes: what a reader must leave as it was. */
private fun bytes(vararg dirs: Path): Map<String, String> = dirs.flatMap { dir ->
    dir.walk().filter { it.isRegularFile() }.map { file -> file.toString() to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } }.toList()
}.toMap()
