package kp.daemon.seats

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watching (contracts.md §12, architecture.md §4): a tmux session — `kp` on the office — with one window per live seat,
 * named after it, tailing its transcript; made when the seat starts, closed when it is retired or gone (`tmux attach
 * -t kp`). An office without tmux is said so once, and no operation fails for it: a pane is never a seat's condition.
 * [tmux] is the command, [session] the session's name.
 */
class Panes(private val tmux: String = "tmux", val session: String = SESSION, private val say: (String) -> Unit) {
    private val told = AtomicBoolean(false)

    /** A window [seat] in the session, tailing [transcript]: the session made with it when it is the first. */
    @Synchronized
    fun open(seat: String, transcript: Path) {
        if (!available()) return
        close(seat)
        val tail = "tail -n +1 -F '${transcript.toString().replace("'", "'\\''")}'"
        val made = if (run("has-session", "-t", session)) run("new-window", "-d", "-t", "$session:", "-n", seat, tail)
            else run("new-session", "-d", "-s", session, "-n", seat, tail)
        if (!made) say("panes: tmux would not open a window for $seat")
    }

    /** [seat]'s window closed, if it has one. */
    @Synchronized
    fun close(seat: String) {
        if (!available()) return
        if (windows().contains(seat)) run("kill-window", "-t", "$session:$seat")
    }

    /** The names of the session's windows; none when it does not exist or tmux is not here. */
    @Synchronized
    fun windows(): List<String> {
        if (!available()) return emptyList()
        val listed = output("list-windows", "-t", "$session:", "-F", "#{window_name}") ?: return emptyList()
        return listed.lines().filter { it.isNotBlank() }
    }

    /** Whether tmux is on this office; when it is not, that is said once. */
    private fun available(): Boolean {
        val here = output("-V") != null
        if (!here && told.compareAndSet(false, true)) say("panes: no tmux on this office (`$tmux`): seats run without panes")
        return here
    }

    private fun run(vararg words: String): Boolean = output(*words) != null

    /** tmux's output, or null when it cannot be run or answers with an error. */
    private fun output(vararg words: String): String? = try {
        val process = ProcessBuilder(listOf(tmux) + words).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
    } catch (_: java.io.IOException) {
        null
    }

    companion object {
        const val SESSION = "kp"
        private const val WAIT_SECONDS = 10L
    }
}
