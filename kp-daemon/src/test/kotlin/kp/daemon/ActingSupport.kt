package kp.daemon

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.facts.RecordedGitHub
import kp.facts.TOY_STAGE
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Message
import kp.model.Op
import kp.model.Record
import kp.ops.FakeApps
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * An office that acts on GitHub (S3.9): a project checkout the spec builds with the real `git`, holding the toy's stage
 * `TOY/P0/S0.1` — its list names `kp-toy` and the branch of the toy's pull request #1, its one task done — in [states];
 * master's rules but for `pool/offices.yaml`, which names that checkout and a secrets folder of the spec's: since D-108 a
 * `merge` reads the reviewer's review and the checks on `pr.head`, so its judgement observes a head. GitHub is one local server: the recorded answers ([github]) and the App
 * endpoints ([apps]), the four Apps made with keys of the spec's. Its operations are the service's on GitHub
 * ([GitHubOperations]) before any other, which ends `ok`; the facts [fromGitHub] names are read from GitHub as the service
 * reads them ([GitHubWorld]), every other from the table of `h1-prime.yaml`'s walk with this stage's list ticked and its
 * Found lines triaged. [restart] is a kill: the office ends where it stands and a new one starts on its log.
 */
class ActingOffice(
    states: Map<String, String>,
    private val fromGitHub: Set<String> = emptySet(),
    table: Map<String, Datum> = emptyMap(),
) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "acting-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, "toy/tasks-p0-s0.1-skeleton.md", GitHubOffice.LIST)
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "cut S0.1")
        seed(project, states)
        // The stage's worktree was made on the list's branch in kp-toy: where it acts on GitHub is read from that (S3.11).
        placed(project, TOY_STAGE, "kp-toy", kp.facts.TOY_BRANCH)
    }
    private val secrets: Path = folder.resolve("secrets").createDirectories()
    private val server = RecordedGitHub.local()
    val github = RecordedGitHub(server)
    val apps = FakeApps(server, secrets.resolve("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    private val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\",")
            .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"$secrets\""))
    }
    val journal = CopyOnWriteArrayList<String>()
    private val walk = TableFacts(Walk.read(checkout.resolve("rules/walks/h1-prime.yaml")).facts).also { walk ->
        walk.set(mapOf("list.tasks_open" to Datum.of(0), "list.found_untriaged" to Datum.of(0)) + table)
    }
    private val world = GitHubWorld.of("panda", project, github.api) { journal += it }
    private val facts = FactSource { fact, stage -> if (fact.name in fromGitHub) world.observe(fact, stage) else walk.observe(fact, stage) }
    private var operations = operations()
    var office: Office = start()
        private set

    init {
        server.start()
    }

    private fun operations() = GitHubOperations.of("panda", Operations { _, done -> done(OperationResult.Ok()) }, github.api) { journal += it }

    private fun start(): Office = Office.start("panda", project, root, facts, operations, NoSeats, Clock { Instant.parse("2026-11-22T09:00:00Z") }) {
        world.reading(it)
        operations.reading(it)
    }

    /** A kill: the office ends with what is in flight unanswered, and a new daemon starts on its log. */
    fun restart() {
        office.close()
        operations = operations()
        office = start()
    }

    fun send(seat: String, input: String): Answer = office.submit(Sender.Seat(seat), json(input)).answered()

    /** The id of the last message of [kind] on the log. */
    fun idOf(kind: String): String = office.counted().filterIsInstance<Message>().last { it.kind == kind }.id.value

    /** The facts the judgement of the last message of [kind] observed, each with its value as JSON. */
    fun observedOf(kind: String): Map<String, String> {
        val id = idOf(kind)
        val judged = office.counted().filterIsInstance<Record>().first { it.json["id"]?.jsonPrimitive?.content == id && it.json["event"]?.jsonPrimitive?.content in setOf("delivered", "refused") }
        return judged.json["observed"]?.jsonObject.orEmpty().mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    /** The `op` records of [op] on the log, each `<phase> <result or reason>`. */
    fun ops(op: String): List<String> = office.counted().filterIsInstance<Op>().filter { it.op == op }.map { "${it.phase} ${it.result ?: it.reason}" }

    /** Waits, at most 30 s, until the log holds [op]'s result. */
    fun awaitResult(op: String) {
        val end = System.nanoTime() + 30_000_000_000
        var version = office.changes.version()
        while (office.counted().filterIsInstance<Op>().none { it.op == op && it.phase != "requested" }) {
            if (System.nanoTime() > end) throw AssertionError("waited 30s for the result of $op; its records: ${ops(op)}")
            version = office.changes.await(version, 100)
        }
    }

    /** The stage's state on the log. */
    fun state(): String? = office.state.machine("stage", TOY_STAGE)?.state

    /** The text of every file of the daemon's log. */
    fun logText(): String = project.resolve("mail").walk().filter { it.isRegularFile() }.joinToString("\n") { it.readText() }

    override fun close() {
        office.close()
        server.stop(0)
        Judgements.check(project)
    }
}

/** A `merge` of the toy's stage, from the TeamLead. */
const val MERGE_THE_TOY = """{"kind": "merge", "stage": "$TOY_STAGE", "subject": "merge S0.1"}"""
