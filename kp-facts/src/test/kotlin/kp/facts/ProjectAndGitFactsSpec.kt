package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * KP/P3/S3.1 — facts from the project and git. One spec per sentence of the stage list's T1, at `kp-facts`' one entry: a
 * fact's name and parameters in; a value with its evidence, or unknown with what was missing, out — on checkouts the
 * specs build in temp folders with the real `git` and dated commits ([Office]). Each observation is held to have changed
 * nothing in any checkout: its status, its refs, its object count and its index are what they were. The fifth sentence —
 * the daemon asks them — is `kp-daemon`'s `ProjectAndGitFactsSpec`.
 */
class ProjectAndGitFactsSpec : FreeSpec({
    "every fact Amendment 32 defines is answered and has a spec named after it; the two it does not read yet are unknown, each with the amendment's reason" {
        val amendment32 = listOf(
            "plan.predecessors_exited", "plan.stage_has_list", "plan.last_stage_exited", "plan.in_current_wave", "plan.wave_of", "plan.chart_fresh",
            "list.fence_complete", "list.stop_conditions", "list.tasks_open", "list.tasks_total", "list.task_exists", "list.done_item_exists",
            "list.found_untriaged", "list.lines", "stage.rolls", "stage.live", "stage.touches", "stage.cut_at",
            "status.entry_present", "status.entries", "register.fresh", "project.clean_and_pushed", "project.proof_files",
            "git.branch_exists", "git.stray_changes",
        )
        val office = Office(tempdir())

        ProjectFacts.ANSWERED.keys.sorted() shouldBe amendment32.sorted()
        ProjectFacts.NOT_READ_YET.keys.sorted() shouldBe listOf("git.pin_files_touched", "list.sections_changed")
        specNames() shouldContainAll amendment32 + ProjectFacts.NOT_READ_YET.keys
        office.observe("list.sections_changed", "stage" to "KP/P3/S3.1", "since" to "m-panda-000001").missing!! shouldContain
            "needs the list as it stood at an earlier delivery"
        office.observe("git.pin_files_touched", "pr" to "Collite/keporkak#50").missing!! shouldContain "waits for the ruling on pin stages"
    }

    "a stage's wave, on a plan with two stages side by side and one behind both: past, current, next and later — from the log, from the overview's tick, and the log's word where both speak" {
        val office = Office(tempdir())
        office.project.write("plan.md", """
            | stage | after | goal |
            |---|---|---|
            | **S5.1 A** | — | beside B |
            | **S5.2 B** | — | beside A |
            | **S5.3 C** | S5.1, S5.2 | after both |
            | **S5.4 D** | S5.3 | after C |
        """.trimIndent() + "\n")
        office.project.write("tasks-overview.md", "- [ ] **P5 · Five**\n")
        fun waves() = listOf("KP/P5/S5.1", "KP/P5/S5.2", "KP/P5/S5.3", "KP/P5/S5.4").map { office.observe("plan.wave_of", "stage" to it).value }

        office.log.exited("KP/P5/S5.1", "2026-11-20T09:00:00Z")
        waves() shouldBe listOf("past", "current", "next", "later")

        office.log.exited("KP/P5/S5.2", "2026-11-20T10:00:00Z")
        waves() shouldBe listOf("past", "past", "current", "next")

        office.log.states.clear()
        office.project.write("tasks-overview.md", "- [ ] **P5 · Five**\n  - [x] S5.1 A — done\n")
        waves() shouldBe listOf("past", "current", "next", "later")

        office.log.states["KP/P5/S5.1"] = "building"
        waves() shouldBe listOf("current", "current", "next", "later")
        office.observe("plan.predecessors_exited", "stage" to "KP/P5/S5.3").value shouldBe false
    }

    "a list's tasks: none, one, several with the last one open; ticked with no `done <sha>`, and `done <sha>` with no tick, are open" {
        val office = Office(tempdir())
        fun tasks(vararg lines: String): List<Any?> {
            office.project.write("tasks-p3-s3.1-facts.md", (listOf("# KP-keporkak-P3 · S3.1", "## Tasks") + lines + listOf("## DONE")).joinToString("\n", postfix = "\n"))
            return listOf("list.tasks_total", "list.tasks_open").map { office.observe(it, "stage" to "KP/P3/S3.1").value }
        }

        tasks() shouldBe listOf(0, 0)
        tasks("- [ ] T1 — specs") shouldBe listOf(1, 1)
        tasks("- [x] T1 — specs", "      done 1a2b3c4", "- [x] T2 — shape", "      done 5d6e7f8", "- [ ] T3 — plan") shouldBe listOf(3, 1)
        tasks("- [x] T1 — specs", "      done 1a2b3c4", "- [x] T2 — shape", "      done 5d6e7f8", "- [x] T3 — plan") shouldBe listOf(3, 1)
        tasks("- [x] T1 — specs", "      done 1a2b3c4", "- [x] T2 — shape", "      done 5d6e7f8", "- [ ] T3 — plan", "      done 9a8b7c6") shouldBe listOf(3, 1)
        tasks("- [x] T1 — specs", "      done 1a2b3c4", "- [x] T2 — shape", "      done 5d6e7f8", "- [x] T3 — plan", "      done 9a8b7c6") shouldBe listOf(3, 0)
    }

    "the main checkouts: no change, a change in one, changes in two summed; an untracked file is counted and an ignored one not; the project checkout's changes are not counted" {
        val office = Office(tempdir())
        office.toy.write(".gitignore", "build/\n").commit("2026-11-02T09:00:00+01:00")
        office.project.write("mail/KP/p3-s3.1.jsonl", "{\"v\":1,\"seq\":1}\n").write("tasks-p3-s3.1-facts.md", "- [ ] T1\n").commit("2026-11-02T09:00:00+01:00")
        fun stray() = office.observe("git.stray_changes", "office" to "panda").value

        stray() shouldBe 0
        office.project.write("mail/KP/p3-s3.1.jsonl", "{\"v\":1,\"seq\":1}\n{\"v\":1,\"seq\":2}\n").write("tasks-p3-s3.1-facts.md", "- [x] T1\n      done 1a2b3c4\n")
        stray() shouldBe 0
        office.keporkak.write("README.md", "keporkak, changed\n")
        stray() shouldBe 1
        office.toy.write("README.md", "kp-toy, changed\n")
        stray() shouldBe 2
        office.toy.write("NEW.md", "untracked\n").write("build/out.txt", "ignored\n")
        stray() shouldBe 3
    }

    "the observers write nothing: after a fact is read, every checkout's status, refs, object count and index are what they were" {
        val office = Office(tempdir())
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — facts (PR: feat/facts)\nrolls: none · live: none · touches: none\n")
            .write("STATUS.md", "# KP — status\n").commit("2026-11-19T08:26:12+01:00")
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — facts (PR: feat/facts)\nrolls: none · live: none · touches: none\nchanged\n")
        office.keporkak.write("README.md", "changed\n")
        office.project.touch("STATUS.md")
        office.toy.touch("README.md")

        val before = listOf(office.project, office.keporkak, office.toy).map { it.state() }
        val answers = ProjectFacts.ANSWERED.keys.sorted().map { fact -> office.facts().observe(fact, mapOf("stage" to "KP/P3/S3.1", "phase" to "KP/P3", "effort" to "KP", "office" to "panda", "id" to "T1")) }

        listOf(office.project, office.keporkak, office.toy).map { it.state() } shouldBe before
        answers.size shouldBe 25
    }

    "with no `git` on the path, a fact read through git is unknown and says so; nothing is thrown" {
        val office = Office(tempdir(), path = "")
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — facts (PR: feat/facts)\n")

        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1").missing!! shouldContain "no `git` on the path"
        office.observe("git.stray_changes", "office" to "panda").missing!! shouldContain "no `git` on the path"
        office.observe("list.lines", "stage" to "KP/P3/S3.1").value shouldBe 1
    }
})

/**
 * The names of `kp-facts`' specs, each read from its test sources: a spec is named after the fact it is about when its
 * name begins with that fact in backticks.
 */
private fun specNames(): Set<String> {
    val sources = Path.of(System.getProperty("kp.root")).resolve("kp-facts/src/test/kotlin")
    val named = Regex("""^\s*"`([a-z_]+\.[a-z_]+)`[^"]*" \{""", RegexOption.MULTILINE)
    return sources.walk().filter { it.isRegularFile() && it.toString().endsWith(".kt") }
        .flatMap { source -> named.findAll(source.readText()).map { it.groupValues[1] } }.toSet()
}
