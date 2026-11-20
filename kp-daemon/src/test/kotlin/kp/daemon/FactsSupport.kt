package kp.daemon

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.mailroom.Status
import kp.model.Judged
import kp.model.Record
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The fixtures of S3.1's daemon specs (ProjectAndGitFactsSpec): an office whose world is the service's, on checkouts the
 * spec builds with the real `git`; and a refusal asserted by its rule and what failed.
 */

/**
 * A refusal of [Answer] by [rule], [failed] being the requirement that failed in its file form, as JSON — the assertion
 * S2.8's check asks of a spec (`build-support/check_refusal_assertions.py`).
 */
fun refusedBy(rule: String, failed: String): Matcher<Answer> = Matcher { answer ->
    val what = answer.failed?.json() ?: answer.requirement
    MatcherResult(
        answer.status == Status.REFUSED && answer.rule == rule && what == failed,
        { "expected a refusal by $rule, failed $failed — was ${answer.status} by ${answer.rule}, failed $what" },
        { "expected no refusal by $rule, failed $failed" },
    )
}

/** The records that close what became of a message: delivered, or not. */
private val JUDGED = setOf("delivered", "refused")

/**
 * An office on a project checkout and two main checkouts the spec builds, with master's rules but for `pool/offices.yaml`,
 * which names them; its world is the service's, [ProjectWorld], with `git` looked up on [path].
 */
class FactsOffice(path: String? = System.getenv("PATH")) : AutoCloseable {
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

    /** The facts the judgement of the last message of [kind] observed, each with its value as JSON. */
    fun observedOf(kind: String): Map<String, String> {
        val lines = office.lines().map { it.entry }
        val id = lines.filterIsInstance<kp.model.Message>().last { it.kind == kind }.id.value
        val judged = lines.filterIsInstance<Record>().first { it.json["id"]?.jsonPrimitive?.content == id && it.json["event"]?.jsonPrimitive?.content in JUDGED }
        val observed = judged.json["observed"]?.jsonObject.orEmpty()
        return observed.mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    fun endsWithAJudgedLine(): Boolean = office.lines().last().entry is Judged && Judgements.outsideAJudgement(office.lines().map { it.entry }).isEmpty()

    override fun close() {
        office.close()
        Judgements.check(project)
    }
}

/** How the mailroom records, in a judgement's `observed`, a fact the world did not supply. */
const val NOT_SUPPLIED = """{"unknown":"not supplied"}"""

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
