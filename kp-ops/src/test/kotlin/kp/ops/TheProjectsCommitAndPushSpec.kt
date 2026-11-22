package kp.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * KP/P3/S3.3 — the project's commit and push, T1's fourth sentence (contracts.md §7, Amendment 36). The seam is the
 * performer on a project checkout cloned from a remote the spec builds on disk — not GitHub's, so pushed to as it is —
 * with the real `git`; the token it is handed is the spec's, minted for the remote's repository.
 */
class TheProjectsCommitAndPushSpec : FreeSpec({
    "nothing but the log changed: the remote's branch holds one new commit by kp-merger with the log's file, and ok carries its sha" {
        val project = Project()
        append(project.checkout, "mail/KP/p3-s3.3.jsonl", "{\"seq\":2}\n")

        val pushed = project.push.commitPush("m-panda-000042 (stage-exit of KP/P3/S3.3)")

        pushed shouldBe Performed.Ok(mapOf("sha" to project.remote.head()))
        git(project.remote.dir, "log", "--format=%an %s", "master") shouldBe "kp-merger project: m-panda-000042 (stage-exit of KP/P3/S3.3)\nFixture the first commit"
        git(project.remote.dir, "show", "--format=", "--name-only", "master") shouldBe "mail/KP/p3-s3.3.jsonl"
        project.minted shouldBe listOf("keporkak-project")
    }

    "a review file, a list and STATUS changed: one commit by kp-merger holds the three, and what is ignored stays out" {
        val project = Project()
        write(project.checkout, "review-030.md", "# review-030\n")
        append(project.checkout, "tasks-p3-s3.3-operations-on-the-office.md", "- [x] T1 — Specs. done abc1234\n")
        append(project.checkout, "STATUS.md", "- **2026-11-22 — S3.3 merged.**\n")
        write(project.checkout, "mail/.writer.lock", "")

        val pushed = project.push.commitPush("m-panda-000043 (stage-exit of KP/P3/S3.3)")

        pushed shouldBe Performed.Ok(mapOf("sha" to project.remote.head()))
        git(project.remote.dir, "show", "--format=%an", "--name-only", "master").lines().filter { it.isNotBlank() } shouldBe
            listOf("kp-merger", "STATUS.md", "review-030.md", "tasks-p3-s3.3-operations-on-the-office.md")
    }

    "the remote moved in another file: the push carries a merge, with both changes" {
        val project = Project()
        project.remote.moves(mapOf("plan.md" to "# plan, changed elsewhere\n"))
        append(project.checkout, "STATUS.md", "- **2026-11-22 — S3.3 merged.**\n")

        val pushed = project.push.commitPush("m-panda-000044 (stage-exit of KP/P3/S3.3)")

        pushed shouldBe Performed.Ok(mapOf("sha" to project.remote.head()))
        git(project.remote.dir, "rev-list", "--parents", "-n", "1", "master").split(" ").size shouldBe 3
        project.remote.show("plan.md") shouldBe "# plan, changed elsewhere"
        project.remote.show("STATUS.md") shouldBe "# KP — status\n- **2026-11-22 — S3.3 merged.**"
    }

    "the remote moved in the same lines: failed(conflict), no merge in progress, and every local change is still there" {
        val project = Project()
        project.remote.moves(mapOf("STATUS.md" to "# KP — status, changed elsewhere\n"))
        write(project.checkout, "STATUS.md", "# KP — status, changed here\n")
        val remoteBefore = project.remote.head()

        val pushed = project.push.commitPush("m-panda-000045 (stage-exit of KP/P3/S3.3)")

        pushed shouldBe Performed.Failed("conflict: STATUS.md")
        project.checkout.resolve(".git/MERGE_HEAD").exists() shouldBe false
        git(project.checkout, "status", "--porcelain") shouldBe ""
        project.checkout.resolve("STATUS.md").readText() shouldBe "# KP — status, changed here\n"
        project.remote.head() shouldBe remoteBefore
    }

    "the push refused: failed, and the commit is kept" {
        val project = Project()
        write(project.remote.dir, "hooks/pre-receive", "#!/bin/sh\necho 'the branch is protected' >&2\nexit 1\n").toFile().setExecutable(true)
        append(project.checkout, "STATUS.md", "- **2026-11-22 — S3.3 merged.**\n")
        val remoteBefore = project.remote.head()

        val pushed = project.push.commitPush("m-panda-000046 (stage-exit of KP/P3/S3.3)")

        pushed.shouldBeInstanceOf<Performed.Failed>()
        git(project.checkout, "log", "-1", "--format=%an %s") shouldBe "kp-merger project: m-panda-000046 (stage-exit of KP/P3/S3.3)"
        git(project.checkout, "status", "--porcelain") shouldBe ""
        project.remote.head() shouldBe remoteBefore
    }
})

/** A project checkout cloned from its remote `keporkak-project.git`, as Panda's is from GitHub, and its performer. */
private class Project {
    val remote = Remote("keporkak-project.git", mapOf(
        ".gitignore" to "mail/.writer.lock\n*.log\n",
        "STATUS.md" to "# KP — status\n",
        "plan.md" to "# plan\n",
        "tasks-p3-s3.3-operations-on-the-office.md" to "# KP-keporkak-P3 · S3.3\n",
        "mail/KP/p3-s3.3.jsonl" to "{\"seq\":1}\n",
    ))
    val checkout = remote.clone("project")
    val minted = ArrayList<String>()
    val push = ProjectPush(checkout, { repository -> minted += repository; Minted.Ok(Token("ghs_theSpecsOwnToken", "2026-11-22T10:00:00Z")) })
}
