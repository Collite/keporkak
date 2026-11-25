package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Envelope
import kp.model.Message
import kp.model.Record
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.8 — the daemon's part of the repairs (T1's second and third sentences; T3; Amendment 34's two corrections,
 * D-98). The seams are the daemon's own entries: a start through `main` — the launcher the unit starts — on a project whose
 * log ends inside a judgement, `kp inbox` through the real `kp`, and the journal the daemon writes. The first time a message
 * is judged, an office in this process carries it with the service's world, [ProjectWorld], on the same project and
 * rules; the log is then cut after the message's line, as a stop there leaves it, and the daemon is started on it.
 */
class AfterTheFirstWaveSpec : FreeSpec({
    "a daemon started on a log whose last judgement must be carried again answers that judgement's facts of the log from the fold: with one stage in the log, with two, and with none the fact is what the project says, never \"has not folded\"" - {
        "one stage in the log — S3.0 exited there; S3.1 ticked in the overview" {
            val restart = Restarted(log = mapOf("KP/P3/S3.0" to "exited"), ticked = listOf("S3.1"))

            restart.first shouldBe mapOf("plan.predecessors_exited" to "true")
            restart.again shouldBe restart.first
            restart.outcomeAgain shouldBe restart.outcomeFirst
            restart.journal shouldNotContain "has not folded"
        }

        "two stages in the log — S3.0 exited, S3.1 building: the log's word, though the overview ticks S3.1" {
            val restart = Restarted(log = mapOf("KP/P3/S3.0" to "exited", "KP/P3/S3.1" to "building"), ticked = listOf("S3.1"))

            restart.first shouldBe mapOf("plan.predecessors_exited" to "false")
            restart.again shouldBe restart.first
            restart.outcomeAgain shouldBe restart.outcomeFirst
            restart.journal shouldNotContain "has not folded"
        }

        "no stage in the log — S3.0 and S3.1 ticked in the overview" {
            val restart = Restarted(log = emptyMap(), ticked = listOf("S3.0", "S3.1"))

            restart.first shouldBe mapOf("plan.predecessors_exited" to "true")
            restart.again shouldBe restart.first
            restart.outcomeAgain shouldBe restart.outcomeFirst
            restart.journal shouldNotContain "has not folded"
        }
    }

    "a daemon started on a log in which Bora is absent judges its first carried message with him absent: held for the Deputy, as the first time (S3.10 T6, issue #59)" {
        val restart = Restarted(log = mapOf("KP/P3/S3.0" to "exited"), ticked = listOf("S3.1"), away = true)

        restart.outcomeFirst shouldStartWith "held by "
        restart.outcomeAgain shouldBe restart.outcomeFirst
    }

    "a view's ask of an unknown fact writes no journal line, however often it is asked; a judgement's ask writes one each time" {
        // A ruling waits for Bora on S3.8, whose list has no `rolls:` line; a QA request on S3.8 — its judgement reads
        // `stage.live` — is the judgement the log ends in: the daemon started on it carries it again.
        val project = Project(log = emptyMap(), ticked = listOf("S3.0", "S3.1"), rolls = false)
        val firstTime = project.carry(
            "teamlead" to """{"kind": "ruling-request", "stage": "KP/P3/S3.8", "subject": "a ruling", "args": {"default": "go on", "citation": "D-98"}}""",
            "coder-1" to """{"kind": "qa-request", "stage": "KP/P3/S3.8", "subject": "verify T1", "args": {"task": "T1", "done": "D1", "verify": "the facts"}}""",
        )
        project.cutAfter("qa-request")
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        val daemon = Launched(project.dir, socket, project.root)
        daemon.awaitLine("started: office panda")

        val inboxes = List(3) { kp(socket, "inbox").text }
        daemon.process.toHandle().destroy()
        val journal = daemon.journal()

        inboxes.forEach { it shouldNotContain "deadline passed" }
        inboxes.map { it.lines().count { line -> line == "   rolls: unknown · live: unknown · touches: unknown" } } shouldBe listOf(1, 1, 1)
        firstTime.unknown() shouldContainExactly listOf("facts: stage.live for KP/P3/S3.8 is unknown — tasks-p3-s3.8-after-the-first-wave.md has no line that begins 'rolls:'")
        journal.unknown() shouldContainExactly listOf("facts: stage.live for KP/P3/S3.8 is unknown — tasks-p3-s3.8-after-the-first-wave.md has no line that begins 'rolls:'")
    }
})

