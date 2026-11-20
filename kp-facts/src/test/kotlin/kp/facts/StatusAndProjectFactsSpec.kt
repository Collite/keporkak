package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories

/**
 * STATUS and the project checkout (contracts.md §4.4, Amendment 32): the dated entries of an effort's `STATUS.md`, the
 * register's freshness, whether the checkout is clean and pushed, and the files the evidence rules refuse. One spec per
 * fact, named after it.
 */
class StatusAndProjectFactsSpec : FreeSpec({
    "`status.entries` counts the lines of the effort's STATUS.md that begin `- **<yyyy-mm-dd>`; unknown with no STATUS.md" {
        val office = Office(tempdir())
        office.project.write("STATUS.md", STATUS).write("toy/STATUS.md", "# TOY — status\n- **2026-11-13 — S0.1 exited.**\n")

        office.observe("status.entries", "effort" to "KP") shouldBe value(3, "STATUS.md#L3,L5,L7")
        office.observe("status.entries", "effort" to "TOY") shouldBe value(1, "toy/STATUS.md#L2")
        office.project.delete("toy/STATUS.md")
        office.observe("status.entries", "effort" to "TOY").missing shouldBe "toy/STATUS.md does not exist"
        office.observe("status.entries", "effort" to "XX").missing shouldBe "the office's project.efforts names no folder for the effort XX"
    }

    "`status.entry_present` is true when a dated entry names the stage first: `- **<date> — S<n.m> `" {
        val office = Office(tempdir())
        office.project.write("STATUS.md", STATUS)

        office.observe("status.entry_present", "stage" to "KP/P2/S2.5") shouldBe value(true, "STATUS.md#L5")
        office.observe("status.entry_present", "stage" to "KP/P3/S3.0").value shouldBe false
        office.observe("status.entry_present", "stage" to "KP/P2/S2.50") shouldBe value(true, "STATUS.md#L7")
    }

    "`register.fresh` is true when REGISTER.md is in a commit not older than the last commit of any effort's STATUS.md; false with no REGISTER.md" {
        val office = Office(tempdir())
        office.project.write("STATUS.md", STATUS).write("toy/STATUS.md", "# TOY\n").commit("2026-11-19T10:00:00Z")

        office.observe("register.fresh").value shouldBe false
        office.project.write("REGISTER.md", "# register\n").commit("2026-11-19T11:00:00Z")
        office.observe("register.fresh").value shouldBe true
        office.project.write("toy/STATUS.md", "# TOY\n- **2026-11-19 — S0.2 cut.**\n").commit("2026-11-19T12:00:00Z")
        office.observe("register.fresh").value shouldBe false
    }

    "`project.clean_and_pushed` is true with no changed path, `mail/` included, and no commit its upstream lacks; false with no upstream" {
        val office = Office(tempdir())
        val origin = tempdir().toPath().resolve("origin.git").createDirectories().also { runGit(it, listOf("init", "--quiet", "--bare")) }
        office.project.write("STATUS.md", STATUS).commit("2026-11-19T10:00:00Z")

        office.observe("project.clean_and_pushed").value shouldBe false
        office.project.git("remote", "add", "origin", origin.toString())
        office.project.git("push", "--quiet", "--set-upstream", "origin", "main")
        office.observe("project.clean_and_pushed").value shouldBe true
        office.project.write("mail/KP/p3-s3.1.jsonl", "{\"v\":1}\n")
        office.observe("project.clean_and_pushed").value shouldBe false
        office.project.commit("2026-11-19T11:00:00Z")
        office.observe("project.clean_and_pushed").value shouldBe false
        office.project.git("push", "--quiet")
        office.observe("project.clean_and_pushed").value shouldBe true
    }

    "`project.proof_files` counts the tracked files outside `mail/` that are `*.log`, `*.xml`, `*.jsonl` or under a folder named `review` or `proofs`" {
        val office = Office(tempdir())
        office.project.write("STATUS.md", STATUS).write("review-002.md", "a review\n").write("mail/KP/p3-s3.1.jsonl", "{}\n").commit("2026-11-19T10:00:00Z")

        office.observe("project.proof_files", "stage" to "KP/P3/S3.1") shouldBe value(0, "project: 3 tracked files")
        office.project.write("run.log", "x\n").write("toy/report.xml", "<x/>\n").write("notes/review/r.md", "x\n").write("proofs/p.md", "x\n")
            .write("copy.jsonl", "{}\n").commit("2026-11-19T11:00:00Z")
        office.project.write("untracked.log", "x\n")
        office.observe("project.proof_files", "stage" to "KP/P3/S3.1") shouldBe
            value(5, "project: 8 tracked files; refused: copy.jsonl, notes/review/r.md, proofs/p.md, run.log, toy/report.xml")
    }
})

private val STATUS = """
    # KP — status
    ## Now
    - **2026-11-20 — P3 begun: S3.0 and S3.1 cut (D-92, D-93, D-94).** Bora ruled.
      a continuation line
    - **2026-11-19 — S2.5 exited.** Collite/keporkak#48 merged.
    not an entry: - **2026-11-18 — S2.4 exited.**
    - **2026-11-19 — S2.50 is no stage of this plan.**
""".trimIndent() + "\n"
