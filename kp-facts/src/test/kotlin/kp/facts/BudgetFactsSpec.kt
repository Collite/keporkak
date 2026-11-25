package kp.facts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

/**
 * KP/P3/S3.13 T6 — `budget.consumption` and `budget.vs_daily_median` at their edges (contracts.md §4.4, Amendment 44): the
 * cases behind PoolAndBudgetFactsSpec, for the next developer.
 */
class BudgetFactsSpec : StringSpec({
    val now = Instant.parse("2026-11-20T12:00:00Z")
    fun report(window: String, fraction: String, resets: String?, heard: String = "2026-11-20T11:00:00Z", seat: String = "coder-1") =
        VendorReport("claude", window, BigDecimal(fraction), resets?.let(Instant::parse), Instant.parse(heard), seat)

    "a window resets at its very second; one the vendor gave no reset for stands; the last report of any seat stands for all" {
        BudgetFacts.consumption(listOf(report("five_hour", "0.4", "2026-11-20T12:00:00Z")), "claude", "five_hour", now) shouldBe
            Observation.Unknown("the window five_hour of claude reset at 2026-11-20T12:00:00Z and was not reported since")
        (BudgetFacts.consumption(listOf(report("seven_day", "0.3", null)), "claude", "seven_day", now) as Observation.Value).value shouldBe BigDecimal("0.3")
        val two = listOf(report("seven_day", "0.3", null, heard = "2026-11-20T10:00:00Z"), report("seven_day", "0.35", null, seat = "reviewer-1"))
        (BudgetFacts.consumption(two, "claude", "subscription", now) as Observation.Value).value shouldBe BigDecimal("0.35")
        BudgetFacts.consumption(emptyList(), "claude", "subscription", now) shouldBe Observation.Unknown("the vendor has reported no window of claude")
    }

    "the median of an even count of days is the mean of the middle two; a median of zero is unknown; today with no turn is zero" {
        fun turn(at: String, count: Long) = TurnEnded("claude", Instant.parse(at), count)
        val four = listOf(turn("2026-11-16T10:00:00Z", 1000), turn("2026-11-17T10:00:00Z", 2000), turn("2026-11-18T10:00:00Z", 4000), turn("2026-11-19T10:00:00Z", 8000),
            turn("2026-11-20T10:00:00Z", 6000))
        (BudgetFacts.vsDailyMedian(four, "claude", now, 14, 3, "least") as Observation.Value).value shouldBe BigDecimal("2")
        // Over the last two days only: 4 000 and 8 000, the median 6 000.
        (BudgetFacts.vsDailyMedian(four, "claude", now, 2, 2, "least") as Observation.Value).value shouldBe BigDecimal("1")
        (BudgetFacts.vsDailyMedian(four.dropLast(1), "claude", now, 14, 3, "least") as Observation.Value).value shouldBe BigDecimal("0")
        BudgetFacts.vsDailyMedian(listOf(turn("2026-11-17T10:00:00Z", 0), turn("2026-11-18T10:00:00Z", 0), turn("2026-11-19T10:00:00Z", 0)), "claude", now, 14, 3, "least") shouldBe
            Observation.Unknown("the median of claude's daily sums over 2026-11-17, 2026-11-18, 2026-11-19 is zero")
    }
})
