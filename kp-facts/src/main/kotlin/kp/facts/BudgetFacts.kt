package kp.facts

import java.math.BigDecimal
import java.time.Instant

/**
 * What the vendor said of a subscription's window, as the driver heard it from a seat's agent (contracts.md §4.4,
 * Amendment 44): the window as the vendor names it, the fraction used, when it resets — when the vendor says — and when
 * and through which seat it was heard.
 */
data class VendorReport(val subscription: String, val window: String, val fraction: BigDecimal, val resets: Instant?, val heard: Instant, val seat: String)
