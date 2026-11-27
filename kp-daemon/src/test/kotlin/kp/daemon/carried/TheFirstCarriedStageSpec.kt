package kp.daemon.carried

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.model.Message
import kp.ops.git
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * S3.5 T1 — the first carried stage on the fixture office: `just rehearse first-carried-stage --seats fake --world office`
 * behind the real mailroom, seat API, driver, facts and operations; the fixture's remotes and its local GitHub the world.
 */
class TheFirstCarriedStageSpec : StringSpec({
    "on the fixture office the walk ends exited with fake seats: the toy's merge as kp-merger, the project pushed, the worktree gone, the register naming the stage" {
        Carried().use { carried ->
            val passed = carried.play()
            val told = carried.said.joinToString("\n")
            passed shouldBe true
            told shouldContain "the walk is done: the stage is exited"
            val lines = carried.lines()
            // No record of its log was made by the recorder: the walk's own messages are the script's, Bora's, the seats', the robots'.
            lines.filterIsInstance<Message>().filter { it.by.startsWith("hand:") }.shouldBeEmpty()
            lines.filterIsInstance<Message>().filter { it.stage?.value == CARRIED && it.from.value in setOf("teamlead", "pool") && !it.by.startsWith("robot:") }
                .map { it.by }.distinct() shouldBe listOf("script:first-carried-stage")
            // A seatless role's step is sent by the script and judged like any message; a seat's and Bora's are their own.
            val judged = lines.filterIsInstance<kp.model.Delivered>().map { it.id }.toSet()
            lines.filterIsInstance<Message>().filter { it.by == "script:first-carried-stage" }.all { it.id.value in judged } shouldBe true
            lines.filterIsInstance<Message>().filter { it.stage?.value == CARRIED && it.from.value in setOf("coder-1", "reviewer-1", "bora") }
                .map { "${it.kind} ${it.by}${if (it.signed) " signed" else ""}" } shouldBe listOf("approval bora-cli signed", "started seat:coder-1",
                    "review-request seat:coder-1", "verdict seat:reviewer-1")
            // The step with writes: appended its line before its message: the stage-exit's judgement read the STATUS entry.
            val exit = lines.filterIsInstance<Message>().single { it.kind == "stage-exit" }.id.value
            lines.filterIsInstance<kp.model.Delivered>().single { it.id == exit }.observed?.get("status.entry_present").toString() shouldBe "true"
            // The fixture's toy remote holds the merge commit, made as kp-merger.
            val (merge, by) = checkNotNull(carried.office.github.merged()) { "the pull request was not merged: ${carried.office.github.pulls()}" }
            by shouldBe "kp-merger[bot]"
            git(carried.office.toyRemote.dir, "log", "-1", "--format=%H %an", "master") shouldBe "$merge kp-merger[bot]"
            // The project's bare remote holds the commit project.commit_push made: the log, the list ticked, the STATUS entry.
            carried.office.projectRemote.show("toy/STATUS.md") shouldContain "S0.2 exited."
            carried.office.projectRemote.show("toy/tasks-p0-s0.2-smoke.md") shouldContain "- [x] T6"
            // What changed since is the log alone: the lines the exit's judgements wrote after the push.
            carried.office.status(carried.office.project).lines().filter { it.isNotBlank() }.map { it.trim().substringAfter(" ").trim() }.filterNot { it.startsWith("mail/") }.shouldBeEmpty()
            // The worktree is gone; REGISTER.md names the stage.
            carried.office.worktrees.resolve("toy-p0-s0.2").exists() shouldBe false
            carried.office.projectRemote.show("REGISTER.md") shouldContain "S0.2"
            // `kp check` re-judges the office's log, the stage's among it, with no difference.
            val rules = kp.daemon.InForceRules.of(kp.daemon.RuleFiles(carried.office.root).read()).first!!
            val (checked, code) = kp.daemon.Check(rules).run(listOf(carried.office.project.resolve("mail")))
            withClue(checked.joinToString("\n")) { code shouldBe 0 }
        }
    }

    "every fact a requirement on the walk's way reads is in an observed with a value — none unknown, none supplied" {
        Carried().use { carried ->
            carried.play() shouldBe true
            val read = carried.observedOnTheWay()
            println("the facts the way read, each with the transitions that read it (S3.5 DONE):\n" + read.entries.joinToString("\n") { (fact, rules) -> "- $fact: ${rules.joinToString(", ")}" })
            read.keys.filter { it.endsWith(" = null") }.shouldBeEmpty()
            read.keys.map { it.substringBefore('(').substringBefore(' ') }.toSet().containsAll(setOf("plan.predecessors_exited", "pool.seat_available",
                "budget.slot_open", "list.fence_complete", "list.tasks_total", "list.lines", "roster.model_allowed", "git.branch_exists", "seat.process_gone",
                "seat.tool_call_open_for", "pr.ready", "ci.state", "pr.head", "list.tasks_open", "seat.name", "roster.rank", "roster.model_rank",
                "roster.floor_rank", "pr.review_on", "bora.present", "pr.evidence_present", "project.proof_files", "list.found_untriaged",
                "git.stray_changes", "status.entry_present", "status.entries", "stage.rolls", "stage.live")) shouldBe true
        }
    }
})
