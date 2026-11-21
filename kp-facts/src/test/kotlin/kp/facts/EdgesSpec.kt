package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * S3.8 T4 — the edges below `AfterTheFirstWaveSpec`'s (review-025's follow-ups, Collite/keporkak#52): every place a
 * number of an id is read — a fence's `SC-<n>`, an `after` cell's `P<n>`, a plan's row, an asked `T<n>` or `D<n>`, a
 * stage id — is unknown past what can be held, naming the number and where it stands; a list file that cannot be read is
 * named as such. Nothing is thrown out of `kp-facts`.
 */
class EdgesSpec : FreeSpec({
    "a number too large to hold is unknown wherever an id holds it, and the fence holding one is unknown, not incomplete" {
        val office = Office(tempdir())
        office.project.write("plan.md", "| stage | after |\n|---|---|\n| **S3.1 Facts** | P99999999999 |\n| **S3.2 More** | — |\n| **S99999999999.1 Far** | — |\n")
            .write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — Facts\nrolls: none · live: none · touches: none\n## Scope fence\n- SC-1 · SC-99999999999\n## Tasks\n- [ ] T1 — Specs\n## DONE (observable, all required)\n- one\n")

        office.observe("list.stop_conditions", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md#L4: the number 99999999999 of SC-99999999999 is too large to hold"
        office.observe("list.fence_complete", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md#L4: the number 99999999999 of SC-99999999999 is too large to hold"
        office.observe("list.task_exists", "stage" to "KP/P3/S3.1", "id" to "T99999999999").missing shouldBe "the parameter 'id': the number 99999999999 of T99999999999 is too large to hold"
        office.observe("list.done_item_exists", "stage" to "KP/P3/S3.1", "id" to "D99999999999").missing shouldBe "the parameter 'id': the number 99999999999 of D99999999999 is too large to hold"
        office.observe("plan.wave_of", "stage" to "KP/P3/S3.2").missing shouldBe "plan.md#L5: the number 99999999999 of S99999999999.1 is too large to hold"
        office.observe("plan.stage_has_list", "stage" to "KP/P99999999999/S99999999999.1").missing shouldBe
            "the stage: the number 99999999999 of KP/P99999999999/S99999999999.1 is too large to hold"
        office.observe("plan.last_stage_exited", "phase" to "KP/P99999999999").missing shouldBe "the phase: the number 99999999999 of KP/P99999999999 is too large to hold"
    }

    "an `after` cell's phase id too large to hold is unknown, naming the cell's row" {
        val office = Office(tempdir())
        office.project.write("plan.md", "| stage | after |\n|---|---|\n| **S3.1 Facts** | P99999999999 |\n")

        office.observe("plan.predecessors_exited", "stage" to "KP/P3/S3.1").missing shouldBe "plan.md#L3: the number 99999999999 of P99999999999 is too large to hold"
    }

    "a list file that cannot be read is unknown, naming the file — not a bare exception" {
        val office = Office(tempdir())
        office.project.write("tasks-p3-s3.1-facts.md", "# KP-keporkak-P3 · S3.1 — Facts\nrolls: none · live: none · touches: none\n")
        val list = office.project.dir.resolve("tasks-p3-s3.1-facts.md")
        Files.setPosixFilePermissions(list, PosixFilePermissions.fromString("---------"))
        try {
            office.observe("stage.rolls", "stage" to "KP/P3/S3.1").missing shouldBe "tasks-p3-s3.1-facts.md cannot be read"
            office.observe("plan.stage_has_list", "stage" to "KP/P3/S3.1").value shouldBe true
        } finally {
            Files.setPosixFilePermissions(list, PosixFilePermissions.fromString("rw-------"))
        }
    }
})
