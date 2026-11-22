package kp.ops

import java.nio.file.Path
import java.time.Duration

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
    /** `git -C <dir> <args>`, with [more] added to its environment — a secret among them, which nothing here prints. */
    fun run(dir: Path, vararg args: String, more: Map<String, String> = emptyMap()): Ran =
        runOnce(listOf(executable, "-C", dir.toString()) + args, limit) { environment ->
            environment.keys.removeAll { it in UNPASSED }
            environment["GIT_TERMINAL_PROMPT"] = "0"
            environment.putAll(this.environment)
            environment.putAll(more)
        }

    /** What `git -C <dir> <args>` printed when it succeeded; null when it failed. */
    fun read(dir: Path, vararg args: String): String? = run(dir, *args).takeIf { it.ok }?.output?.trim()

    companion object {
        /** How long one git command may take: a fetch or a push reaches a network. */
        val LIMIT: Duration = Duration.ofMinutes(5)

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
