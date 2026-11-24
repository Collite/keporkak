package kp.daemon

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.FactRef
import kp.facts.RecordedGitHub
import kp.facts.TOY_BRANCH
import kp.facts.TOY_STAGE
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.ops.FakeApp
import kp.ops.FakeApps
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/** The four Apps as Bora created them (`bora-github-apps.md` §6), each reaching the three repositories, holding what §9 gives its roles. */
val THE_APPS = listOf(
    FakeApp("kp-merger", 5849229, 184045472, "kp-merger", mapOf("contents" to "write", "pull_requests" to "write", "checks" to "read", "issues" to "write"), REPOSITORIES),
    FakeApp("kp-coder", 5849303, 184046595, "collite-kp-coder", mapOf("contents" to "write", "pull_requests" to "write"), REPOSITORIES),
    FakeApp("kp-reviewer", 5849353, 184047637, "kp-reviewer", mapOf("contents" to "read", "pull_requests" to "write", "checks" to "read"), REPOSITORIES),
    FakeApp("kp-gardener", 5849393, 184048423, "kp-gardener", mapOf("issues" to "write", "pull_requests" to "read", "contents" to "read"), REPOSITORIES),
)
private val REPOSITORIES get() = listOf("keporkak", "kp-toy", "project")

/**
 * An office reading GitHub (S3.2): a project checkout the spec builds with the real `git`, holding the toy's stage
 * `TOY/P0/S0.1` — its list names `kp-toy` and the branch of the toy's pull request #1, its one task is done — in [states];
 * master's rules but for `pool/offices.yaml`, which names that checkout and a secrets folder of the spec's. GitHub is one
 * local server: the responses recorded from `Collite/kp-toy` ([github]) and the App endpoints ([apps]), where the four
 * Apps are made with keys of the spec's, PKCS#1, in `<secrets>/apps`. With [up] false the daemon asks a port nothing
 * listens on. With [made], the log holds the stage's `worktree.create`, ended `ok` on that branch in `kp-toy` (S3.11).
 * Its world is the service's ([ServiceWorld]), and its journal is kept.
 */
class GitHubOffice(states: Map<String, String> = mapOf(TOY_STAGE to "building"), up: Boolean = true, made: Boolean = true) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "github-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, "toy/tasks-p0-s0.1-skeleton.md", LIST)
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "cut S0.1")
        seed(project, states)
        // The stage's worktree was made on the list's branch in kp-toy: what its repository and branch are read from (S3.11).
        if (made) placed(project, TOY_STAGE, "kp-toy", TOY_BRANCH)
    }
    val secrets: Path = folder.resolve("secrets").createDirectories()
    private val server = RecordedGitHub.local()
    val github = RecordedGitHub(server)
    val apps = FakeApps(server, secrets.resolve("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    val api: URI = if (up) github.api else URI.create("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/")
    val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\",")
            .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"$secrets\""))
    }
    val journal = CopyOnWriteArrayList<String>()
    // Rules that do not load stop the office here with their problems, each naming its line (S3.12).
    private val rules = InForceRules.of(RuleFiles(root).read()).let { (rules, load) -> rules ?: error("the rules do not load: ${load.problems.joinToString("; ")}") }
    val world = ServiceWorld(ProjectWorld.of(rules, "panda", project) { journal += it }, GitHubWorld.of("panda", project, api) { journal += it })
    val office: Office = Office.start("panda", project, root, world, Operations { _, done -> done(OperationResult.Ok()) }, NoSeats, Clock { Instant.parse("2026-11-21T09:00:00Z") })
        .also(world::reading)

    init {
        server.start()
    }

    fun send(seat: String, input: String): Answer = office.submit(Sender.Seat(seat), json(input)).answered()

    /** [fact] with [params], as a judgement about the toy's stage asks it. */
    fun observe(fact: String, vararg params: Pair<String, kp.engine.Datum>) = world.observe(FactRef(fact, params.toMap()), TOY_STAGE)

    /** The facts the judgement of the last message of [kind] observed, each with its value as JSON. */
    fun observedOf(kind: String): Map<String, String> {
        val lines = office.lines().map { it.entry }
        val id = lines.filterIsInstance<kp.model.Message>().last { it.kind == kind }.id.value
        val judged = lines.filterIsInstance<kp.model.Record>().first { it.json["id"]?.jsonPrimitive?.content == id && it.json["event"]?.jsonPrimitive?.content in setOf("delivered", "refused") }
        return judged.json["observed"]?.jsonObject.orEmpty().mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    fun endsWithAJudgedLine(): Boolean = office.lines().last().entry is kp.model.Judged && Judgements.outsideAJudgement(office.lines().map { it.entry }).isEmpty()

    /** The text of every file of the daemon's log. */
    fun logText(): String = project.resolve("mail").walk().filter { it.isRegularFile() }.joinToString("\n") { it.readText() }

    override fun close() {
        office.close()
        server.stop(0)
        Judgements.check(project)
    }

    companion object {
        /** The toy's list of S0.1, as its first line is: the repository `kp-toy`, the branch of pull request #1. */
        val LIST = """
            # TOY-kp-toy-P0 · S0.1 — The skeleton            (PR: $TOY_BRANCH)
            rolls: none  ·  live: none  ·  touches: none

            ## Scope fence
            - Stop conditions: SC-1 another repo
            ## Tasks
            - [x] T1 — The skeleton
                  done 028e9e2
            ## DONE (observable, all required)
            - the skeleton builds
            ## Found while doing
            -
        """.trimIndent() + "\n"
    }
}

/** What a spec's run printed — standard output and standard error — while [run] ran; both are put back after. */
fun printedDuring(run: () -> Unit): String {
    val out = System.out
    val err = System.err
    val printed = ByteArrayOutputStream()
    val both = PrintStream(printed, true)
    System.setOut(PrintStream(TeeStream(out, both), true))
    System.setErr(PrintStream(TeeStream(err, both), true))
    try {
        run()
    } finally {
        System.setOut(out)
        System.setErr(err)
    }
    return printed.toString()
}

private class TeeStream(private val first: java.io.OutputStream, private val second: java.io.OutputStream) : java.io.OutputStream() {
    override fun write(b: Int) {
        first.write(b)
        second.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        first.write(b, off, len)
        second.write(b, off, len)
    }
}
