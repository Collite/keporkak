package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private const val BRANCH = "feat/kp-keporkak-p3-s3.3-operations-on-the-office"

/**
 * KP/P3/S3.3 — a stage's worktree, T1's second sentence (contracts.md §7, Amendment 36). The seam is the performer, on a
 * main checkout cloned from a remote the spec builds on disk with the real `git`; the remote has moved since the clone,
 * so a worktree made from the fetched default branch is told apart from one made from what the clone had.
 */
class TheStagesWorktreeSpec : FreeSpec({
    "worktree.create makes the stage's worktree on the list's branch at the remote's default branch, fetched, and its scratch folder" {
        val office = Office()

        val created = Worktrees().create(office.stage)

        created shouldBe Performed.Ok()
        git(office.stage.worktree, "rev-parse", "HEAD") shouldBe office.remote.head("master")
        git(office.stage.worktree, "branch", "--show-current") shouldBe BRANCH
        office.stage.scratch.isDirectory() shouldBe true
    }

    "the daemon's git runs no hook of the main checkout's: a post-checkout hook planted there does not run at worktree.create, nor a reference-transaction one at worktree.remove (S3.11 T7, Amendment 43)" {
        val office = Office()
        val ran = office.main.resolveSibling("hooks-ran")
        listOf("post-checkout", "reference-transaction").forEach { hook ->
            office.main.resolve(".git/hooks/$hook").toFile().apply { writeText("#!/bin/sh\necho $hook >> '$ran'\n"); setExecutable(true) }
        }

        Worktrees().create(office.stage) shouldBe Performed.Ok()
        Worktrees().remove(office.stage) shouldBe Performed.Ok()

        ran.toFile().exists() shouldBe false
    }

    "a second worktree.create, the worktree there on its branch with no change, is ok and changes nothing" {
        val office = Office()
        Worktrees().create(office.stage)
        val before = git(office.stage.worktree, "status", "--porcelain", "--branch") + git(office.main, "worktree", "list", "--porcelain")

        val again = Worktrees().create(office.stage)

        again shouldBe Performed.Ok()
        git(office.stage.worktree, "status", "--porcelain", "--branch") + git(office.main, "worktree", "list", "--porcelain") shouldBe before
    }

    "worktree.create with the path taken is failed and names what is there: a worktree on another branch, or a file" {
        val office = Office()
        git(office.main, "worktree", "add", "--quiet", "-b", "feat/another", office.stage.worktree.toString())
        val other = Office()
        write(other.stage.worktree.parent, other.stage.worktree.fileName.toString(), "a file\n")

        val onAnotherBranch = Worktrees().create(office.stage)
        val onAFile = Worktrees().create(other.stage)

        onAnotherBranch shouldBe Performed.Failed("${office.stage.worktree} is a worktree on the branch feat/another, not $BRANCH")
        onAFile shouldBe Performed.Failed("${other.stage.worktree} is a file, not a worktree")
    }

    "worktree.remove of a clean worktree removes it and its scratch folder and leaves the branch" {
        val office = Office()
        Worktrees().create(office.stage)

        val removed = Worktrees().remove(office.stage)

        removed shouldBe Performed.Ok()
        office.stage.worktree.exists() shouldBe false
        office.stage.scratch.exists() shouldBe false
        git(office.main, "branch", "--list", BRANCH) shouldBe BRANCH
    }

    "worktree.remove of a worktree with an untracked file, or a modified tracked one, is failed, names the file and removes nothing" {
        val untracked = Office().also { Worktrees().create(it.stage) }
        val modified = Office().also { Worktrees().create(it.stage) }
        write(untracked.stage.worktree, "notes.txt", "not committed\n")
        append(modified.stage.worktree, "README.md", "changed\n")

        val withUntracked = Worktrees().remove(untracked.stage)
        val withModified = Worktrees().remove(modified.stage)

        withUntracked shouldBe Performed.Failed("${untracked.stage.worktree} holds a change in no commit: notes.txt (untracked)")
        withModified shouldBe Performed.Failed("${modified.stage.worktree} holds a change in no commit: README.md (modified)")
        listOf(untracked, modified).forEach { office ->
            office.stage.worktree.resolve("README.md").exists() shouldBe true
            office.stage.scratch.isDirectory() shouldBe true
        }
    }
})

/**
 * An office's view of one repository: a remote whose `master` moved after the main checkout was cloned from it, and the
 * places of stage KP/P3/S3.3 — its worktree and scratch folder under roots of the spec's own.
 */
private class Office {
    val remote = Remote("keporkak.git", mapOf("README.md" to "keporkak\n"))
    val main = remote.clone("keporkak")
    private val roots = folder("roots-")
    val stage = StageWorktree(main, "master", BRANCH, roots.resolve("worktrees/kp-p3-s3.3"), roots.resolve("scratch/kp-p3-s3.3"))

    init {
        roots.resolve("worktrees").toFile().mkdirs()
        roots.resolve("scratch").toFile().mkdirs()
        remote.moves(mapOf("CHANGELOG.md" to "moved after the clone\n"))
    }
}
