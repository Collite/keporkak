package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * KP/P3/S3.14 — a seat's four facts in the service, case by case (the slices behind AfterTheFourthWaveSpec): the pool's
 * facts over the fixture pool of PoolSupport.kt and a folded fixture log, asked as the mailroom asks.
 */
class SeatFactsSpec : StringSpec({
    "a role's seat in a stage is the last started for it there and not retired: a retired one is passed over, a seat started again after it counts from then" {
        val asked = PoolAsked(log = FixtureLog(listOf(
            SeatAt("coder-1", "coder", S31, "junior", "idle"),
            SeatAt("coder-2", "coder", S31, "senior", "retired"),
            SeatAt("coder-3", "coder", S32, "junior", "busy"),
        )))

        asked.ask("seat.name", "of" to "coder", stage = S31) shouldBe "coder-1"
        asked.evidence("seat.name", "of" to "coder", stage = S31) shouldBe "coder-1: the last seat started for coder in KP/P3/S3.1 and not retired"
        asked.ask("seat.name", "of" to "coder", stage = S32) shouldBe "coder-3"

        val again = PoolAsked(log = FixtureLog(listOf(
            SeatAt("coder-1", "coder", S31, "junior", "retired"),
            SeatAt("coder-2", "coder", S31, "junior", "idle"),
            SeatAt("coder-1", "coder", S31, "senior", "idle"),
        )))
        again.ask("seat.name", "of" to "coder", stage = S31) shouldBe "coder-1"
        again.ask("seat.model", "of" to "coder", stage = S31) shouldBe "claude-beta-1"
    }

    "a seat named as a seat answers for itself — its role from the log's start, its model and effort from its line — and one no start names is unknown" {
        val asked = PoolAsked(log = FixtureLog(listOf(SeatAt("reviewer-1", "reviewer", S31, null, "idle"), SeatAt("coder-1", "coder", S31, "senior", "busy"))),
            live = mapOf("coder-1" to ("claude-beta-1" to "low")))

        asked.ask("seat.name", "of" to "reviewer-1", stage = S31) shouldBe "reviewer-1"
        asked.ask("seat.role", "seat" to "reviewer-1") shouldBe "reviewer"
        asked.ask("seat.model", "of" to "reviewer-1") shouldBe "claude-beta-1"
        // The seats hold coder-1 on the model and effort it was started with — beta low — and the reviewer's line is at or above it.
        asked.ask("seat.effort", "of" to "coder", stage = S31) shouldBe "low"
        asked.ask("seat.effort", "of" to "reviewer-1") shouldBe "low"
        asked.unknown("seat.name", "of" to "coder-9", stage = S31) shouldBe "no seat coder-9 is known to the log or to the seats"
        asked.unknown("seat.role", "of" to "coder", stage = null) shouldBe "no stage to find the role coder in"
        asked.unknown("seat.model", "of" to "qa", stage = S31) shouldBe "no seat is bound to the role qa in KP/P3/S3.1"
    }

    "a seat a `session-report` being judged asks to start is the report's: its role, and its line by the level of the request it answers — over an earlier start of the same name, and before any seat is held" {
        // coder-1 was started and retired on S3.1 at senior; a report on S3.2 asks to start it again, at junior, on a request about no stage.
        val log = FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "senior", "retired")),
            reports = listOf(FixtureLog.Reported("coder-1", "coder", S32, level = "junior"), FixtureLog.Reported("reviewer-2", "reviewer", S32)))
        val coder = PoolAsked(log = log, arriving = log.arriving("coder-1"))

        coder.ask("seat.role", "seat" to "coder-1", stage = S32) shouldBe "coder"
        coder.ask("seat.model", "seat" to "coder-1", stage = S32) shouldBe "claude-alpha-1"
        coder.evidence("seat.name", "seat" to "coder-1", stage = S32) shouldBe "coder-1: a seat a session-report being judged asks to start"
        // Judged no more, coder-1 is the log's: its retired start at senior.
        PoolAsked(log = log).ask("seat.model", "seat" to "coder-1", stage = S32) shouldBe "claude-beta-1"
        // The reviewer's level is at or above the coder's on the stage: S3.2 has none yet.
        PoolAsked(log = log, arriving = log.arriving("reviewer-2")).unknown("seat.model", "seat" to "reviewer-2", stage = S32) shouldBe
            "reviewer-2 cannot be seated as reviewer: the level of reviewer is set at or above the coder's, and the stage has no coder's line"
    }
})

private const val S31 = "KP/P3/S3.1"
private const val S32 = "KP/P3/S3.2"