/** The lines of a journal that say a fact is unknown — as the daemon wrote them, after its time and its name. */
private fun Iterable<String>.unknown(): List<String> = filter { " is unknown — " in it }.map { it.substringAfter(" kp-daemon: ") }

private fun String.unknown(): List<String> = lines().unknown()

/**
 * A project of KP's P3 with three stages, a git checkout of its own: S3.0 and S3.1 side by side, S3.8 after both. The
 * stages of [log] are in their state on its log before anything is carried (as a rehearsal seeds a walk's); those of
 * [ticked] are ticked in its overview. S3.8's list has a `rolls:` line unless [rolls] is false. It runs on [root], a copy
 * of master's rules whose `pool/offices.yaml` names it as the office's project checkout and two main checkouts of its
 * own, each with one commit — so no fact reads a checkout of the machine the spec runs on.
 */
private class Project(log: Map<String, String>, ticked: List<String>, rolls: Boolean = true) {
    val dir: Path = repository(Files.createTempDirectory(checkout.resolve("build").createDirectories(), "restart-")).also { dir ->
        dir.resolve("plan.md").writeText(PLAN)
        dir.resolve("tasks-overview.md").writeText("- [ ] **P3 · Eyes and hands**\n" + ticked.joinToString("") { "  - [x] $it\n" })
        dir.resolve("tasks-p3-s3.8-after-the-first-wave.md").writeText(if (rolls) LIST else LIST.lines().filterNot { it.startsWith("rolls:") }.joinToString("\n"))
        git(dir, "add", "-A")
        git(dir, "commit", "--quiet", "-m", "cut S3.8")
        seed(dir, log)
    }
    private val mains: Map<String, Path> = listOf("keporkak", "kp-toy").associateWith { name ->
        repository(dir.resolveSibling("${dir.fileName}-$name")).also { main ->
            main.resolve("README.md").writeText("$name\n")
            git(main, "add", "-A")
            git(main, "commit", "--quiet", "-m", "one")
        }
    }
    val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$dir\",")
            .replace(Regex("(?m)^    repos: .*$"), "    repos: {" + mains.entries.joinToString(", ") { (name, path) -> "$name: \"$path\"" } + "}"))
    }

    /**
     * Carries each message, from its seat, through an office in this process whose world is the service's on this
     * project and [root]'s rules; what its journal said. Its clock is the service's, as the daemon started later on this
     * log keeps: a deadline the rules set here has not passed when the daemon starts, whatever day the spec runs (review-029 B1).
     */
    fun carry(vararg messages: Pair<String, String>): List<String> {
        val journal = CopyOnWriteArrayList<String>()
        val project = ProjectWorld.of(InForceRules.of(RuleFiles(root).read()).first!!, "panda", dir) { journal += it }
        // The pool's facts beside the project's, as the service answers them (S3.13): the daemon started later has both.
        val pool = kp.daemon.seats.PoolFacts("panda") { journal += it }
        val world = kp.mailroom.FactSource { fact, stage -> if (fact.name in kp.daemon.seats.PoolFacts.ANSWERED) pool.observe(fact, stage) else project.observe(fact, stage) }
        Office.start("panda", dir, root, world, Operations { _, done -> done(OperationResult.Ok()) }, NoSeats, serviceClock)
            .also { office -> project.reading(office); pool.reading(office) }
            .use { office -> messages.forEach { (seat, input) -> office.submit(if (seat == BORA) Sender.Bora(signed = true) else Sender.Seat(seat), json(input)).answered() } }
        return journal
    }

    /** The log as a stop right after the line of the last message of [kind] leaves it: every later line gone. */
    fun cutAfter(kind: String) {
        val cut = lines().filterIsInstance<Message>().last { it.kind == kind }.seq.toLong()
        keepLines(dir) { it <= cut }
    }

    fun lines(): List<Envelope> = Log(dir.resolve("mail")).lines
}

