package kp.ops

import java.time.Duration

/**
 * `git`, as its command line (architecture.md §6: no library for git): [executable], run with the office user's
 * environment and [environment] added — what a spec traces it with. A secret is handed to it through its
 * environment, never its arguments (contracts.md §4.4, Amendment 33). A command that does not end within [limit] is
 * ended, and fails.
 */
class GitCommand(
    val executable: String = "git",
    private val environment: Map<String, String> = emptyMap(),
    private val limit: Duration = LIMIT,
) {
    companion object {
        /** How long one git command may take: a fetch or a push reaches a network. */
        val LIMIT: Duration = Duration.ofMinutes(5)
    }
}
