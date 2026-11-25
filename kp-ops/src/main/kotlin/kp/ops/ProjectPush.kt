package kp.ops

import java.nio.file.Files
import java.nio.file.Path

/**
 * `project.commit_push` (contracts.md §7, Amendment 36) on the project [checkout]: commits every changed path that is
 * not ignored, as `kp-merger`; fetches; merges the remote's branch in when it has moved — never a rebase, and never a
 * file of `mail/` rewritten: the log's writer holds those files open; pushes to the branch's upstream with a token
 * [mint] gives for that repository — over https when the remote is GitHub's, to any other remote as it is — handed
 * to `git` through its environment. A merge that conflicts is given up: `failed(conflict)`. After any failure the
 * checkout holds every change it held and no merge in progress. `ok` carries the pushed sha.
 *
 * A merge it finds in progress — one an earlier run was cut off in — is given up before anything is added or committed,
 * and [say], the journal, hears it: no conflict marker, and nothing of a half-made merge, is ever committed (Amendment 43;
 * Collite/keporkak#65, 1). It reads no configuration of the office user's or the system's, and its git runs no hook of the
 * checkout's (GitCommand): no hook, signing or credential of anyone's takes part.
 */
class ProjectPush(
    private val checkout: Path,
    private val mint: (repository: String) -> Minted,
    private val git: GitCommand = GitCommand(),
    private val say: (String) -> Unit = {},
) {
    /** One commit, fetch, merge and push, the commit's message naming its [cause]. */
    fun commitPush(cause: String): Performed {
        val branch = read("symbolic-ref", "--quiet", "--short", "HEAD") ?: return Performed.Failed("the project checkout ${checkout} is on no branch")
        if (inProgress()) {
            val given = run("merge", "--abort")
            if (!given.ok || inProgress()) return Performed.Failed("the project checkout $checkout has a merge in progress that could not be given up: ${given.output.trim()}")
            say("project.commit_push for $cause: the project checkout had a merge in progress — an earlier run's, cut off in it; it was given up before anything was added or committed")
        }
        val added = run("add", "--all", ".")
        if (!added.ok) return Performed.Failed("git add failed: ${added.output.trim()}")
        if (!run("diff", "--cached", "--quiet").ok) {
            val committed = run("commit", "--quiet", "--message", "$PROJECT$cause")
            if (!committed.ok) return Performed.Failed("the commit failed: ${committed.output.trim()}")
        }
        val remote = read("config", "branch.$branch.remote") ?: return Performed.Failed("the project checkout's branch $branch has no upstream")
        val merge = read("config", "branch.$branch.merge") ?: return Performed.Failed("the project checkout's branch $branch has no upstream")
        val upstream = "$remote/${merge.removePrefix(HEADS)}"
        val url = read("remote", "get-url", remote) ?: return Performed.Failed("the project checkout has no remote $remote")
        val token = when (val minted = mint(repository(url))) {
            is Minted.Ok -> minted.token
            is Minted.Failed -> return Performed.Failed("no token for ${repository(url)}: ${minted.reason}")
        }
        val reaching = reaching(url, token)
        val fetched = run("fetch", "--quiet", remote, more = reaching)
        if (!fetched.ok) return Performed.Failed("fetching $remote failed: ${fetched.output.trim()}")
        if (!run("merge-base", "--is-ancestor", upstream, "HEAD").ok) {
            merged(upstream, cause)?.let { return it }
        }
        val pushed = run("push", "--porcelain", remote, "HEAD:$merge", more = reaching)
        if (!pushed.ok) return Performed.Failed("the push to $remote was refused: ${pushed.output.trim()}")
        return Performed.Ok(mapOf(SHA to (read("rev-parse", "HEAD") ?: "")))
    }

    /**
     * What the project checkout holds of an operation interrupted, as the journal says it: read only, nothing touched —
     * a merge in progress, or none.
     */
    fun describe(): String {
        val inProgress = read("rev-parse", "--git-path", "MERGE_HEAD")?.let { checkout.resolve(it) }?.let(Files::exists) == true
        return if (inProgress) "the project checkout $checkout has a merge in progress" else "the project checkout $checkout has no merge in progress"
    }

    /**
     * Merges [upstream] in, as a merge and never a rebase: given up when the remote moved in a file of `mail/` — merging
     * would rewrite a file the log's writer holds open — or when it conflicts. Null once merged.
     */
    private fun merged(upstream: String, cause: String): Performed? {
        val theirs = read("diff", "--name-only", "HEAD...$upstream").orEmpty().lines().filter { it.isNotBlank() }
        theirs.filter { it.startsWith(MAIL) }.takeIf { it.isNotEmpty() }?.let { log ->
            return Performed.Failed("$CONFLICT: the remote moved in ${log.joinToString(", ")} — a file of the log, which no merge rewrites")
        }
        val merging = run("merge", "--no-edit", "--no-stat", "--message", "${PROJECT}merges $upstream — $cause", upstream)
        if (merging.ok) return null
        val conflicted = read("diff", "--name-only", "--diff-filter=U").orEmpty().lines().filter { it.isNotBlank() }
        if (inProgress()) run("merge", "--abort")
        return Performed.Failed(if (conflicted.isNotEmpty()) "$CONFLICT: ${conflicted.joinToString(", ")}" else "the merge of $upstream was given up: ${merging.output.trim()}")
    }

    private fun inProgress(): Boolean = read("rev-parse", "--git-path", "MERGE_HEAD")?.let { checkout.resolve(it) }?.let(Files::exists) == true

    /** What git needs to reach [url] with [token]: the token in one variable, read by a helper that names it, never its text. */
    private fun reaching(url: String, token: Token): Map<String, String> {
        val config = ArrayList<Pair<String, String>>()
        // The office user's helpers are cleared first; then the one that answers with the token, from the environment.
        config += "credential.helper" to ""
        config += "credential.helper" to HELPER
        if (GITHUB.matches(url)) GITHUB_FORMS.forEach { form -> config += "url.$GITHUB_HTTPS.insteadOf" to form }
        return mapOf(TOKEN_VARIABLE to token.text(), "GIT_CONFIG_COUNT" to "${config.size}") +
            config.withIndex().flatMap { (at, entry) -> listOf("GIT_CONFIG_KEY_$at" to entry.first, "GIT_CONFIG_VALUE_$at" to entry.second) }
    }

    private fun run(vararg args: String, more: Map<String, String> = emptyMap()): Ran = git.run(checkout, *args, more = ISOLATED + more)

    private fun read(vararg args: String): String? = run(*args).takeIf { it.ok }?.output?.trim()

    companion object {
        private const val PROJECT = "project: "
        private const val HEADS = "refs/heads/"
        private const val MAIL = "mail/"
        private const val SHA = "sha"
        private const val CONFLICT = "conflict"
        private const val TOKEN_VARIABLE = "KP_GIT_TOKEN"
        /** A credential helper that answers `get` with the token the environment holds: its text names the variable, never the token. */
        private const val HELPER = "!f() { test \"\$1\" = get && echo username=x-access-token && echo \"password=\$$TOKEN_VARIABLE\"; }; f"
        private const val GITHUB_HTTPS = "https://github.com/"
        private val GITHUB_FORMS = listOf("git@github.com:", "ssh://git@github.com/")
        private val GITHUB = Regex("^(https://github\\.com/|git@github\\.com:|ssh://git@github\\.com/)[^/]+/[^/]+?(\\.git)?/?$")
        /**
         * `kp-merger`'s identity, and no configuration of the office user's or the system's: a commit is made as it, and no
         * hook, signing or helper of theirs takes part.
         */
        private val ISOLATED = mapOf(
            "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_AUTHOR_NAME" to "kp-merger", "GIT_AUTHOR_EMAIL" to "kp-merger@keporkak.invalid",
            "GIT_COMMITTER_NAME" to "kp-merger", "GIT_COMMITTER_EMAIL" to "kp-merger@keporkak.invalid",
        )

        /** The repository a remote's [url] names: its last part, without `.git` — `keporkak-project` for GitHub's. */
        fun repository(url: String): String = url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
    }
}
