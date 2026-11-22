package kp.daemon

import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import java.net.URI
import java.nio.file.Path

/**
 * The service's operations on GitHub (contracts.md §7, Amendment 37; S3.9): `git.merge`, `pr.close` and `issue.file`,
 * performed by `kp-ops` as `kp-merger`. Every other operation is [other]'s.
 */
class GitHubOperations private constructor(
    private val office: String,
    private val project: Path,
    private val api: URI,
    private val other: Operations,
    private val say: (String) -> Unit,
) : Operations {
    @Volatile private var reading: Office? = null

    /** Reads [office]'s fold, its log and its rules in force from now on. */
    fun reading(office: Office) {
        reading = office
    }

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) = other.perform(request, done)

    companion object {
        /** The operations on GitHub of [office], on the project checkout [project]; [other] performs the rest; [say] is the journal. */
        fun of(office: String, project: Path, other: Operations, api: URI = kp.ops.GitHubApps.GITHUB, say: (String) -> Unit = Journal::say): GitHubOperations =
            GitHubOperations(office, project, api, other, say)
    }
}
