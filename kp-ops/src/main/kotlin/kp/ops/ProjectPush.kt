package kp.ops

import java.nio.file.Path

/**
 * `project.commit_push` (contracts.md §7, Amendment 36) on the project [checkout]: commits every changed path that is
 * not ignored, as `kp-merger`; fetches; merges the remote's branch in when it has moved — never a rebase, and never a
 * file of `mail/` rewritten: the log's writer holds those files open; pushes to the branch's upstream with a token
 * [mint] gives for that repository — over https when the remote is GitHub's, to any other remote as it is — handed
 * to `git` through its environment. A merge that conflicts is given up: `failed(conflict)`. After any failure the
 * checkout holds every change it held and no merge in progress. `ok` carries the pushed sha.
 */
class ProjectPush(
    private val checkout: Path,
    private val mint: (repository: String) -> Minted,
    private val git: GitCommand = GitCommand(),
) {
    /** One commit, fetch, merge and push, the commit's message naming its [cause]. */
    fun commitPush(cause: String): Performed = Performed.Failed("project.commit_push is not performed yet")
}
