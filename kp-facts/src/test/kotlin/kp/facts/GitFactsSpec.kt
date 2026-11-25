package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.createDirectories

/**
 * The main checkouts (contracts.md §4.4, Amendment 32): the office's `repos:`, never the project checkout. A git
 * command that fails — no repository, no `git` — makes its fact unknown with the reason. One spec per fact, named after
 * it.
 */
class GitFactsSpec : FreeSpec({
    "`git.branch_exists` is true when the branch the stage's `worktree.create` record names is a local or a remote-tracking branch of the main checkout of the repository it names (Amendment 40)" {
        val office = Office(tempdir())
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — Facts    (PR: feat/kp-keporkak-p3-s3.1-facts)\n")
        office.log.placed("KP/P3/S3.1", "keporkak", "feat/kp-keporkak-p3-s3.1-facts")
        val record = "the log's worktree.create of KP/P3/S3.1: keporkak, feat/kp-keporkak-p3-s3.1-facts"

        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1") shouldBe value(false, "$record; keporkak: no ref of feat/kp-keporkak-p3-s3.1-facts")
        office.keporkak.git("branch", "feat/kp-keporkak-p3-s3.1-facts")
        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1") shouldBe value(true, "$record; keporkak: refs/heads/feat/kp-keporkak-p3-s3.1-facts")
        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1", "repo" to "kp-toy").value shouldBe false
        office.toy.git("update-ref", "refs/remotes/origin/feat/kp-keporkak-p3-s3.1-facts", "HEAD")
        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1", "repo" to "kp-toy").value shouldBe true
    }

    "`git.branch_exists` is unknown for a repository the office does not hold, and for a stage with no `worktree.create` record, whatever its list names (Amendments 40, 43)" {
        val office = Office(tempdir())
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — Facts    (PR: feat/kp-keporkak-p3-s3.1-facts)\n")
            .write("toy/tasks-p0-s0.2-smoke.md", "# TOY-kp-toy-P0 · S0.2 — Smoke    (PR: feat/toy-kp-toy-p0-s0.2-smoke)\n")
        office.log.placed("KP/P3/S3.1", "other", "feat/kp-keporkak-p3-s3.1-facts")

        office.observe("git.branch_exists", "stage" to "KP/P3/S3.1").missing shouldBe "the office holds no repository 'other'"
        office.observe("git.branch_exists", "stage" to "TOY/P0/S0.2").missing shouldBe
            "TOY/P0/S0.2 has no worktree.create that ended ok: no repository and no branch are fixed for it (contracts.md §4.4, Amendment 40)"
    }

    "`git.stray_changes` is unknown when a main checkout is no repository, or for another office" {
        val office = Office(tempdir())
        val plain = tempdir().toPath().resolve("plain").createDirectories()
        val facts = ProjectFacts(office.checkouts.copy(repos = office.checkouts.repos + ("plain" to plain)), office.log, Git(System.getenv("PATH")))

        facts.observe("git.stray_changes", mapOf("office" to "panda")).missing!! shouldStartWith "plain: "
        office.observe("git.stray_changes", "office" to "mac").missing shouldBe "the daemon reads its own office, panda, not mac"
        office.observe("git.stray_changes", "office" to "panda").let { stray ->
            stray.value shouldBe 0
            stray.evidence shouldContain "keporkak: 0 changed paths"
        }
    }

    "`git.pin_files_touched` is unknown: it waits for the ruling on pin stages" {
        val office = Office(tempdir())

        office.observe("git.pin_files_touched", "pr" to "Collite/keporkak#50").missing!! shouldContain "the ruling on pin stages"
    }
})
