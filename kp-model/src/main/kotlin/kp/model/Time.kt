package kp.model

import java.time.LocalDateTime

private val stamp = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(?:[Zz]|([+-])(\\d{2}):(\\d{2}))$")

/**
 * The UTC date and time a contract timestamp names (§2: RFC 3339). Taken from the line, never from a clock.
 * Digits of a fraction beyond the nanosecond are cut.
 */
fun utc(at: String): LocalDateTime {
    val g = requireNotNull(stamp.matchEntire(at)) { "not a timestamp: $at" }.groupValues
    val local = LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].take(9).padEnd(9, '0').toInt())
    val offset = if (g[8].isEmpty()) 0L else (if (g[8] == "-") -1L else 1L) * (g[9].toLong() * 3600 + g[10].toLong() * 60)
    return local.minusSeconds(offset)
}
