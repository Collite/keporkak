package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.seats.GitOverHttps
import kp.daemon.seats.IdentityOffice
import kp.daemon.seats.PlantedHome
import kp.engine.Datum
import kp.facts.Observation
import kp.facts.RecordedGitHub.Companion.checkRuns
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_HEAD
import kp.facts.TOY_STAGE
import kp.facts.byTheReviewer
import kp.facts.pull
import kp.facts.run
import kp.facts.toyCheckRuns
import kp.facts.toyPullRequests
import kp.facts.toyReviews
import kp.mailroom.Status
import kp.ops.ProjectPush
import kp.ops.Remote
import kotlin.io.path.exists

/** What every reader says of a stage that has no `worktree.create` record (contracts.md §4.4, Amendments 40, 43). */
private const val NO_RECORD = "TOY/P0/S0.1 has no worktree.create that ended ok: no repository and no branch are fixed for it (contracts.md §4.4, Amendment 40)"

/** The five forms of a GitHub remote a seat may meet (Amendment 43), each naming the toy. */
private val GITHUB_REMOTES = listOf(
    "git@github.com:Collite/kp-toy.git",
    "ssh://git@github.com/Collite/kp-toy.git",
    "ssh://github.com/Collite/kp-toy.git",
    "git+ssh://git@github.com/Collite/kp-toy.git",
    "https://github.com/Collite/kp-toy.git",
)

/**
 * KP/P3/S3.11 — after the third wave's reviews (T1). Bora reads these. The seams: the service's readers of a stage's
 * repository and branch — `kp-facts`' two entries through the daemon's world, its operations on GitHub and on the office,
 * a seat's GitHub identity asked through the seat API — over a log that holds, or lacks, the stage's `worktree.create`
 * record, beside a list whose first line was rewritten after it ([RecordedOffice]); the daemon acting on GitHub under
 * master's rules ([ActingOffice]); a seat's process started beside a planted home of the office user's, pushing to a
 * GitHub on this machine ([IdentityOffice], [GitOverHttps], [PlantedHome]); the project's push on a checkout with a bare
 * remote ([ProjectPush], [Remote]). GitHub's API is S3.2's local server throughout.
 */
