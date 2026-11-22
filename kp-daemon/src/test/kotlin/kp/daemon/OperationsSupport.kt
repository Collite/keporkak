package kp.daemon

import kp.engine.FactRef
import kp.facts.ProjectFacts
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Op
import kp.ops.FakeApps
import kp.ops.GitCommand
import kp.ops.Remote
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/** The stage of S3.3's daemon specs, its list's branch, and the name of its worktree and scratch folder. */
const val OFFICE_STAGE = "KP/P3/S3.3"
const val OFFICE_BRANCH = "feat/kp-keporkak-p3-s3.3-operations-on-the-office"
const val OFFICE_WORKTREE = "kp-p3-s3.3"

/**
 * An office on disk for S3.3's daemon specs, nothing of it on GitHub: two remotes the spec builds — the project's,
 * `project.git`, and keporkak's — the project checkout and keporkak's main checkout cloned from them, keporkak's remote
 * moved since; the office's roots; and master's rules but for `pool/offices.yaml`, which names all of them, its efforts
 * in the order TOY, KP, and a secrets folder where the fake GitHub's Apps ([apps]) keep their keys. The stages of
 * [states] are on the log in their state; with [worktree], the stage's worktree is there, clean, on its branch. Not
 * [performs], it performs nothing: each request stays in flight.
 *
 * Its performers are the service's ([OfficeOperations]), its world the project's and git's ([ProjectWorld]); its `git`
 * is traced: each command the spec saw start, with its arguments — git's own children among them, as `GIT_TRACE`
 * writes them — is in [started].
 */
