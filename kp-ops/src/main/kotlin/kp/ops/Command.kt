package kp.ops

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** One command's end: its exit status — -1 when it did not start or did not end in time — and what it printed, both streams together. */
class Ran(val status: Int, val output: String) {
    val ok: Boolean get() = status == 0
}

/**
 * [command] run once, reading nothing, with its environment as [adjust] leaves the office user's; ended — it and
 * what it started — when it has not ended within [limit], and then a failure. What it printed is read as it prints.
 */
internal fun runOnce(command: List<String>, limit: Duration, adjust: (MutableMap<String, String>) -> Unit = {}): Ran {
    val process = try {
        ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply { adjust(environment()) }.start()
    } catch (error: java.io.IOException) {
        return Ran(-1, "${command.first()} could not be started: ${error.message}")
    }
    val output = StringBuilder()
    val reader = Thread.ofVirtual().start { output.append(process.inputStream.bufferedReader().readText()) }
    val ended = process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)
    if (!ended) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
    // The reader ends with the process's output, which the process — ended now — no longer holds open.
    reader.join(limit)
    return if (ended) Ran(process.exitValue(), output.toString().trimEnd()) else Ran(-1, "${command.first()} did not end in $limit")
}