class AfterTheThirdWaveSpec : FreeSpec({
    "a stage's repository and branch are those of its `worktree.create` record for every reader — the nine GitHub facts, `git.branch_exists`, the three operations on GitHub, `worktree.remove`, a seat's token: a first line rewritten to another repository, another branch, or no heading at all changes none of them" - {
        mapOf(
            "another repository of the office" to RecordedOffice.heading(repo = "keporkak"),
            "another branch" to RecordedOffice.heading(branch = "feat/toy-kp-toy-p0-s0.1-elsewhere"),
            "no heading at all" to "The skeleton, its heading taken out",
        ).forEach { (rewritten, first) ->
            "rewritten to $rewritten" {
                RecordedOffice().use { office ->
                    val before = office.everyReader()

                    office.rewrite(first)

                    office.everyReader() shouldBe before
                    before.filterValues { it is Observation.Unknown }.keys.shouldBeEmpty()
                    before["git.branch_exists"] shouldBe Observation.Value(true, before.getValue("git.branch_exists").evidence)
                    office.perform("git.merge", RecordedOffice.MERGE) shouldBe """ok {"sha":"0c6b33afb43dd4addb3147ad73edf4c53087a17a"}"""
                    office.perform("pr.close", RecordedOffice.MERGE) shouldBe """ok {"closed":"Collite/kp-toy#1"}"""
                    office.perform("issue.file", RecordedOffice.VERDICT, mapOf("found" to Datum.Text("a Found line"))) shouldBe """ok {"numbers":[7]}"""
                    office.perform("worktree.remove", RecordedOffice.MERGE) shouldStartWith "ok"
                    office.listsWorktree(office.worktree) shouldBe false
                    office.ask("coder").first shouldBe 200
                    office.mint("coder") shouldBe """ok {"app":"kp-coder","repositories":["Collite/kp-toy"],"permissions":{"contents":"write","pull_requests":"write"}}"""
                    office.mintedFor().toSet() shouldBe setOf("kp-merger [kp-toy]", "kp-coder [kp-toy]")
                    office.asked().filterNot { it.contains(" /repos/Collite/kp-toy/") }.shouldBeEmpty()
                }
            }
        }
    }

    "a stage with no such record — none yet, or one that `failed` — has no repository: its GitHub facts and `git.branch_exists` are unknown with that reason, a token is refused (403), an operation on GitHub `failed`; `token.mint` at a seat's start still ends `ok`, minted as for a seat with no stage" - {
        mapOf("none yet" to WorktreeMade.NONE, "one that failed" to WorktreeMade.FAILED).forEach { (what, made) ->
            "$what" {
                RecordedOffice(made).use { office ->
                    office.everyReader().mapValues { (_, answer) -> (answer as? Observation.Unknown)?.missing } shouldBe
                        office.everyReader().mapValues { NO_RECORD }

                    office.ask("coder") shouldBe (403 to """{"error":"$NO_RECORD"}""")
                    office.journal shouldContainLine "github-token: coder-1 (coder) refused: $NO_RECORD"
                    office.perform("git.merge", RecordedOffice.MERGE) shouldBe "failed refused: where TOY/P0/S0.1 acts on GitHub is unknown: $NO_RECORD"
                    office.perform("pr.close", RecordedOffice.MERGE) shouldBe "failed where TOY/P0/S0.1 acts on GitHub is unknown: $NO_RECORD"
                    office.perform("issue.file", RecordedOffice.VERDICT, mapOf("found" to Datum.Text("a Found line"))) shouldBe
                        "failed where TOY/P0/S0.1 acts on GitHub is unknown: $NO_RECORD"
                    office.mint("coder") shouldBe
                        """ok {"app":"kp-coder","repositories":["Collite/keporkak","Collite/kp-toy"],"permissions":{"contents":"write","pull_requests":"write"}}"""
                    office.asked().shouldBeEmpty()
                }
            }
        }
    }

    "a `merge` on a head with no review of the reviewer's, a review that asks for changes, or checks not green is refused by master's rules, naming the rule; on a reviewed green head the daemon merges it" {
        val reviewed = """{"fact":"pr.review_on","sha":{"fact":"pr.head"},"in":["approved","commented"]}"""
        val green = """{"fact":"ci.state","of":{"fact":"pr.head"},"eq":"green"}"""
        ActingOffice(mapOf(TOY_STAGE to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", kp.facts.Answer(200, recorded("merge-200.json")))

            office.github.toyReviews()
            office.send("teamlead", MERGE_THE_TOY) should refusedBy("merge", reviewed)
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "CHANGES_REQUESTED", "2026-11-24T08:30:00Z"))
            office.send("teamlead", MERGE_THE_TOY) should refusedBy("merge", reviewed)
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-24T08:40:00Z"))
            office.github.toyCheckRuns(TOY_HEAD, checkRuns(run("status" to "in_progress", "conclusion" to null)))
            office.send("teamlead", MERGE_THE_TOY) should refusedBy("merge", green)
            office.ops("git.merge").shouldBeEmpty()
        }
        ActingOffice(mapOf(TOY_STAGE to "passed"), fromGitHub = setOf("pr.head", "pr.review_on", "ci.state")).use { office ->
            office.github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
            office.github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
            office.github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-24T08:40:00Z"))
            office.github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", kp.facts.Answer(200, recorded("merge-200.json")))

            office.send("teamlead", MERGE_THE_TOY).status shouldBe Status.DELIVERED
            office.awaitResult("git.merge")
            office.ops("git.merge") shouldBe listOf("requested null", """ok {"sha":"0c6b33afb43dd4addb3147ad73edf4c53087a17a"}""")
            office.state() shouldBe "merged"
        }
    }

    "a seat's `git` and `gh`, by any path and any form of a GitHub remote, act as the seat's App or not at all: beside a planted login of `gh`, a global and a system git configuration with a helper, a rewrite to ssh and an identity, and an ssh command, none of the planted is used" {
        GitOverHttps().use { github ->
            val planted = PlantedHome(kotlin.io.path.createTempDirectory("kp-planted-home-"))
            IdentityOffice(base = mapOf("HOME" to planted.home.toString()) + planted.system()).use { office ->
                val coder = office.seat("coder-1", "coder", TOY_STAGE)
                val net = github.network()

                val gh = coder.run(OFFICE_GH, "auth", "status", network = net)
                gh.out + gh.err shouldContain "You are not logged into any GitHub hosts"
                gh.out + gh.err shouldNotContain "planted"

                coder.run("git", "init", "--quiet", "--initial-branch=main", "work", network = net).exit shouldBe 0
                coder.run("git", "-C", "work", "config", "credential.helper", "store", network = net)
                coder.run("git", "-C", "work", "commit", "--quiet", "--allow-empty", "-m", "the seat's commit", network = net).exit shouldBe 0
                coder.run("git", "-C", "work", "log", "-1", "--format=%an <%ae> · %cn <%ce>", network = net).out.trim() shouldBe
                    "kp-coder <kp-coder@keporkak.invalid> · kp-coder <kp-coder@keporkak.invalid>"

                GITHUB_REMOTES.forEach { remote -> coder.run("git", "-C", "work", "push", remote, "HEAD:refs/heads/kp-spec", network = net, seconds = 60) }
                github.connects.toSet() shouldBe setOf("github.com:443")
                github.seen.count { it.password != null && it.target == "/Collite/kp-toy.git/info/refs?service=git-receive-pack" } shouldBe 5
                github.passwords().map(office::issuedTo).toSet() shouldBe setOf("kp-coder")

                val alias = coder.run("git", "-C", "work", "push", "gh-planted:Collite/kp-toy.git", "HEAD:refs/heads/kp-spec", network = net)
                alias.exit shouldBe 128
                alias.err shouldContain "a seat reaches GitHub over https only: git's ssh is refused (contracts.md §4.4, Amendment 43)"

                planted.ran().shouldBeEmpty()
                planted.credentials.exists() shouldBe false
            }
        }
    }

    "a `project.commit_push` that finds a merge in progress — with conflict markers, and with none — commits nothing of it; and no hook of the checkout runs at its commit, its merge or its push" - {
        "with conflict markers" {
            val project = InterruptedMerge(conflicting = true)

            val pushed = project.push.commitPush("m-panda-000050 (stage-exit of KP/P3/S3.11)")

            pushed shouldBe kp.ops.Performed.Failed("conflict: STATUS.md")
            project.remote.show("STATUS.md") shouldBe "# KP — status, changed elsewhere"
            project.checkout.resolve("STATUS.md").toFile().readText() shouldBe "# KP — status, changed here\n"
            project.mergeInProgress() shouldBe false
            project.hooksRan().shouldBeEmpty()
        }
        "with none" {
            val project = InterruptedMerge(conflicting = false)

            val pushed = project.push.commitPush("m-panda-000051 (stage-exit of KP/P3/S3.11)")

            pushed shouldBe kp.ops.Performed.Ok(mapOf("sha" to project.remote.head()))
            project.remote.subjects() shouldBe listOf(
                "project: merges origin/master — m-panda-000051 (stage-exit of KP/P3/S3.11)",
                "project: m-panda-000051 (stage-exit of KP/P3/S3.11)",
                "the first commit",
            )
            project.remote.show("plan.md") shouldBe "# plan, changed elsewhere"
            project.mergeInProgress() shouldBe false
            project.hooksRan().shouldBeEmpty()
        }
    }
})

