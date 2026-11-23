package kp.driver.acp

import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A seat's readable transcript, `<roots.seats>/_transcripts/<seat>.log` (contracts.md §10): one line per event,
 * `<time> <seat> <event>`, appended across the seat's sessions. Every secret the seat's sessions held — the agent's
 * token, each session token, each GitHub token handed to the seat (Amendment 35) — is replaced before a line is written,
 * and in every reason the driver returns. A GitHub token is replaced by its shape as well ([INSTALLATION_TOKEN]): one
 * handed by an earlier daemon, which this driver was never told of, is hidden when the seat's loaded session replays it
 * or the seat prints it again (review-030 B1). Nothing of a token is kept for that: no token, no digest of one.
 */
internal class Transcript(private val file: Path, private val seat: SeatId, private val clock: Clock) {
    private val secrets = CopyOnWriteArraySet<String>()

    fun hide(secret: String) {
        if (secret.isNotEmpty()) secrets += secret
    }

    /** [text] with every secret of this seat replaced, and every GitHub installation token. */
    fun hidden(text: String): String = INSTALLATION_TOKEN.replace(secrets.fold(text) { acc, secret -> acc.replace(secret, HIDDEN) }, HIDDEN)

    @Synchronized
    fun write(event: String, at: Instant = clock.instant()) {
        val line = hidden("$at ${seat.value} ${event.replace("\r", "\\r").replace("\n", "\\n")}")
        Files.createDirectories(file.parent)
        Files.writeString(file, line + "\n", CREATE, APPEND)
    }

    companion object {
        const val HIDDEN = "‹hidden›"

        /**
         * A GitHub App's installation token as GitHub writes one — `ghs_`, then letters and digits — the one kind of GitHub
         * token the daemon hands a seat (Amendment 35).
         */
        private val INSTALLATION_TOKEN = Regex("ghs_[A-Za-z0-9]+")

        fun of(seatsRoot: Path, seat: SeatId, clock: Clock) = Transcript(seatsRoot.resolve("_transcripts").resolve("${seat.value}.log"), seat, clock)
    }
}
