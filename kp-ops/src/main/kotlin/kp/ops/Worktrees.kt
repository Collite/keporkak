package kp.ops

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A stage's worktree as `worktree.create` and `worktree.remove` see it (contracts.md §7, Amendment 36): the [main]
 * checkout of the stage's repository, its [defaultBranch] — the profile's `default_branch` — the [branch] the list's
 * first line names, and the two folders, `<roots.worktrees>/<effort>-p<n>-s<n.m>` and the scratch folder of that name.
 */
data class StageWorktree(val main: Path, val defaultBranch: String, val branch: String, val worktree: Path, val scratch: Path)

/**
 * `worktree.create` and `worktree.remove` (contracts.md §7, Amendment 36), as the office user, with [git]: the main
 * checkout's remote is `origin`, reached as the office user reaches it.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class Worktrees(private val git: GitCommand = GitCommand()) {
    /**
     * Fetches the default branch and adds the worktree on the stage's branch, made from the fetched branch — tracking
     * nothing, so that the stage's branch never pulls or pushes the default one; makes the scratch folder. A worktree
     * already there on that branch, with no change, is `ok`; any other thing at that path is `failed`, naming it — and
     * so is a branch of that name made before: it is not made again from the default branch.
     */
    fun create(stage: StageWorktree): Performed {
        val path = stage.worktree
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!path.isDirectory()) return Performed.Failed("$path is a file, not a worktree")
            val branch = branchOf(stage.main, path) ?: return Performed.Failed("$path is there and is no worktree of ${stage.main}")
            if (branch != stage.branch) return Performed.Failed("$path is a worktree on ${if (branch.isEmpty()) "no branch" else "the branch $branch"}, not ${stage.branch}")
            changes(path)?.let { return Performed.Failed("$path is a worktree on ${stage.branch} that holds a change in no commit: $it") }
            stage.scratch.createDirectories()
            return Performed.Ok()
        }
        val fetched = git.run(stage.main, "fetch", "--quiet", ORIGIN, stage.defaultBranch)
        if (!fetched.ok) return Performed.Failed("fetching $ORIGIN's ${stage.defaultBranch} in ${stage.main} failed: ${fetched.output}")
        if (git.run(stage.main, "rev-parse", "--verify", "--quiet", "refs/heads/${stage.branch}").ok) {
            return Performed.Failed("the branch ${stage.branch} is in ${stage.main} already: worktree.create makes it from $ORIGIN/${stage.defaultBranch}, and does not take one made before")
        }
        val added = git.run(stage.main, "worktree", "add", "--quiet", "--no-track", "-b", stage.branch, path.toString(), "$ORIGIN/${stage.defaultBranch}")
        if (!added.ok) return Performed.Failed("adding the worktree $path in ${stage.main} failed: ${added.output}")
        stage.scratch.createDirectories()
        return Performed.Ok()
    }

    /**
     * Removes the worktree and the scratch folder; a worktree that holds a change in no commit — tracked or untracked,
     * not ignored — is not removed: `failed`, naming what it holds. The branch is left as it is. With nothing at the
     * worktree's path there is no worktree to remove: the scratch folder is.
     */
    fun remove(stage: StageWorktree): Performed {
        val path = stage.worktree
        if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!path.isDirectory() || branchOf(stage.main, path) == null) return Performed.Failed("$path is there and is no worktree of ${stage.main}")
            changes(path)?.let { return Performed.Failed("$path holds a change in no commit: $it") }
            val removed = git.run(stage.main, "worktree", "remove", path.toString())
            if (!removed.ok) return Performed.Failed("removing the worktree $path in ${stage.main} failed: ${removed.output}")
        }
        if (stage.scratch.exists()) stage.scratch.deleteRecursively()
        return Performed.Ok()
    }

    /** What is at [path], as the journal says it after an interruption: read only, nothing touched. */
    fun describe(path: Path): String = when {
        !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "nothing is at $path"
        !path.isDirectory() -> "$path is a file"
        git.read(path, "rev-parse", "--show-toplevel")?.let { Path.of(it).toRealPath() == path.toRealPath() } != true -> "$path is a folder that is no git worktree"
        else -> {
            val branch = git.read(path, "branch", "--show-current").orEmpty()
            "$path is a worktree on ${if (branch.isEmpty()) "no branch" else "the branch $branch"}" +
                (changes(path)?.let { ", holding a change in no commit: $it" } ?: ", with no change in it")
        }
    }

    /** The branch the worktree at [path] of [main] is on — empty when on none — or null when [main] lists no worktree there. */
    private fun branchOf(main: Path, path: Path): String? {
        val listed = git.read(main, "worktree", "list", "--porcelain") ?: return null
        val real = path.toRealPath()
        val entry = listed.split("\n\n").map { it.lines() }.firstOrNull { lines ->
            lines.firstOrNull()?.removePrefix("worktree ")?.let { runCatching { Path.of(it).toRealPath() == real }.getOrDefault(false) } == true
        } ?: return null
        return entry.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch refs/heads/") ?: ""
    }

    /** The changes in no commit in the worktree at [path], each a file and what it is, or null when there is none. */
    private fun changes(path: Path): String? {
        val status = git.run(path, "status", "--porcelain=v1", "-z", "--untracked-files=all")
        if (!status.ok) return "its status could not be read: ${status.output}"
        return changed(status.output).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    companion object {
        private const val ORIGIN = "origin"

        /** `git status --porcelain=v1 -z`'s entries, each as `<file> (<what>)`: untracked, modified, added, deleted, renamed. */
        internal fun changed(status: String): List<String> {
            val entries = status.split('\u0000').filter { it.isNotEmpty() }.iterator()
            val changes = ArrayList<String>()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.length < 4) continue
                val code = entry.substring(0, 2)
                if (code[0] == 'R' || code[0] == 'C') entries.takeIf { it.hasNext() }?.next()
                changes += "${entry.substring(3)} (${what(code)})"
            }
            return changes
        }

        private fun what(code: String): String = when {
            code == "??" -> "untracked"
            'U' in code || code == "AA" || code == "DD" -> "in conflict"
            'R' in code -> "renamed"
            'D' in code -> "deleted"
            'A' in code -> "added"
            'M' in code || 'T' in code -> "modified"
            else -> "changed"
        }
    }
}
