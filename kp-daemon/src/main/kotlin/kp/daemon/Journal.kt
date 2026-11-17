package kp.daemon

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The daemon's own journal: one line per event on standard error, which the systemd user service hands to the
 * journal (`just logs`). The daemon brings no SLF4J provider, so the ACP SDK's logging — which writes whole frames at
 * trace, and a frame carries a seat's token (S2.1's Found line) — goes nowhere at any level (DaemonLoggingSpec).
 */
object Journal {
    fun say(text: String) = System.err.println("${Instant.now().truncatedTo(ChronoUnit.SECONDS)} kp-daemon: $text")
}
