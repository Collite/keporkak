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
})
