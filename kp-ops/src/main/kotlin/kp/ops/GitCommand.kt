package kp.ops

import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * `git`, as its command line (architecture.md §6: no library for git): [executable], run with the office user's
 * environment and [environment] added — what a spec traces it with. A secret is handed to it through its
 * environment, never its arguments (contracts.md §4.4, Amendment 33): no variable that would print a request's
 * headers is passed on, and it never prompts. A command that does not end within [limit] is ended, and fails.
 */
class GitCommand(
    val executable: String = "git",
    private val environment: Map<String, String> = emptyMap(),
    private val limit: Duration = LIMIT,
) {
    /** One command's end: its exit status — -1 when it did not end in time — and what it printed, both streams together. */
    class Ran(val status: Int, val output: String) {
        val ok: Boolean get() = status == 0
    }

    /** `git -C <dir> <args>`, with [more] added to its environment — a secret among them, which nothing here prints. */
    fun run(dir: Path, vararg args: String, more: Map<String, String> = emptyMap()): Ran {
        val process = try {
            ProcessBuilder(listOf(executable, "-C", dir.toString()) + args).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).apply {
                    environment().apply {
                        keys.removeAll { it in UNPASSED }
                        put("GIT_TERMINAL_PROMPT", "0")
                        putAll(this@GitCommand.environment)
                        putAll(more)
                    }
                }.start()
        } catch (error: java.io.IOException) {
            return Ran(-1, "$executable could not be started: ${error.message}")
        }
        val output = StringBuilder()
        val reader = Thread.ofVirtual().start { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            reader.join(READER_MILLIS)
            return Ran(-1, "git ${args.joinToString(" ")} did not end in ${limit.toSeconds()} s")
        }
        reader.join(READER_MILLIS)
        return Ran(process.exitValue(), output.toString().trimEnd())
    }

    /** What `git -C <dir> <args>` printed when it succeeded; null when it failed. */
    fun read(dir: Path, vararg args: String): String? = run(dir, *args).takeIf { it.ok }?.output?.trim()

    companion object {
        /** How long one git command may take: a fetch or a push reaches a network. */
        val LIMIT: Duration = Duration.ofMinutes(5)
        private const val READER_MILLIS = 10_000L

        /**
         * Variables not passed on to git: those that would print a request's headers — a token's among them — and those that
         * would point it at another repository than the one it is run in, or have it ask anyone anything.
         */
        private val UNPASSED = setOf(
            "GIT_TRACE_CURL", "GIT_TRACE_CURL_NO_DATA", "GIT_CURL_VERBOSE", "GIT_TRACE_PACKET",
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_ASKPASS", "SSH_ASKPASS",
        )
    }
}
