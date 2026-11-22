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
 * and in every reason the driver returns.
 */
internal class Transcript(private val file: Path, private val seat: SeatId, private val clock: Clock) {
    private val secrets = CopyOnWriteArraySet<String>()

    fun hide(secret: String) {
        if (secret.isNotEmpty()) secrets += secret
    }

    /** [text] with every secret of this seat replaced. */
    fun hidden(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, HIDDEN) }

    @Synchronized
    fun write(event: String, at: Instant = clock.instant()) {
        val line = hidden("$at ${seat.value} ${event.replace("\r", "\\r").replace("\n", "\\n")}")
        Files.createDirectories(file.parent)
        Files.writeString(file, line + "\n", CREATE, APPEND)
    }

    companion object {
        const val HIDDEN = "‹hidden›"

        fun of(seatsRoot: Path, seat: SeatId, clock: Clock) = Transcript(seatsRoot.resolve("_transcripts").resolve("${seat.value}.log"), seat, clock)
    }
}
