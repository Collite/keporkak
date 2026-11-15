package kp.driver.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One agent process: started with exactly the command, directory and environment given — nothing inherited — and its
 * stdio as lines. Its stderr is drained; only its last lines are kept, for a failure's transcript.
 */
internal class AgentProcess private constructor(private val process: Process) {
    val pid: Long get() = process.pid()
    val exit: CompletableFuture<Process> = process.onExit()
    private val writer = process.outputStream.bufferedWriter()
    private val stderr = ArrayDeque<String>()

    init {
        thread(isDaemon = true, name = "kp-driver-stderr-${process.pid()}") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) { if (stderr.size == STDERR_LINES) stderr.removeFirst(); stderr.addLast(line) }
                }
            }
        }
    }

    /** The agent's stdout, one line at a time; it ends when the process closes it. Collected once, by the transport. */
    fun lines(): Flow<String> = flow {
        val reader = process.inputStream.bufferedReader()
        while (true) emit(reader.readLine() ?: break)
    }.flowOn(Dispatchers.IO)

    /** Writes one line to the agent's stdin. A closed pipe throws [IOException], which the transport takes as closed. */
    suspend fun write(line: String) = withContext(Dispatchers.IO) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    fun closeInput() {
        runCatching { synchronized(writer) { writer.close() } }
    }

    fun alive(): Boolean = process.isAlive

    fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    fun stderrTail(): List<String> = synchronized(stderr) { stderr.toList() }

    /**
     * Closes the agent's input, waits up to [grace] for it to leave, then ends its whole tree by force. The tree is
     * collected while waiting, so a child the agent leaves behind when it exits is ended too. Returns when every
     * process of the tree is gone.
     */
    fun end(grace: Duration) {
        val tree = mutableSetOf<ProcessHandle>()
        fun collect() = process.descendants().forEach { tree += it }
        collect()
        closeInput()
        val until = System.nanoTime() + grace.toNanos()
        while (process.isAlive && System.nanoTime() < until) {
            collect()
            process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        collect()
        process.destroyForcibly()
        tree.forEach { it.destroyForcibly() }
        process.waitFor()
        tree.forEach { handle -> runCatching { handle.onExit().get(TREE_EXIT_SECONDS, TimeUnit.SECONDS) } }
    }

    companion object {
        private const val STDERR_LINES = 20
        private const val POLL_MILLIS = 20L
        private const val TREE_EXIT_SECONDS = 5L

        fun start(command: List<String>, directory: Path, environment: Map<String, String>): AgentProcess {
            val builder = ProcessBuilder(command).directory(directory.toFile())
            builder.environment().clear()
            builder.environment().putAll(environment)
            return AgentProcess(builder.start())
        }
    }
}
