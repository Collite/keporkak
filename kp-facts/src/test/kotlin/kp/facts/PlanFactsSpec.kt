package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The plan's facts (contracts.md §4.4, Amendment 32): the stage rows of `plan.md`, their column `after`, exited — the
 * log's word where it knows a stage, else the overview's tick — the waves and the chart. One spec per fact, named after
 * it; each reads its value and evidence on a fixture and is unknown on each shape the amendment calls unknown.
 */
class PlanFactsSpec : FreeSpec({
    "`plan.wave_of` is past, current, next or later, in the effort's own plan; unknown for a stage with no row, a table with no `after` column, a cell that holds prose, a predecessor with no row" {
        val office = planned(Office(tempdir()))
        office.log.exited("KP/P1/S1.1", "2026-11-10T09:00:00Z")

        office.observe("plan.wave_of", "stage" to "KP/P1/S1.1").let { wave ->
            wave.value shouldBe "past"
            wave.evidence shouldContain "log: KP/P1/S1.1 exited"
        }
        office.observe("plan.wave_of", "stage" to "KP/P1/S1.2").let { wave ->
            wave.value shouldBe "current"
            wave.evidence shouldContain "plan.md#L6"
        }
        office.observe("plan.wave_of", "stage" to "KP/P3/S3.3").value shouldBe "next"
        office.observe("plan.wave_of", "stage" to "TOY/P0/S0.2").value shouldBe "current"
        office.observe("plan.wave_of", "stage" to "KP/P9/S9.9").missing shouldBe "plan.md has no row for S9.9"
        office.observe("plan.wave_of", "stage" to "KP/P2/S2.1").missing shouldBe "plan.md#L8: the table of S2.1 has no column 'after'"
        office.observe("plan.wave_of", "stage" to "KP/P3/S3.1").missing shouldBe
            "plan.md#L14: the cell 'after' of S3.1 holds 'S1.2 for its last two tasks', not stage and phase ids"
        office.observe("plan.wave_of", "stage" to "KP/P3/S3.2").missing shouldBe "plan.md#L15: S3.2's predecessor S9.9 has no row"
    }

    "`plan.predecessors_exited` is true when every predecessor has exited, and with none; a phase id stands for its every stage" {
        val office = planned(Office(tempdir()))

        office.observe("plan.predecessors_exited", "stage" to "KP/P1/S1.1").value shouldBe true
        office.observe("plan.predecessors_exited", "stage" to "KP/P1/S1.2").value shouldBe false
        office.log.exited("KP/P1/S1.1", "2026-11-10T09:00:00Z")
        office.observe("plan.predecessors_exited", "stage" to "KP/P1/S1.2").value shouldBe true
        office.observe("plan.predecessors_exited", "stage" to "KP/P3/S3.3").value shouldBe false
        office.project.write("tasks-overview.md", "- [x] **P1 · One**\n  - [x] S1.2 Two\n")
        office.observe("plan.predecessors_exited", "stage" to "KP/P3/S3.3").value shouldBe true
        office.observe("plan.predecessors_exited", "stage" to "KP/P3/S3.1").shouldBeInstanceOf<Observation.Unknown>()
    }

    "`plan.in_current_wave` is true when every stage named is current — one stage or a list of them; a list that names none is unknown" {
        val office = planned(Office(tempdir()))

        office.observe("plan.in_current_wave", "stage" to "KP/P1/S1.1").value shouldBe true
        office.observe("plan.in_current_wave", "stage" to listOf("KP/P1/S1.1", "TOY/P0/S0.2")).value shouldBe true
        office.observe("plan.in_current_wave", "stage" to listOf("KP/P1/S1.1", "KP/P1/S1.2")).value shouldBe false
        office.observe("plan.in_current_wave", "stage" to emptyList<String>()).missing shouldBe "the parameter 'stage' names no stage"
    }

    "`plan.last_stage_exited` is true when every stage of the phase has exited; a phase with no row is unknown" {
        val office = planned(Office(tempdir()))
        office.log.exited("KP/P1/S1.1", "2026-11-10T09:00:00Z")

        office.observe("plan.last_stage_exited", "phase" to "KP/P1").value shouldBe false
        office.project.write("tasks-overview.md", "  - [x] **S1.2** Two\n")
        office.observe("plan.last_stage_exited", "phase" to "KP/P1").value shouldBe true
        office.observe("plan.last_stage_exited", "phase" to "KP/P9").missing shouldBe "plan.md has no row of phase P9"
    }

    "`plan.stage_has_list` is true with one list file, false with none, and unknown with two — naming them" {
        val office = planned(Office(tempdir()))
        office.project.write("tasks-p1-s1.1-one.md", "# KP-keporkak-P1 · S1.1\n").write("tasks-p1-s1.10-ten.md", "# KP-keporkak-P1 · S1.10\n")

        office.observe("plan.stage_has_list", "stage" to "KP/P1/S1.1") shouldBe value(true, "tasks-p1-s1.1-one.md")
        office.observe("plan.stage_has_list", "stage" to "KP/P1/S1.2") shouldBe value(false, "no file tasks-p1-s1.2-*.md")
        office.project.write("tasks-p1-s1.1-again.md", "# KP-keporkak-P1 · S1.1\n")
        office.observe("plan.stage_has_list", "stage" to "KP/P1/S1.1").missing shouldBe "two list files for S1.1: tasks-p1-s1.1-again.md, tasks-p1-s1.1-one.md"
    }

    "`plan.chart_fresh` is true when the working tree's chart differs from the one in the newest commit not later than the phase's last exit; unknown with no exit in the log, or no such commit" {
        val office = planned(Office(tempdir()))
        office.observe("plan.chart_fresh", "phase" to "KP/P1").missing shouldBe "the log dates no exit of a stage of KP/P1"

        office.project.commit("2026-11-10T08:00:00Z")
        office.log.exited("KP/P1/S1.1", "2026-11-10T09:00:00Z")
        office.log.exited("KP/P1/S1.2", "2026-11-10T10:00:00Z")
        office.project.write("later.md", "a commit after the exit\n").commit("2026-11-10T11:00:00Z")
        office.observe("plan.chart_fresh", "phase" to "KP/P1").value shouldBe false
        office.project.write("plan.md", PLAN.replace("P1 --> P2", "P1 --> P2\n  P2 --> P3"))
        office.observe("plan.chart_fresh", "phase" to "KP/P1").let { fresh ->
            fresh.value shouldBe true
            fresh.evidence shouldContain "plan.md#L21-L25"
        }

        val early = planned(Office(tempdir()))
        early.log.exited("KP/P1/S1.2", "2026-11-10T10:00:00Z")
        early.project.commit("2026-11-10T11:00:00Z")
        early.observe("plan.chart_fresh", "phase" to "KP/P1").missing shouldBe "no commit of the project checkout is dated at or before KP/P1/S1.2's exit, 2026-11-10T10:00:00Z"
    }
})

