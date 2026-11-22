package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.io.path.readLines

/**
 * KP/P3/S3.8 — `kp-facts` on its edges (T1's fourth sentence; T4; review-025's follow-ups, Collite/keporkak#52), at its
 * one entry on fixture checkouts ([Office]): a tick of a stage whose id begins another's, a number too large to hold, an
 * effort's folder that cannot be read and a `git` that does not end. Each of the last three is unknown with a reason
 * that says which, and nothing is thrown out of `kp-facts`.
 */
class AfterTheFirstWaveSpec : FreeSpec({
    "`kp-facts` on its edges: `- [x] S1.10` ticks S1.10 and not S1.1; a number too large to hold, an effort folder that cannot be read and a `git` that does not end are each unknown, with a reason that says which" - {
        "`- [x] S1.10` in an overview with S1.1 open: S1.1 is not exited, S1.10 is" {
            val office = Office(tempdir())
            office.project.write("plan.md", TEN_STAGES).write("tasks-overview.md", "- [ ] **P1 · One**\n  - [x] S1.10 Ten\n  - [ ] S1.1 One\n")

            office.observe("plan.predecessors_exited", "stage" to "KP/P1/S1.2") shouldBe
                value(false, "plan.md#L6 (after S1.1); S1.1: tasks-overview.md does not tick S1.1")
            office.observe("plan.predecessors_exited", "stage" to "KP/P1/S1.11") shouldBe
                value(true, "plan.md#L8 (after S1.10); S1.10: tasks-overview.md#L2 ticks S1.10")
        }

        "a task `T99999999999` in a list: unknown, the reason names the number" {
            val office = Office(tempdir())
            office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — Facts\n## Tasks\n- [x] T1 — Specs\n      done 1a2b3c4\n- [x] T99999999999 — The rest\n      done 1a2b3c4\n")

            office.observe("list.tasks_total", "stage" to "KP/P3/S3.1").missing shouldBe
                "tasks-p3-s3.1-facts.md#L5: the number 99999999999 of T99999999999 is too large to hold"
        }

        "an effort's folder with no read permission: unknown, the reason names the folder — not that a file does not exist" {
            val office = Office(tempdir())
            office.project.write("toy/plan.md", TEN_STAGES).write("toy/tasks-p0-s0.2-smoke.md", "# TOY-kp-toy-P0 · S0.2 — Smoke\n")
            val toy = office.project.dir.resolve("toy")
            Files.setPosixFilePermissions(toy, PosixFilePermissions.fromString("---------"))
            try {
                office.observe("plan.wave_of", "stage" to "TOY/P1/S1.1").missing shouldBe "the effort TOY's folder toy/ cannot be read"
                office.observe("plan.stage_has_list", "stage" to "TOY/P0/S0.2").missing shouldBe "the effort TOY's folder toy/ cannot be read"
            } finally {
                Files.setPosixFilePermissions(toy, PosixFilePermissions.fromString("rwx------"))
            }
        }

        "a `git` that sleeps past the limit with its output open is ended at the limit, and the fact is unknown" {
            val fake = tempdir().toPath()
            val pids = fake.resolve("git.pids")
            fake.resolve("git").toFile().apply {
                writeText("#!/bin/sh\necho $$ > '$pids'\nsleep 600 &\necho $! >> '$pids'\nwait\n")
                setExecutable(true)
            }
            val office = Office(tempdir(), path = fake.toString(), limit = Duration.ofSeconds(2))

            val began = System.nanoTime()
            val stray = office.observe("git.stray_changes", "office" to "panda")
            val took = Duration.ofNanos(System.nanoTime() - began)

            stray.missing shouldBe "keporkak: git rev-parse in ${office.keporkak.dir} did not end within 2 s; it was ended"
            took.toSeconds() shouldBeLessThan 10L
            pids.readLines().map { ProcessHandle.of(it.trim().toLong()).map(ProcessHandle::isAlive).orElse(false) } shouldBe listOf(false, false)
        }
    }
})

/** A plan whose stage ids begin one another's: S1.1 and S1.10, S1.2 after S1.1, S1.11 after S1.10. */
private val TEN_STAGES = """
    ## 2 · Stages

    | stage | after | goal |
    |---|---|---|
    | **S1.1 One** | — | one |
    | **S1.2 Two** | S1.1 | two |
    | **S1.10 Ten** | — | ten |
    | **S1.11 Eleven** | S1.10 | eleven |
""".trimIndent() + "\n"
