package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The list's facts (contracts.md §4.4, Amendment 32): the one file `tasks-p<n>-s<n.m>-*.md` in the effort's folder, read
 * line by line — its sections, the `rolls:` line, the stop conditions, the tasks, the DONE items, the Found lines — and the
 * time of the oldest commit that holds it. One spec per fact, named after it.
 */
class ListFactsSpec : FreeSpec({
    "`list.fence_complete` is true when `rolls:`, `live:` and `touches:` each have a value and the fence names a stop condition; false when one is missing" {
        val office = listed(Office(tempdir()))

        office.observe("list.fence_complete", "stage" to "KP/P3/S3.1").value shouldBe true
        office.project.write(LIST_PATH, LIST.replace("  ·  touches: none", "  ·  touches:"))
        office.observe("list.fence_complete", "stage" to "KP/P3/S3.1").value shouldBe false
        office.project.write(LIST_PATH, LIST.replace(Regex("rolls:.*\n"), ""))
        office.observe("list.fence_complete", "stage" to "KP/P3/S3.1").value shouldBe false
        office.project.write(LIST_PATH, LIST.replace(Regex("SC-[0-9]+"), "a stop"))
        office.observe("list.fence_complete", "stage" to "KP/P3/S3.1").value shouldBe false
    }

    "`list.stop_conditions` is the set of SC ids the `Scope fence` section names; unknown with no such section" {
        val office = listed(Office(tempdir()))

        office.observe("list.stop_conditions", "stage" to "KP/P3/S3.1") shouldBe value(listOf("SC-1", "SC-2", "SC-10"), "tasks-p3-s3.1-facts.md#L5-L7")
        office.project.write(LIST_PATH, LIST.replace("## Scope fence", "## Fence"))
        office.observe("list.stop_conditions", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md has no section 'Scope fence'"
    }

    "`list.tasks_total` counts the tasks of the `Tasks` section; unknown with no such section, no list, or two" {
        val office = listed(Office(tempdir()))

        office.observe("list.tasks_total", "stage" to "KP/P3/S3.1") shouldBe value(2, "tasks-p3-s3.1-facts.md#L11,L13")
        office.observe("list.tasks_total", "stage" to "KP/P3/S3.2").missing shouldBe "no file tasks-p3-s3.2-*.md"
        office.project.write("tasks-p3-s3.1-again.md", LIST)
        office.observe("list.tasks_total", "stage" to "KP/P3/S3.1").missing shouldBe "two list files for S3.1: tasks-p3-s3.1-again.md, tasks-p3-s3.1-facts.md"
        office.project.delete("tasks-p3-s3.1-again.md").write(LIST_PATH, LIST.replace("## Tasks", "## Work"))
        office.observe("list.tasks_total", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md has no section 'Tasks'"
    }

    "`list.tasks_open` counts the tasks not closed — a task is closed when ticked and one of its lines holds `done <sha>`" {
        val office = listed(Office(tempdir()))

        office.observe("list.tasks_open", "stage" to "KP/P3/S3.1") shouldBe value(1, "tasks-p3-s3.1-facts.md#L13")
        office.project.write(LIST_PATH, LIST.replace("- [ ] T2 — Shape", "- [x] T2 — Shape\n      done 0123456789abcdef0123456789abcdef01234567"))
        office.observe("list.tasks_open", "stage" to "KP/P3/S3.1").value shouldBe 0
        office.project.write(LIST_PATH, LIST.replace("- [ ] T2 — Shape", "- [x] T2 — Shape\n      done 12345g7"))
        office.observe("list.tasks_open", "stage" to "KP/P3/S3.1").value shouldBe 1
    }

    "`list.task_exists` is true for a task the list holds, false for another id; unknown with no id" {
        val office = listed(Office(tempdir()))

        office.observe("list.task_exists", "stage" to "KP/P3/S3.1", "id" to "T2") shouldBe value(true, "tasks-p3-s3.1-facts.md#L13")
        office.observe("list.task_exists", "stage" to "KP/P3/S3.1", "id" to "T3").value shouldBe false
        office.observe("list.task_exists", "stage" to "KP/P3/S3.1", "id" to "T").value shouldBe false
        office.observe("list.task_exists", "stage" to "KP/P3/S3.1").missing shouldBe "no parameter 'id'"
    }

    "`list.done_item_exists` is true for D<n> where the `DONE` section has an n-th item" {
        val office = listed(Office(tempdir()))

        office.observe("list.done_item_exists", "stage" to "KP/P3/S3.1", "id" to "D3") shouldBe value(true, "tasks-p3-s3.1-facts.md#L19")
        office.observe("list.done_item_exists", "stage" to "KP/P3/S3.1", "id" to "D4").value shouldBe false
        office.observe("list.done_item_exists", "stage" to "KP/P3/S3.1", "id" to "D0").value shouldBe false
    }

    "`list.found_untriaged` counts the lines of `Found while doing` that begin `- ` and hold more than the dash" {
        val office = listed(Office(tempdir()))

        office.observe("list.found_untriaged", "stage" to "KP/P3/S3.1") shouldBe value(2, "tasks-p3-s3.1-facts.md#L21,L23")
    }

    "`list.lines` is the number of the list's lines" {
        val office = listed(Office(tempdir()))

        office.observe("list.lines", "stage" to "KP/P3/S3.1") shouldBe value(26, "tasks-p3-s3.1-facts.md#L1-L26")
        office.project.write(LIST_PATH, "one line, no line feed")
        office.observe("list.lines", "stage" to "KP/P3/S3.1").value shouldBe 1
    }

    "`list.sections_changed` is unknown: it needs the list as it stood at an earlier delivery" {
        val office = listed(Office(tempdir()))

        office.observe("list.sections_changed", "stage" to "KP/P3/S3.1", "since" to "m-panda-000001").missing!! shouldContain "a later list of P3"
    }

    "`stage.rolls` is the value after `rolls:` on the line that begins `rolls:`, as written; unknown with no such line" {
        val office = listed(Office(tempdir()))

        office.observe("stage.rolls", "stage" to "KP/P3/S3.1") shouldBe value("none", "tasks-p3-s3.1-facts.md#L3")
        office.project.write(LIST_PATH, LIST.replace(Regex("rolls:.*\n"), ""))
        office.observe("stage.rolls", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md has no line that begins 'rolls:'"
    }

    "`stage.live` is the value after `live:` up to the next ` · `, as written; unknown when the line has no value for it" {
        val office = listed(Office(tempdir()))

        office.observe("stage.live", "stage" to "KP/P3/S3.1") shouldBe value("Panda, real Claude seats — on tokens", "tasks-p3-s3.1-facts.md#L3")
        office.project.write(LIST_PATH, LIST.replace("live: Panda, real Claude seats — on tokens", "live:"))
        office.observe("stage.live", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md#L3: 'live:' has no value"
    }

    "`stage.touches` is the value after `touches:` to the line's end, as written" {
        val office = listed(Office(tempdir()))

        office.observe("stage.touches", "stage" to "KP/P3/S3.1") shouldBe value("none", "tasks-p3-s3.1-facts.md#L3")
        office.project.write(LIST_PATH, LIST.replace("rolls: none  ·  live: Panda, real Claude seats — on tokens  ·  touches: none", "rolls: none · live: none"))
        office.observe("stage.touches", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md#L3: no 'touches:'"
    }

    "`stage.cut_at` is the committer time of the oldest commit that holds the list; a list in no commit has none" {
        val office = listed(Office(tempdir()))
        office.observe("stage.cut_at", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md is in no commit"

        val first = office.project.commit("2026-11-19T08:26:12+01:00")
        office.project.write(LIST_PATH, "$LIST\nchanged\n").commit("2026-11-20T10:00:00+01:00")
        office.observe("stage.cut_at", "stage" to "KP/P3/S3.1") shouldBe value("2026-11-19T08:26:12+01:00", "project: commit ${first.take(12)}")
    }

    "a list with two sections of one name, or two `rolls:` lines, is unknown to each fact that reads them, the evidence citing both; a fact that reads neither still answers" {
        val office = listed(Office(tempdir()))
        fun again(heading: String) = office.project.write(LIST_PATH, "$LIST## $heading\n- [ ] T3 — again · SC-11\n- again\n")
        fun missing(vararg facts: String) = facts.map { fact -> office.observe(fact, "stage" to "KP/P3/S3.1", "id" to if (fact == "list.done_item_exists") "D1" else "T1").missing }

        again("Scope fence")
        missing("list.fence_complete", "list.stop_conditions") shouldBe List(2) { "tasks-p3-s3.1-facts.md#L5,L27: two sections 'Scope fence'" }
        again("Tasks (again)")
        missing("list.tasks_total", "list.tasks_open", "list.task_exists") shouldBe List(3) { "tasks-p3-s3.1-facts.md#L10,L27: two sections 'Tasks'" }
        again("DONE")
        missing("list.done_item_exists") shouldBe listOf("tasks-p3-s3.1-facts.md#L15,L27: two sections 'DONE'")
        again("Found while doing")
        missing("list.found_untriaged") shouldBe listOf("tasks-p3-s3.1-facts.md#L20,L27: two sections 'Found while doing'")
        office.observe("list.tasks_open", "stage" to "KP/P3/S3.1").value shouldBe 1

        office.project.write(LIST_PATH, "${LIST}rolls: live  ·  live: none  ·  touches: kp-facts/\n")
        missing("stage.rolls", "stage.live", "stage.touches", "list.fence_complete") shouldBe List(4) { "tasks-p3-s3.1-facts.md#L3,L27: two lines that begin 'rolls:'" }
        office.observe("list.found_untriaged", "stage" to "KP/P3/S3.1").value shouldBe 2
    }
})

private const val LIST_PATH = "tasks-p3-s3.1-facts.md"

/** A stage list in the shape of the plan's lists (PL-9): its header, the fence, two tasks, three DONE items, two Found lines. */
private val LIST = """
    # KP-keporkak-P3 · S3.1 — Facts            (PR: feat/kp-keporkak-p3-s3.1-facts)
    Realizes: D-94
    rolls: none  ·  live: Panda, real Claude seats — on tokens  ·  touches: none

    ## Scope fence
    - In scope: the facts
    - Stop conditions: SC-1 another repo · SC-2 contracts.md · SC-10 a rule
    ## Pre-flight
    - [x] read the amendment
    ## Tasks (tick right after landing)
    - [x] T1 — Specs
          done 1a2b3c4
    - [ ] T2 — Shape

    ## DONE (observable, all required)
    - one
    - two
      continued
    - three
    ## Found while doing (one line each)
    - a discovery
    -
    - another

    ## Retro notes
    -
""".trimIndent() + "\n"

private fun listed(office: Office): Office = office.apply { project.write(LIST_PATH, LIST) }
