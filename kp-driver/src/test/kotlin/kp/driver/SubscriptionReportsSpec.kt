package kp.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

/**
 * KP/P3/S3.13 T6 — what the vendor says of a subscription, as the driver hears it beside a `usage_update`: the pinned
 * adapter's `_meta["_claude/rateLimit"]`, carried by S2.1's fake agent as the adapter carries it.
 */
class SubscriptionReportsSpec : StringSpec({
    val seat = kp.model.SeatId("coder-1")

    "the vendor's window, fraction, reset and status are reported as they came; a usage_update without them reports nothing, and one without a fraction is written down and not reported" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[
                {"do": "usage", "used": 1000, "size": 200000},
                {"do": "usage", "used": 1500, "size": 200000, "rate_limit": {"status": "allowed"}},
                {"do": "usage", "used": 2000, "size": 200000, "rate_limit": {"status": "allowed_warning", "rateLimitType": "seven_day", "utilization": 0.81, "resetsAt": 1795219200}},
                {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "one").ok()

            office.reports shouldBe listOf(SubscriptionReport("seven_day", BigDecimal("0.81"), Instant.parse("2026-11-21T00:00:00Z"), "allowed_warning", Instant.parse("2026-11-15T10:00:00Z")))
            office.transcript("coder-1").filter { it.endsWith("""coder-1 the vendor on the subscription: {"status":"allowed"}""") }.size shouldBe 1
        }
    }

    "the vendor's windows as the pinned adapter passed them on the office (the handshake of 2026-11-24): each window of unifiedWindows reported with its own fraction, reset and status; the top level names a window and no fraction of its own" {
        // The line the handshake's transcript holds, word for word (~/Dev/_scratch/handshakes/handshake-0.106.0-2026-11-24T193913Z.md).
        val said = """{"status":"allowed","resetsAt":1795549800,"rateLimitType":"five_hour","overageStatus":"allowed","overageResetsAt":1796083200,"isUsingOverage":false,""" +
            """"unifiedWindows":{"five_hour":{"utilization":0.46,"resetsAt":1795549800,"status":"allowed"},"seven_day":{"utilization":0.23,"resetsAt":1795950000,"status":"allowed"}}}"""
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "usage", "used": 1000, "size": 200000, "rate_limit": $said}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "one").ok()

            office.reports shouldBe listOf(
                SubscriptionReport("five_hour", BigDecimal("0.46"), Instant.parse("2026-11-24T19:50:00Z"), "allowed", Instant.parse("2026-11-15T10:00:00Z")),
                SubscriptionReport("seven_day", BigDecimal("0.23"), Instant.parse("2026-11-29T11:00:00Z"), "allowed", Instant.parse("2026-11-15T10:00:00Z")),
            )
        }
    }
})
