package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith

/**
 * KP/P3/S3.13 — the pool's facts, case by case (the slices behind PoolAndBudgetFactsSpec): the fixture pool of
 * PoolSupport.kt, a folded fixture log.
 */
class PoolFactsSpec : StringSpec({
    "pool.seat_available is true where a start would be seated, by the reading a start uses, and names the order line; false with why where it would not" {
        val asked = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", "KP/P3/S3.1", "senior", "idle"))))

        asked.ask("pool.seat_available", "role" to "coder", "level" to "junior") shouldBe true
        asked.evidence("pool.seat_available", "role" to "coder", "level" to "junior") shouldBe
            "coder at junior is seated on claude-alpha-1 low (pool/roster.yaml line 6 (order.0)); the model is reserved for no role"
        asked.ask("pool.seat_available", "role" to "principal", "level" to "junior") shouldBe true
        asked.evidence("pool.seat_available", "role" to "principal", "level" to "junior") shouldEndWith "the model is reserved for principal (pool/roster.yaml line 25 (reserved.claude-gamma-1))"
        // The coder's level is the assignment's: with none asked, no start would be seated.
        asked.ask("pool.seat_available", "role" to "coder") shouldBe false
        asked.evidence("pool.seat_available", "role" to "coder") shouldContain "per assignment"
        // The reviewer's is at or above the coder's on the stage — senior M, beta high.
        asked.evidence("pool.seat_available", "role" to "reviewer", stage = "KP/P3/S3.1") shouldContain "claude-beta-1 high"
    }

    "office.computer_use is this office's when none is named, and the named one's, from pool/offices.yaml" {
        val asked = PoolAsked()

        asked.ask("office.computer_use") shouldBe "off"
        asked.evidence("office.computer_use") shouldContain "(offices.panda.computer_use)"
        asked.ask("office.computer_use", "office" to "dsk") shouldBe "off"
        asked.evidence("office.computer_use", "office" to "dsk") shouldContain "(offices.dsk.computer_use)"
    }

    "a seat whose line cannot be read is unknown with why: a role the log never started, a stage with no seat of the role" {
        val asked = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", "KP/P3/S3.1", null, "idle"))))

        asked.unknown("roster.rank", "of" to "coder-1") shouldBe
            "coder-1 cannot be seated as coder: the level of coder is per assignment, and no request about it names one"
        asked.unknown("roster.rank", "of" to "coder") shouldBe "no stage to find the role coder in"
        asked.unknown("roster.rank", "of" to "coder", stage = "KP/P3/S3.2") shouldBe "no seat is bound to the role coder in KP/P3/S3.2"
    }
})
