package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.log.LogWriter
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.model.Message
import kp.model.OfficeId
import kp.ops.App
import kp.ops.FakeApps
import kp.ops.GitHubApps
import kp.ops.ProjectPush
import kp.ops.Remote
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.writeText

private const val APPROVAL = """{"kind": "approval", "stage": "$OFFICE_STAGE", "subject": "approved"}"""
private const val STAGE_EXIT = """{"kind": "stage-exit", "stage": "$OFFICE_STAGE", "subject": "S3.3 exits"}"""

/**
 * KP/P3/S3.3 — operations on the office, the daemon's half (T1's fifth sentence; contracts.md §7, Amendment 36). The seams
 * are an office in this process whose performers are the service's, on checkouts and remotes the spec builds on disk
 * ([OfficeOnDisk]); the real log writer beside the project's performer; and the daemon started through `main`.
 */
class OperationsOnTheOfficeSpec : FreeSpec({
    "Bora's approval has the office make the stage's worktree on its list's branch at keporkak's master, fetched, and its scratch folder" {
        OfficeOnDisk(mapOf(OFFICE_STAGE to "proposed")).use { office ->
            val approval = office.bora(APPROVAL)

            office.ops(approval.id) shouldContainExactly listOf("worktree.create requested", "worktree.create ok")
            val worktree = office.worktrees.resolve(OFFICE_WORKTREE)
            kp.ops.git(worktree, "rev-parse", "HEAD") shouldBe office.keporkakRemote.head("master")
            kp.ops.git(worktree, "branch", "--show-current") shouldBe OFFICE_BRANCH
            office.scratch.resolve(OFFICE_WORKTREE).isDirectory() shouldBe true
        }
    }

    "a stage's exit: its worktree and scratch removed, REGISTER.md one row per effort in offices.yaml's order, the project committed and pushed as kp-merger — then register.fresh is true" {
        OfficeOnDisk(mapOf(OFFICE_STAGE to "merged"), worktree = true).use { office ->
            val exit = office.send("teamlead", STAGE_EXIT)

            office.ops(exit.id) shouldContainExactly listOf("worktree.remove requested", "worktree.remove ok", "register.rebuild requested", "register.rebuild ok",
                "project.commit_push requested", "project.commit_push ok")
            office.worktrees.resolve(OFFICE_WORKTREE).exists() shouldBe false
            office.scratch.resolve(OFFICE_WORKTREE).exists() shouldBe false
            office.projectRemote.show("REGISTER.md").lines().filter { it.startsWith("| [") }.map { it.substringBefore(")") + ")" } shouldBe
                listOf("| [TOY](toy/STATUS.md)", "| [KP](STATUS.md)")
            kp.ops.git(office.projectRemote.dir, "log", "-1", "--format=%an", "master") shouldBe "kp-merger"
            office.fact("register.fresh") shouldBe Datum.Bool(true)
        }
    }

    "the token of a push is in no argument of any process the spec saw start, in no journal line, no op record and no error" {
        OfficeOnDisk(mapOf(OFFICE_STAGE to "merged"), worktree = true).use { office ->
            office.refusePushes()

            val exit = office.send("teamlead", STAGE_EXIT)

            office.ops(exit.id).last() shouldStartWith "project.commit_push failed"
            office.apps.issued.map { "${it.app.identity} ${it.repositories} ${it.permissions}" } shouldBe listOf("kp-merger [project] {contents=write}")
            office.started.filter { " push " in it }.shouldNotBeEmpty()
            val token = office.apps.issued.single().token
            (office.started + office.journal + office.logText().lines()).filter { token in it }.shouldBeEmpty()
        }
    }

    "the log's files are never replaced: lines the real writer appends while commits and merges run are all in its file afterwards, in order, and its file is the checkout's" {
        val remote = Remote("project.git", mapOf(".gitignore" to "mail/.writer.lock\n", "plan.md" to "# plan\n"))
        val checkout = remote.clone("project")
        val keys = Files.createTempDirectory(kp.ops.folder("keys-"), "apps")
        FakeApps.start(keys).use { github ->
            THE_APPS.forEach(github::add)
            val push = ProjectPush(checkout, { repository -> GitHubApps(keys, github.api).mint(App("kp-merger", 5849229, 184045472), listOf(repository), mapOf("contents" to "write")) })
            LogWriter.open(checkout, OfficeId("panda")).use { writer ->
                writer.append(RULES_RECORD)
                val file = checkout.resolve("mail/_office/panda-2026-11.jsonl")
                val inode = Files.getAttribute(file, "unix:ino")
                val appended = AtomicInteger(1)
                val appending = AtomicBoolean(true)
                val appender = thread { while (appending.get()) { writer.append(RULES_RECORD); appended.incrementAndGet() } }

                val rounds = (1..5).map { round ->
                    remote.moves(mapOf("plan.md" to "# plan, round $round\n"))
                    push.commitPush("round $round")
                }
                appending.set(false)
                appender.join()

                rounds.map { it::class.simpleName } shouldBe List(5) { "Ok" }
                file.readLines().map { Json.parseToJsonElement(it).jsonObject["seq"].toString().toInt() } shouldBe (1..appended.get()).toList()
                Files.getAttribute(file, "unix:ino") shouldBe inode
                file.readLines().take(remote.show("mail/_office/panda-2026-11.jsonl").lines().size) shouldBe remote.show("mail/_office/panda-2026-11.jsonl").lines()
            }
        }
    }

    "every operation without a performer on the office answers failed, naming itself" {
        val operations = OfficeOperations(checkout, "panda", checkout)
        val answers = listOf("git.merge", "pr.close", "issue.file", "release.build", "gitops.roll", "board.regenerate").map { operation ->
            var answer: OperationResult? = null
            operations.perform(OperationRequest(operation, "m-panda-000001", Datum.Fields(emptyMap()))) { answer = it }
            answer
        }

        answers shouldBe listOf("git.merge", "pr.close", "issue.file", "release.build", "gitops.roll", "board.regenerate").map { OperationResult.Failed("no performer for $it on this office yet") }
    }

    "a daemon killed while its worktree.create runs records it failed, interrupted, at its next start, performs nothing again, and its journal says what was left at the worktree's path" {
        val office = OfficeOnDisk(mapOf(OFFICE_STAGE to "proposed"), performs = false)
        val held = office.worktrees.resolveSibling("held")
        val hold = office.worktrees.resolveSibling("hold").apply { writeText("") }
        val bin = office.worktrees.resolveSibling("bin").createDirectories()
        bin.resolve("git").apply {
            writeText("#!/bin/sh\n${realGit()} \"\$@\"\nstatus=\$?\ncase \" \$* \" in *' worktree add '*) touch '$held'; while [ -e '$hold' ]; do sleep 0.1; done;; esac\nexit \$status\n")
            toFile().setExecutable(true)
        }
        val approval = office.bora(APPROVAL)
        office.close()
        val cut = office.office.lines().map { it.entry }.filterIsInstance<Message>().single { it.id.value == approval.id }.seq.toLong()
        keepLines(office.project) { it <= cut }
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        try {
            val first = Launched(office.project, socket, office.root, mapOf("PATH" to "$bin:${System.getenv("PATH")}"))
            first.awaitLine("started: office panda")
            awaitFile(held)
            first.process.destroyForcibly().waitFor(60, TimeUnit.SECONDS)
            val again = Launched(office.project, socket, office.root)
            again.awaitLine("started: office panda")
            again.process.toHandle().destroy()
            val journal = again.journal()

            val ops = Log(office.project.resolve("mail")).lines.filterIsInstance<kp.model.Op>().filter { it.op == "worktree.create" }
            ops.map { listOfNotNull(it.phase, it.reason).joinToString(" ") } shouldBe listOf("requested", "failed interrupted")
            journal shouldContain "worktree.create for ${approval.id} was interrupted: ${office.worktrees.resolve(OFFICE_WORKTREE)} is a worktree on the branch $OFFICE_BRANCH"
            journal shouldContain "nothing touches it"
            office.scratch.resolve(OFFICE_WORKTREE).exists() shouldBe false
        } finally {
            hold.deleteIfExists()
        }
    }
})

/** A `rules` record, as the writer's every line here: about no stage, in the office's file of November 2026. */
private val RULES_RECORD = Json.parseToJsonElement("""{"v":1,"at":"2026-11-22T09:00:00Z","event":"rules","hash":"h","files":{}}""").jsonObject

/** The machine's `git`, by its path. */
private fun realGit(): String = System.getenv("PATH").split(':').map { java.nio.file.Path.of(it, "git") }.first { Files.isExecutable(it) }.toString()

/** Waits until [file] exists, at most a minute. */
private fun awaitFile(file: java.nio.file.Path) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (!file.exists()) {
        check(System.nanoTime() < end) { "$file did not appear in a minute" }
        Thread.sleep(100)
    }
}