/** The office's own `gh`, by its full path — the first on the machine's path — as a seat that skips its home's would run it. */
private val OFFICE_GH: String = System.getenv("PATH").orEmpty().split(':').map { java.nio.file.Path.of(it, "gh") }.firstOrNull { java.nio.file.Files.isExecutable(it) }?.toString()
    ?: error("no gh on the machine's path: the office's gh is what this spec runs by its full path")

private infix fun List<String>.shouldContainLine(line: String) = this.filter { it == line } shouldBe listOf(line)

/**
 * A project checkout whose last `project.commit_push` was cut off inside its merge (S3.11 T1, T7; Collite/keporkak#65,
 * 1): the remote moved — in `STATUS.md`, against a change of the checkout's own, when [conflicting], else in `plan.md` —
 * and the checkout's `git merge` of it stopped, with its conflict markers in `STATUS.md`, or with a clean merge staged and
 * not committed; the log went on after it. Then a hook of every kind the daemon's git could start is planted in the
 * checkout, each noting that it ran.
 */
private class InterruptedMerge(conflicting: Boolean) {
    val remote = Remote("project.git", mapOf(
        ".gitignore" to "mail/.writer.lock\n",
        "STATUS.md" to "# KP — status\n",
        "plan.md" to "# plan\n",
        "mail/KP/p3-s3.11.jsonl" to "{\"seq\":1}\n",
    ))
    val checkout: java.nio.file.Path = remote.clone("project")
    private val ran: java.nio.file.Path = checkout.resolveSibling("hooks-ran")
    private val apps = kp.ops.FakeApps.start(checkout.resolveSibling("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    /** The performer, its token minted as `kp-merger`'s by the local GitHub's App endpoints. */
    val push = ProjectPush(checkout, { repository -> kp.ops.GitHubApps(checkout.resolveSibling("apps"), apps.api).mint(kp.ops.App("kp-merger", 5849229, 184045472), listOf(repository), mapOf("contents" to "write")) })

    init {
        if (conflicting) {
            remote.moves(mapOf("STATUS.md" to "# KP — status, changed elsewhere\n"))
            kp.ops.write(checkout, "STATUS.md", "# KP — status, changed here\n")
            kp.ops.git(checkout, "commit", "--quiet", "-am", "a change of the checkout's own")
        } else {
            remote.moves(mapOf("plan.md" to "# plan, changed elsewhere\n"))
        }
        kp.ops.git(checkout, "fetch", "--quiet", "origin")
        val merging = ProcessBuilder("git", "-C", checkout.toString(), "merge", "--no-commit", "--no-ff", "origin/master")
            .apply { environment().putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_AUTHOR_NAME" to "Fixture",
                "GIT_AUTHOR_EMAIL" to "fixture@example.invalid", "GIT_COMMITTER_NAME" to "Fixture", "GIT_COMMITTER_EMAIL" to "fixture@example.invalid")) }
            .redirectErrorStream(true).start()
        merging.inputStream.readAllBytes()
        merging.waitFor()
        check(mergeInProgress()) { "the fixture's merge is not in progress" }
        // The log went on after the cut: a line the next push carries.
        kp.ops.append(checkout, "mail/KP/p3-s3.11.jsonl", "{\"seq\":2}\n")
        listOf("pre-commit", "commit-msg", "post-commit", "pre-merge-commit", "post-merge", "pre-push", "reference-transaction").forEach { hook ->
            checkout.resolve(".git/hooks/$hook").toFile().apply {
                writeText("#!/bin/sh\necho $hook >> '$ran'\n")
                setExecutable(true)
            }
        }
    }

    fun mergeInProgress(): Boolean = checkout.resolve(".git/MERGE_HEAD").exists()

    fun hooksRan(): List<String> = if (ran.exists()) ran.toFile().readLines().filter { it.isNotBlank() } else emptyList()
}

/** The subjects of the remote's `master`, along its first parents, newest first. */
private fun Remote.subjects(): List<String> = kp.ops.git(dir, "log", "--first-parent", "--format=%s", "master").lines()
