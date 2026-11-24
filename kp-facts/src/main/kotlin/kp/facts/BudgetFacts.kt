package kp.facts

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the vendor said of a subscription's window, as the driver heard it from a seat's agent (contracts.md §4.4,
 * Amendment 44): the window as the vendor names it, the fraction used, when it resets — when the vendor says — and when
 * and through which seat it was heard.
 */
data class VendorReport(val subscription: String, val window: String, val fraction: BigDecimal, val resets: Instant?, val heard: Instant, val seat: String)

/** A turn's end as its record leaves it (Amendment 44): the subscription of its seat, when it ended, its weighted count. */
data class TurnEnded(val subscription: String, val at: Instant, val count: Long)

/**
 * `budget.consumption` and `budget.vs_daily_median` (contracts.md §4.4, Amendment 44), from what the daemon hands over:
 * the vendor's last reports and the turn-end records. An observer compares by nature (architecture.md §2): which window
 * is fullest, whether a reset has passed, which days make the median — none of it a decision of the rules'. The daemon
 * asks no endpoint of the vendor's and derives no fraction of its own.
 */
object BudgetFacts {
    /** The `window` word that asks for the fullest window reported: the one POOL-7 passes. */
    const val FULLEST = "subscription"

    /**
     * `budget.consumption` (subscription, window): the fraction the vendor last reported of [window] of [subscription] —
     * the last report of any seat of the subscription stands for all of them — or, for [FULLEST], the fullest of the
     * windows reported. Unknown before any report, and for a window whose reset time has passed at [now], until it is
     * reported again.
     */
    fun consumption(reports: List<VendorReport>, subscription: String, window: String, now: Instant): Observation {
        val last = reports.filter { it.subscription == subscription }.groupBy { it.window }.mapValues { (_, each) -> each.maxBy { it.heard } }
        if (last.isEmpty()) return Observation.Unknown("the vendor has reported no window of $subscription")
        val standing = last.filterValues { report -> report.resets?.let { now.isBefore(it) } ?: true }
        if (window != FULLEST) {
            val report = last[window] ?: return Observation.Unknown("the vendor has reported no window $window of $subscription (reported: ${last.keys.sorted().joinToString(", ")})")
            if (window !in standing) return Observation.Unknown("the window $window of $subscription reset at ${report.resets} and was not reported since")
            return Observation.Value(report.fraction, told(report))
        }
        val fullest = standing.values.maxByOrNull { it.fraction }
            ?: return Observation.Unknown("every window of $subscription the vendor reported has reset since: " + last.values.joinToString("; ") { "${it.window} at ${it.resets}" })
        return Observation.Value(fullest.fraction, "the fullest of ${standing.keys.sorted().joinToString(", ")}: ${told(fullest)}")
    }

    private fun told(report: VendorReport): String =
        "${report.subscription}'s ${report.window} ${report.fraction.toPlainString()} used, resets ${report.resets ?: "when the vendor did not say"} — reported through ${report.seat} at ${report.heard}"

    /**
     * `budget.vs_daily_median` (subscription): today's sum over the median of the daily sums — the turn-end records of
     * the seats of [subscription], summed by the day (UTC) each turn ended. The median is over the last [span] days before
     * today on which a turn ended; with fewer than [least] such days it is unknown: there is no baseline yet. [least] is
     * named in what is missing as [leastAt] says where it stands.
     */
    fun vsDailyMedian(turns: List<TurnEnded>, subscription: String, now: Instant, span: Int, least: Int, leastAt: String): Observation {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val sums = turns.filter { it.subscription == subscription }.groupBy { LocalDate.ofInstant(it.at, ZoneOffset.UTC) }.mapValues { (_, each) -> each.sumOf { it.count } }
        val before = sums.filterKeys { it.isBefore(today) }.toSortedMap().entries.toList().takeLast(span)
        val days = before.joinToString(", ") { it.key.toString() }.ifEmpty { "none" }
        if (before.size < least) return Observation.Unknown("$subscription has turns on ${before.size} days before $today ($days); the median needs $least ($leastAt)")
        val ordered = before.map { BigDecimal.valueOf(it.value) }.sorted()
        val middle = ordered.size / 2
        val median = if (ordered.size % 2 == 1) ordered[middle] else (ordered[middle - 1] + ordered[middle]).divide(BigDecimal.valueOf(2))
        if (median.signum() == 0) return Observation.Unknown("the median of $subscription's daily sums over $days is zero")
        val sum = sums[today] ?: 0L
        val ratio = BigDecimal.valueOf(sum).divide(median, 4, RoundingMode.HALF_UP).stripTrailingZeros()
        return Observation.Value(ratio, "$subscription today ($today): $sum; the median of its daily sums on $days: ${median.toPlainString()}")
    }
}
