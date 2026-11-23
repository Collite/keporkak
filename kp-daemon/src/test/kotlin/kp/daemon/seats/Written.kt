package kp.daemon.seats

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Waits until [done] holds of the log at [file] — what a seat's process writes beside the daemon, as a fake agent records
 * what it received (S3.10 T2, D-106): [done] is asked again at each change of the log's folder, never after a time. A
 * spec that reads what a seat wrote waits for that record here; the daemon's own log it waits for by the office's
 * changes. Past [seconds] it fails, naming [what] and what the log holds — the bound of a record that never comes.
 */
fun awaitWritten(file: Path, what: String, seconds: Long = 30, done: () -> Boolean) {
    val folder = file.parent.createDirectories()
    FileSystems.getDefault().newWatchService().use { watch ->
        folder.register(watch, ENTRY_CREATE, ENTRY_MODIFY)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        // Asked once the folder is watched: a record written before it is read here, one written after wakes the wait.
        while (!holds(done)) {
            val left = end - System.nanoTime()
            if (left <= 0) throw AssertionError("waited ${seconds}s for $what; $file holds:\n" + (if (file.exists()) file.readText() else "(nothing: it does not exist)"))
            watch.poll(left, TimeUnit.NANOSECONDS)?.let { key ->
                key.pollEvents()
                key.reset()
            }
        }
    }
}

/** [done], with a line the writer has only begun — a record torn at its end — read as not yet there. */
private fun holds(done: () -> Boolean): Boolean = try {
    done()
} catch (_: IllegalArgumentException) {
    false
}
