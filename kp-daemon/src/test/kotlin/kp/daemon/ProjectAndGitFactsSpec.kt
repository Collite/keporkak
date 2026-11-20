package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.mailroom.Status
import kp.model.Judged
import kp.model.Refused
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.1 — the daemon asks `kp-facts` (T1's fifth sentence; T7). The office runs on a copy of master's rules whose
 * `pool/offices.yaml` names a project checkout and a main checkout the spec builds with the real `git`; its world is the
 * service's — [ProjectWorld] over the office's `project` and `repos` and the fold. Its seams are the daemon's own
 * entries: a judgement through [Office], and the `kp inbox` view.
 */
class ProjectAndGitFactsSpec : FreeSpec({
    "asked by a judgement and by `kp inbox`, the daemon answers a stage's three lines from a real checkout; a fact `kp-facts` does not hold stays unknown, and the judgement is written whole" {
        FactsOffice().use { office ->
            office.send("teamlead", """{"kind": "ruling-request", "stage": "KP/P3/S3.1", "subject": "a ruling", "args": {"default": "go on", "citation": "D-94"}}""")
            office.kp("inbox") shouldContain "   rolls: none · live: Panda · touches: kp-facts/\n"

            office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "subject": "verify T1", "args": {"task": "T1", "done": "D1", "verify": "the facts"}}""")
            office.observedOf("qa-request") shouldBe mapOf("list.task_exists(id=T1)" to "true", "list.done_item_exists(id=D1)" to "true", "stage.live" to "\"Panda\"")

            office.send("teamlead", proposal("KP/P3/S3.1")).status shouldBe Status.REFUSED
            val refused = office.recordOf<Refused>("assignment-proposal")
            refused.failed.toString() shouldContain "pool.seat_available"
            office.observedOf("assignment-proposal").filterKeys { it.startsWith("plan.") || it.startsWith("list.") || it.startsWith("pool.") } shouldBe mapOf(
                "plan.predecessors_exited" to "true", "pool.seat_available" to UNKNOWN, "list.fence_complete" to "true", "list.tasks_total" to "2", "list.lines" to "12",
            )
            office.endsWithAJudgedLine() shouldBe true
        }
    }

    "with no `git` on the path, a judgement that reads `git.branch_exists` is written whole — refused, the fact unknown — and nothing is thrown" {
        FactsOffice(path = "").use { office ->
            office.send("coder-1", """{"kind": "started", "stage": "KP/P3/S3.1", "subject": "started"}""").status shouldBe Status.REFUSED

            val refused = office.recordOf<Refused>("started")
            refused.failed.toString() shouldContain "git.branch_exists"
            office.observedOf("started") shouldBe mapOf("git.branch_exists" to UNKNOWN)
            office.journal.joinToString("\n") shouldContain "git.branch_exists"
            office.journal.joinToString("\n") shouldContain "no `git` on the path"
            office.endsWithAJudgedLine() shouldBe true
        }
        FactsOffice().use { office ->
            office.send("coder-1", """{"kind": "started", "stage": "KP/P3/S3.1", "subject": "started"}""")
            office.observedOf("started") shouldBe mapOf("git.branch_exists" to "true")
        }
    }

    "at start the daemon says which project checkout and which main checkouts it reads" {
        FactsOffice().use { office ->
            office.world.reads shouldContain "project checkout ${office.project}"
            office.world.reads shouldContain "keporkak ${office.keporkak}"
            office.world.reads shouldContain "kp-toy ${office.toy}"
        }
    }
})

/**
 * An office on a project checkout and two main checkouts the spec builds, with master's rules but for `pool/offices.yaml`,
 * which names them; its world is the service's, [ProjectWorld], with `git` looked up on [path].
 */
private class FactsOffice(path: String? = System.getenv("PATH")) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "facts-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, "plan.md", "| stage | after | goal |\n|---|---|---|\n| **S3.1 Facts** | — | the facts |\n")
        write(project, "tasks-overview.md", "- [ ] **P3**\n")
        write(project, "tasks-p3-s3.1-facts.md", LIST)
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "cut S3.1")
    }
    val keporkak: Path = repository(folder.resolve("keporkak")).also { main ->
        write(main, "README.md", "keporkak\n")
        git(main, "add", "-A")
        git(main, "commit", "--quiet", "-m", "one")
        git(main, "branch", "feat/kp-keporkak-p3-s3.1-project-and-git-facts")
    }
    val toy: Path = repository(folder.resolve("kp-toy"))
    private val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\",")
            .replace(Regex("(?m)^    repos: .*$"), "    repos: {keporkak: \"$keporkak\", kp-toy: \"$toy\"}"))
    }
    val journal = java.util.concurrent.CopyOnWriteArrayList<String>()
    val world = ProjectWorld.of(InForceRules.of(RuleFiles(root).read()).first!!, "panda", project, path) { journal += it }
    val office: Office = Office.start("panda", project, root, world, Operations { _, done -> done(OperationResult.Ok()) }, NoSeats, Clock { Instant.parse("2026-11-20T09:00:00Z") })
        .also(world::reading)
    private val commands = KpCommands(office, world)

    fun send(seat: String, input: String): Answer = office.submit(Sender.Seat(seat), json(input)).answered()

    fun kp(vararg args: String): String {
        val lines = ArrayList<String>()
        commands.run(Request(args.first(), args.drop(1), tty = false), Peer("bora")) { lines += it }
        return lines.joinToString("") { "$it\n" }
    }

    /** The record of what became of the last message of [kind]. */
    inline fun <reified T> recordOf(kind: String): T {
        val lines = office.lines().map { it.entry }
        val id = lines.filterIsInstance<kp.model.Message>().last { it.kind == kind }.id.value
        return lines.filterIsInstance<T>().single { (it as? Refused)?.id == id || (it as? kp.model.Delivered)?.id == id }
    }

    /** The facts the judgement of the last message of [kind] observed, each with its value as JSON. */
    fun observedOf(kind: String): Map<String, String> {
        val lines = office.lines().map { it.entry }
        val id = lines.filterIsInstance<kp.model.Message>().last { it.kind == kind }.id.value
        val observed = lines.firstNotNullOf { line -> ((line as? kp.model.Delivered)?.takeIf { it.id == id }?.observed ?: (line as? Refused)?.takeIf { it.id == id }?.observed) }
        return observed.mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    fun endsWithAJudgedLine(): Boolean = office.lines().last().entry is Judged && Judgements.outsideAJudgement(office.lines().map { it.entry }).isEmpty()

    override fun close() {
        office.close()
        Judgements.check(project)
    }
}

/** How the mailroom records, in a judgement's `observed`, a fact the world did not supply. */
private const val UNKNOWN = """{"unknown":"not supplied"}"""

/** A stage list of KP's P3, in the shape of the plan's lists: its first line names its branch and its repository. */
private val LIST = """
    # KP-keporkak-P3 · S3.1 — Facts from the project and git            (PR: feat/kp-keporkak-p3-s3.1-project-and-git-facts)
    rolls: none  ·  live: Panda  ·  touches: kp-facts/

    ## Scope fence
    - Stop conditions: SC-1 another repo · SC-2 contracts.md
    ## Tasks
    - [ ] T1 — Specs
    - [ ] T2 — The shape
    ## DONE (observable, all required)
    - the facts are read
    ## Found while doing
    -
""".trimIndent() + "\n"

private fun repository(dir: Path): Path = dir.createDirectories().also { git(it, "init", "--quiet", "--initial-branch=main") }

private fun write(dir: Path, path: String, text: String) {
    dir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
}

/** The spec's own `git`, reading no user or system configuration. */
private fun git(dir: Path, vararg args: String) {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).apply {
        environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_AUTHOR_NAME" to "Fixture", "GIT_AUTHOR_EMAIL" to "fixture@example.invalid",
                "GIT_COMMITTER_NAME" to "Fixture", "GIT_COMMITTER_EMAIL" to "fixture@example.invalid", "GIT_AUTHOR_DATE" to "2026-11-19T08:00:00Z", "GIT_COMMITTER_DATE" to "2026-11-19T08:00:00Z"))
        }
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
}
