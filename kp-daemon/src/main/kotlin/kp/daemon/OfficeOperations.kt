package kp.daemon

import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.ops.GitCommand
import kp.ops.GitHubApps
import java.net.URI
import java.nio.file.Path

/**
 * The office's performers (architecture.md §2, `kp-ops`; contracts.md §7, Amendment 36): `worktree.create`,
 * `worktree.remove`, `register.rebuild` and `project.commit_push`, each on the checkout the rules in force name for the
 * office [office] — the project checkout [project], the stage's repository's main checkout, its worktree. `notify.bora`
 * ends `ok` at once: the inbox is the notification. Every other operation without a performer answers `failed`,
 * naming itself. [keporkak] is the checkout whose `scripts/` and `docs/profiles/` it reads; [say] is the journal.
 */
class OfficeOperations(
    private val keporkak: Path,
    private val office: String,
    private val project: Path,
    private val git: GitCommand = GitCommand(),
    private val api: URI = GitHubApps.GITHUB,
    private val say: (String) -> Unit = Journal::say,
) : Operations {
    @Volatile private var reading: Office? = null

    /** Reads [office]'s rules in force and its fold from now on: what a request's stage and checkouts are read from. */
    fun reading(office: Office) {
        reading = office
    }

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
        done(if (request.operation == NOTIFY_BORA) OperationResult.Ok() else OperationResult.Failed("no performer for ${request.operation} on this office yet"))
    }

    private companion object {
        const val NOTIFY_BORA = "notify.bora"
    }
}
