package kp.ops

import java.nio.file.Path

/**
 * A stage's worktree as `worktree.create` and `worktree.remove` see it (contracts.md §7, Amendment 36): the [main]
 * checkout of the stage's repository, its [defaultBranch] — the profile's `default_branch` — the [branch] the list's
 * first line names, and the two folders, `<roots.worktrees>/<effort>-p<n>-s<n.m>` and the scratch folder of that name.
 */
data class StageWorktree(val main: Path, val defaultBranch: String, val branch: String, val worktree: Path, val scratch: Path)

/** `worktree.create` and `worktree.remove` (contracts.md §7, Amendment 36), as the office user, with [git]. */
class Worktrees(private val git: GitCommand = GitCommand()) {
    /**
     * Fetches the default branch and adds the worktree on the stage's branch, made from the fetched branch; makes the
     * scratch folder. A worktree already there on that branch, with no change, is `ok`; any other thing at that path is
     * `failed`, naming it.
     */
    fun create(stage: StageWorktree): Performed = Performed.Failed("worktree.create is not performed yet")

    /**
     * Removes the worktree and the scratch folder; a worktree that holds a change in no commit is not removed:
     * `failed`, naming what it holds. The branch is left as it is.
     */
    fun remove(stage: StageWorktree): Performed = Performed.Failed("worktree.remove is not performed yet")
}