/** A plan of the effort KP: P1 with an `after` column, P2 without, P3 with prose and a missing predecessor; and the toy's. */
private val PLAN = """
    # KP — plan
    ### P1
    | stage | after | goal |
    |---|---|---|
    | **S1.1 One** | — | first |
    | **S1.2 Two** | S1.1 | second |
    ### P2
    | stage | goal |
    |---|---|
    | **S2.1 Old table** | no column after |
    ### P3
    | stage | after | goal |
    |---|---|---|
    | **S3.1 Prose** | S1.2 for its last two tasks | prose |
    | **S3.2 Missing** | S9.9 | a predecessor with no row |
    | **S3.3 Phase** | P1 | after a phase |
    | **TOY S0.2 Smoke** | — | another effort's row |

    ## 3. Dependency chart

    ```mermaid
    flowchart LR
      P1 --> P2
    ```
""".trimIndent() + "\n"

private fun planned(office: Office): Office = office.apply {
    project.write("plan.md", PLAN).write("tasks-overview.md", "- [ ] **P1 · One**\n")
    project.write("toy/plan.md", "| stage | after | goal |\n|---|---|---|\n| **S0.1 Skeleton** | — | built |\n| **S0.2 Smoke** | S0.1 | smoke |\n")
    project.write("toy/tasks-overview.md", "- [ ] **P0**\n  - [x] S0.1 The skeleton\n")
}