class OfficeOnDisk(states: Map<String, String> = emptyMap(), worktree: Boolean = false, performs: Boolean = true) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "office-")
    val projectRemote = Remote("project.git", mapOf(
        ".gitignore" to "mail/.writer.lock\n*.log\n",
        "plan.md" to PLAN,
        "tasks-overview.md" to "- [ ] **P3 · Eyes and hands**\n  - [ ] **S3.3** Operations on the office\n",
        "tasks-p3-s3.3-operations-on-the-office.md" to LIST,
        "STATUS.md" to status("KP — Keporkak", "2026-11-22") + "- **2026-11-22 — S3.3 merged.** #60\n",
        "toy/STATUS.md" to status("TOY — the toy project", "2026-11-14"),
    ))
    val project: Path = projectRemote.clone("project").also { seed(it, states) }
    val keporkakRemote = Remote("keporkak.git", mapOf("README.md" to "keporkak\n"))
    val keporkak: Path = keporkakRemote.clone("keporkak").also { keporkakRemote.moves(mapOf("CHANGELOG.md" to "moved after the clone\n")) }
    val worktrees: Path = folder.resolve("worktrees").createDirectories()
    val scratch: Path = folder.resolve("scratch").createDirectories()
    val secrets: Path = folder.resolve("secrets").createDirectories()
    val apps = FakeApps.start(secrets.resolve("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    val root: Path = rulesFor(this)
    private val traced = folder.resolve("git.trace")
    private val argv = folder.resolve("git.argv")
    private val git = GitCommand(tracer(folder, argv).toString(), mapOf("GIT_TRACE" to traced.toString()))
    val journal = CopyOnWriteArrayList<String>()
    val rules: InForceRules = InForceRules.of(RuleFiles(root).read()).first!!
    val world = ProjectWorld.of(rules, "panda", project) { journal += it }
    val operations = OfficeOperations(root, "panda", project, git, apps.api) { journal += it }
    val office: Office = Office.start("panda", project, root, FactSource { fact, stage -> if (fact.name in ProjectFacts.ANSWERED) world.observe(fact, stage) else null },
        if (performs) operations else Operations { _, _ -> }, NoSeats, Clock { Instant.parse("2026-11-22T09:00:00Z") }) { office ->
        world.reading(office)
        operations.reading(office)
    }

    init {
        if (worktree) git(keporkak, "worktree", "add", "--quiet", "-b", OFFICE_BRANCH, worktrees.resolve(OFFICE_WORKTREE).toString(), "origin/master")
        if (worktree) scratch.resolve(OFFICE_WORKTREE).createDirectories()
    }

    /** Each command the spec saw start: the traced `git`'s arguments, and every line `GIT_TRACE` wrote. */
    val started: List<String> get() = listOf(argv, traced).filter { it.isRegularFile() }.flatMap { it.readText().lines() }.filter { it.isNotBlank() }

    fun send(seat: String, input: String): Answer = office.submit(Sender.Seat(seat), json(input)).answered().also { office.settle() }

    fun bora(input: String): Answer = office.submit(Sender.Bora(signed = true), json(input)).answered().also { office.settle() }

    /** The operations [cause] requested, each as `<op> <phase>` and its reason when it failed. */
    fun ops(cause: String): List<String> = office.lines().map { it.entry }.filterIsInstance<Op>().filter { it.cause == cause }
        .map { listOfNotNull(it.op, it.phase, it.reason).joinToString(" ") }

    /** [fact] about the stage, as a judgement asks it. */
    fun fact(fact: String) = world.observe(FactRef(fact), OFFICE_STAGE)

    /** The text of every file of the daemon's log. */
    fun logText(): String = project.resolve("mail").walk().filter { it.isRegularFile() }.joinToString("\n") { it.readText() }

    /** The project's remote refuses every push from now on, as a protected branch would. */
    fun refusePushes() {
        projectRemote.dir.resolve("hooks/pre-receive").apply { writeText("#!/bin/sh\necho 'the branch is protected' >&2\nexit 1\n") }.toFile().setExecutable(true)
    }

    override fun close() {
        office.close()
        apps.close()
        Judgements.check(project)
    }

    companion object {
        /** A `git` that writes its arguments to [argv], then runs the machine's `git` with them. */
        fun tracer(folder: Path, argv: Path): Path = folder.resolve("traced-git").apply {
            writeText("#!/bin/sh\nprintf 'git %s\\n' \"\$*\" >> '$argv'\nexec git \"\$@\"\n")
            toFile().setExecutable(true)
        }

        /** Master's rules but for `pool/offices.yaml`, which names [office]'s checkouts, roots and secrets; with the profiles and the register's script. */
        fun rulesFor(office: OfficeOnDisk): Path = TestOffice.copyOfTheRules().also { root ->
            val offices = root.resolve("pool/offices.yaml")
            offices.writeText(offices.readText()
                .replace(Regex("(?m)^      worktrees: .*$"), "      worktrees: \"${office.worktrees}\"")
                .replace(Regex("(?m)^      scratch: .*$"), "      scratch: \"${office.scratch}\"")
                .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"${office.secrets}\"")
                .replace(Regex("(?m)^    project: .*$"), "    project: {checkout: \"${office.project}\", efforts: {TOY: toy, KP: \".\"}}")
                .replace(Regex("(?m)^    repos: .*$"), "    repos: {keporkak: \"${office.keporkak}\"}"))
            checkout.resolve("docs/profiles").listDirectoryEntries("*.md").forEach { it.copyTo(root.resolve("docs/profiles").createDirectories().resolve(it.name)) }
            checkout.resolve("scripts/build-register.py").takeIf { it.isRegularFile() }?.copyTo(root.resolve("scripts").createDirectories().resolve("build-register.py"))
        }

        private fun status(effort: String, updated: String) = """
            ---
            effort: $effort
            repo_home: project
            code_home: [keporkak]
            state: "in flight"
            phase: "P3"
            next: "nobody"
            blocked_on: []
            gates: []
            updated: $updated
            stream: dev
            lane: coding
            ---

        """.trimIndent() + "\n"

        private val PLAN = """
            ## 2 · Stages

            | stage | after | goal |
            |---|---|---|
            | **S3.3 Operations on the office** | — | hands |
        """.trimIndent() + "\n"

        /** S3.3's list as the plan cuts one: its first line names its branch and its repository; nothing rolls, nothing is live, no Found line is open. */
        val LIST = """
            # KP-keporkak-P3 · S3.3 — Operations on the office            (PR: $OFFICE_BRANCH)
            rolls: none  ·  live: none  ·  touches: none

            ## Scope fence
            - Stop conditions: SC-1 another repo
            ## Tasks
            - [x] T1 — Specs
                  done abc1234
            ## DONE (observable, all required)
            - the operations are performed
            ## Found while doing
            -
        """.trimIndent() + "\n"
    }
}