/**
 * The TeamLead's proposal for S3.8 carried by an office in this process, the log cut after its line, and the daemon
 * started through its launcher on that log — which carries the proposal again — then stopped.
 */
private class Restarted(log: Map<String, String>, ticked: List<String>, away: Boolean = false) {
    private val project = Project(log, ticked)
    private val before: List<Envelope>
    private val after: List<Envelope>
    val journal: String

    init {
        // With [away], Bora has said he is away — a signed `away` — before the proposal comes (S3.10 T6).
        if (away) project.carry(BORA to """{"kind": "away", "subject": "away", "args": {}}""")
        project.carry("teamlead" to proposal("KP/P3/S3.8"))
        before = project.lines()
        project.cutAfter("assignment-proposal")
        val daemon = Launched(project.dir, Files.createTempDirectory("kp-").resolve("kp.sock"), project.root)
        daemon.awaitLine("started: office panda")
        daemon.process.toHandle().destroy()
        daemon.process.waitFor(60, TimeUnit.SECONDS)
        journal = daemon.journal()
        after = project.lines()
    }

    /** The facts of the log the proposal's judgement observed the first time, and when it was carried again. */
    val first: Map<String, String> get() = observed(before)
    val again: Map<String, String> get() = observed(after)
    /** What became of the proposal the first time and when it was carried again: its record's event, rule and failed requirement. */
    val outcomeFirst: String get() = outcome(before)
    val outcomeAgain: String get() = outcome(after)

    private fun proposalId(lines: List<Envelope>) = lines.filterIsInstance<Message>().last { it.kind == "assignment-proposal" }.id.value

    private fun record(lines: List<Envelope>): Record {
        val id = proposalId(lines)
        return lines.filterIsInstance<Record>().last { it.json["id"]?.jsonPrimitive?.content == id && it.json["event"]?.jsonPrimitive?.content in JUDGED }
    }

    private fun observed(lines: List<Envelope>): Map<String, String> = record(lines).json["observed"]?.jsonObject.orEmpty()
        .filterKeys { it.startsWith("plan.") }
        .mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }

    private fun outcome(lines: List<Envelope>): String = record(lines).json.let { "${it["event"]?.jsonPrimitive?.content} by ${it["rule"] ?: it["by"]}, failed ${it["failed"]}" }
}

/** The records that close what became of a message — or hold it (S3.10 T6). */
private val JUDGED = setOf("delivered", "refused", "held")

/** Bora at his command line, signed, as [Project.carry] names him. */
private const val BORA = "bora"

private val PLAN = """
    ## 2 · Stages

    | stage | after | goal |
    |---|---|---|
    | **S3.0 The checks** | — | checks |
    | **S3.1 Facts** | — | facts |
    | **S3.8 After the first wave** | S3.0, S3.1 | repairs |
""".trimIndent() + "\n"

private val LIST = """
    # KP-keporkak-P3 · S3.8 — After the first wave's reviews            (PR: feat/kp-keporkak-p3-s3.8-after-the-first-wave)
    rolls: none  ·  live: none  ·  touches: none

    ## Scope fence
    - Stop conditions: SC-1 another repo · SC-10 the widened check
    ## Tasks
    - [ ] T1 — Specs
    ## DONE (observable, all required)
    - the facts are read
    ## Found while doing
    -
""".trimIndent() + "\n"
