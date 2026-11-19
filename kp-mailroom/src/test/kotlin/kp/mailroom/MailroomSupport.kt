package kp.mailroom

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.engine.fold
import kp.log.Channels
import kp.log.LogWriter
import kp.model.Envelope
import kp.model.Expired
import kp.model.Message
import kp.model.OfficeId
import kp.model.Op
import kp.model.Record
import kp.model.Refused
import kp.model.Rules
import kp.model.Transition
import kp.model.parseLine
import kp.model.plain
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

val root = File(System.getProperty("kp.root"))
val panda = OfficeId("panda")

/** H-1′'s stage (design/11-c2-converged.md §3: "S2.1, a Python client"). */
const val STAGE = "KP/P2/S2.1"
const val START = "2026-11-16T09:00:00Z"

/** The cause a seeded line names: what an earlier, unseen part of the log did. */
const val SEEDED = "m-panda-000000"

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** The answer to a submission that is a message; a spec that submits one fails here when it is not. */
fun Offer.answered(): Answer = (this as? Offer.Answered)?.answer ?: error("the input is no message: $this")

/** An empty project checkout under the build directory; Gradle's clean removes it. */
fun throwawayProject(): Path = Files.createTempDirectory(root.resolve("build").toPath().createDirectories(), "mailroom-")

/** The rule texts on master, as the daemon hands them over; an edit replaces the first occurrence of a text in one file. */
class Texts : RuleTexts {
    private val files: MutableMap<String, String> = run {
        val rules = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary").map { File(root, "rules/$it.yaml") }
        val robots = File(root, "robots").listFiles { file -> file.name.endsWith(".yaml") }!!.sorted()
        val pool = listOf("roster", "offices", "budgets").map { File(root, "pool/$it.yaml") }
        val personas = File(root, "agents").listFiles()!!.map { File(it, "AGENT.md") }.filter { it.isFile }.sorted()
        (rules + robots + pool + personas).associate { it.relativeTo(root).path to it.readText() }.toMutableMap()
    }

    fun edit(file: String, from: String, to: String) {
        val text = files.getValue(file)
        check(from in text) { "'$from' is not in $file" }
        files[file] = text.replaceFirst(from, to)
    }

    /** The file [from] under the name [to], its text unchanged. */
    fun rename(from: String, to: String) {
        files[to] = files.remove(from) ?: error("$from is not a file of the rule set")
    }

    override fun read(): Map<String, String> = files.toMap()
}

/**
 * Facts from a table, looked up the walker's way: a plain value, or a map keyed by the parameter the fact is asked
 * with. A stage's own value, when it has one, comes before the office-wide one.
 */
class FakeFacts : FactSource {
    private val table = HashMap<String, Datum>()
    private val byStage = HashMap<String, HashMap<String, Datum>>()

    fun set(values: Map<String, Datum>) = table.putAll(values)
    fun set(name: String, value: Any?) { table[name] = Datum.of(value) }
    fun set(stage: String, name: String, value: Any?) { byStage.getOrPut(stage, ::HashMap)[name] = Datum.of(value) }

    override fun observe(fact: FactRef, stage: String?): Datum? {
        val value = stage?.let { byStage[it]?.get(fact.name) } ?: table[fact.name] ?: return null
        if (value !is Datum.Fields) return value
        val key = listOf("of", "seat", "id", "dimension", "subscription", "pointer").firstNotNullOfOrNull { fact.params[it] } ?: return null
        return value.fields[(key as? Datum.Text)?.value ?: key.json()]
    }
}

/** A performer that answers `ok` at once — or, while [holding], keeps the request and never answers. */
class FakeOperations : Operations {
    val requested = ArrayList<OperationRequest>()
    var holding = false
    val failing = HashMap<String, String>()

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
        requested += request
        if (holding) return
        done(failing[request.operation]?.let { OperationResult.Failed(it) } ?: OperationResult.Ok())
    }
}

/** Seats that are idle unless [busy]; every wake is noted. */
class FakeSeats : Deliveries {
    val busy = HashSet<String>()
    val woken = ArrayList<Pair<String, String>>()

    override fun idle(seat: String) = seat !in busy
    override fun wake(seat: String, message: Message): Boolean = (seat !in busy).also { if (it) woken += seat to message.id.value }
}

class FakeClock(var now: Instant = Instant.parse(START)) : Clock {
    fun advance(by: Duration) { now = now.plus(by.toJavaDuration()) }
    override fun now(): Instant = now
}

/**
 * A refusal, asserted by the rule that refused and by what failed — never by its status or its words alone (the retro of
 * P1, candidate 1; contracts.md §2.2, §5): the sender's [Answer] or the `refused` record. [failed] is the first requirement
 * that failed, in its file form as the rules write it in flow style (`{fact: git.branch_exists}`); where what failed is
 * held for every kind with no requirement written there is none, and [failed] is the rules' field its `requirement`
 * names (`to: teamlead`). Another rule refusing a requirement written the same way does not pass (review-018 B1).
 */
fun refusedBy(rule: String, failed: String): Matcher<Any?> = Matcher { refusal ->
    val (refused, by, what) = when (refusal) {
        is Answer -> Triple(refusal.status == Status.REFUSED, refusal.rule, refusal.failed?.flow() ?: refusal.requirement)
        is Refused -> Triple(true, refusal.rule, refusal.failed?.let { Datum.of(plain(it.toString())).flow() } ?: refusal.requirement)
        else -> Triple(false, null, "$refusal")
    }
    MatcherResult(
        refused && by == rule && what == failed,
        { "expected a refusal by $rule, failed $failed — was ${if (refused) "refused" else "not refused"} by $by, failed $what" },
        { "expected no refusal by $rule, failed $failed" },
    )
}

/** A datum as the rules write a value in flow style: `{counter: resumes, lt: {cap: resumes_per_stage}}`. */
private fun Datum.flow(): String = when (this) {
    is Datum.Fields -> fields.entries.joinToString(", ", "{", "}") { (key, value) -> "$key: ${value.flow()}" }
    is Datum.Items -> items.joinToString(", ", "[", "]") { it.flow() }
    is Datum.Text -> value
    is Datum.Number -> value.toPlainString()
    is Datum.Bool -> value.toString()
    Datum.Null -> "null"
}

/** A walk of `rules/walks/`, as the Python walker reads it (`check-rules.py --trace`), and the walker's own trace of it. */
class Walk private constructor(val definition: Map<String, Datum>, val trace: List<Map<String, Datum>>) {
    val facts: Map<String, Datum> get() = definition["facts"].fields()
    val steps: List<Map<String, Datum>> get() = (definition.getValue("steps") as Datum.Items).items.map { (it as Datum.Fields).fields }

    /** Whether the walker refused step [step] (1-based). */
    fun walkerRefused(step: Int): Boolean = (trace.single { (it.getValue("step") as Datum.Number).value.toInt() == step }["refused"] as Datum.Bool).value

    /** The stages the walker gives after step [step] (1-based), by the walk's names for them. */
    fun walkerStages(step: Int): Map<String, String> = trace.single { (it.getValue("step") as Datum.Number).value.toInt() == step }["stages"].fields()
        .mapValues { it.value.text()!! }

    companion object {
        private val read = HashMap<String, Walk>()

        fun named(name: String): Walk = read.getOrPut(name) {
            val process = ProcessBuilder(File(root, "build/python/bin/python").path, "scripts/check-rules.py", "--trace", name)
                .directory(root).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            check(lines.none { it.startsWith("FAIL walk $name") }) { lines.filter { it.startsWith("FAIL") }.joinToString("\n") }
            val walk = (Datum.of(plain(lines.single { it.startsWith("WALK ") }.removePrefix("WALK "))) as Datum.Fields).fields
            Walk(walk, lines.filter { it.startsWith("TRACE ") }.map { (Datum.of(plain(it.removePrefix("TRACE "))) as Datum.Fields).fields })
        }
    }
}

/**
 * A walk played through the mailroom with the robots host on (S1.4's DONE): each of its stages an id of its own, in
 * its initial state; its seats `ready`; its facts, per stage; each message step submitted by its sender — Bora signed
 * when the walk signs — and each operation finished by the fake performer as the walk says. After each message and
 * the operations that follow it, every stage's state as the log gives it, beside the walker's after the same steps.
 */
class WalkPlayed(name: String) {
    private val walk = Walk.named(name)
    private val names = LinkedHashMap<String, String>()
    private val settings = walk.definition["stages"].fields()
    /** Each walk stage's id: `WALK/P1/S1.<n>`, in the order the walk names them. */
    private fun id(stage: String): String = names.getOrPut(stage) { "WALK/P1/S1.${names.size + 1}" }
    private val first = walk.definition["stage"].text()!!

    init {
        id(first)
        settings.keys.forEach(::id)
        walk.steps.mapNotNull { it["stage"].text() }.forEach(::id)
    }

    private fun initial(stage: String): String = settings[stage].fields()["initial"].text() ?: walk.definition["initial"].text() ?: "planned"

    val office = Office(walk = name, others = names.filterKeys { initial(it) != "planned" }.entries.associate { (stage, id) -> id to initial(stage) },
        robots = true, seats = walk.definition["seats"].fields().keys.toList()).also { office ->
        names.forEach { (stage, id) -> office.facts.setAll(id, settings[stage].fields()["facts"].fields()) }
    }

    /** Per message step: the walk's step number after its operations, the mailroom's stages, the walker's, and what became of it. */
    val compared: List<Played> = run {
        val ids = HashMap<String, String>()
        var current = first
        val steps = walk.steps
        steps.withIndex().filter { (_, step) -> step["operation"] == null }.map { (index, step) ->
            current = step["stage"].text() ?: current
            val group = listOf(step) + steps.drop(index + 1).takeWhile { it["operation"] != null }
            group.forEach { office.facts.setAll(id(current), it["facts"].fields()) }
            group.filter { it["operation"] != null && it["result"].text() == "failed" }.forEach { office.operations.failing[it["operation"].text()!!] = "the walk's" }
            val kind = step["kind"].text()!!
            val re = step["re"].text()?.let(ids::getValue)
            val input = """{"kind":"$kind","stage":"${id(current)}","args":${(step["args"] ?: Datum.Fields(emptyMap())).json()}""" +
                (re?.let { ""","re":"$it"""" } ?: "") + (step["body"]?.let { ""","body":${it.json()}""" } ?: "") + "}"
            val who = step["who"].text()!!
            office.clock.advance(1.minutes)
            val answer = if (who == "bora") office.bora(input, signed = step["signed"].text() == "bora") else office.send(who, input)
            office.operations.failing.clear()
            step["id"].text()?.let { ids[it] = answer.id }
            val last = index + group.size
            val outcome = office.readBack().lines.filterIsInstance<Record>().last { it.plain()["id"] == answer.id && it.event in setOf("delivered", "refused") }
            Played(last, outcome.event == "refused", names.entries.associate { (stage, id) -> stage to (office.stage(id) ?: "planned") }, outcome,
                walk.walkerRefused(index + 1), walk.walkerStages(last))
        }
    }

    /**
     * Walk step [step] — a message and the operations after it: whether the mailroom refused the message and every
     * stage's state then, the record that says what became of it, and what the walker gives for the same steps.
     */
    data class Played(val step: Int, val refused: Boolean, val stages: Map<String, String>, val outcome: Record, val walkerRefused: Boolean, val walker: Map<String, String>) {
        val mailroom get() = refused to stages
        val python get() = walkerRefused to walker
    }
}

private fun Envelope.event(): String? = (this as? Record)?.event
private fun Envelope.long(field: String): Long = (plain().getValue(field) as java.math.BigDecimal).toLong()

/** Every fact of [values] for [stage]. */
fun FakeFacts.setAll(stage: String, values: Map<String, Datum>) = values.forEach { (name, value) -> set(stage, name, value) }

private fun Datum?.text(): String? = (this as? Datum.Text)?.value
private fun Datum?.fields(): Map<String, Datum> = (this as? Datum.Fields)?.fields.orEmpty()

/**
 * What [Office.end] compares of the state — where a restart must end again — and what it leaves out, each with its reason
 * (the retro of P1, candidate 2; S2.8 T4).
 */
val WHERE_THE_LOG_ENDS = Projection(
    "where the office's log ends (Office.end)",
    compares = setOf("machines", "counters", "delivered", "deadlines", "tickets", "operations", "unread"),
    leavesOut = mapOf(
        "seq" to "a start writes lines of its own (`voided`, `rules`): the numbers after it differ, as ids do",
        "at" to "the time of the last line, which a start's own lines move",
        "unjudged" to "a judgement left open: where a run ends, every judgement is closed",
        "opened" to "a judgement left open: where a run ends, every judgement is closed",
        "pending" to "a message not yet judged: where a run ends, every message is",
        "hops" to "counts keyed by thread ids, which a restart numbers differently; the deliveries are compared in order",
        "answered" to "message ids, which a restart numbers differently; a wait answered is no longer in `deadlines`",
        "rules" to "not compared: the start reloads the same files, and nothing here asks that it did — a Found line of S2.8",
        "held" to "not compared: a message held at the end is not asked for — a Found line of S2.8",
        "refusals" to "not compared: a seat's refusals of the day are not asked for — a Found line of S2.8",
        "sessions" to "not compared: where a seat's session window begins is not asked for — a Found line of S2.8",
        "wakes" to "not compared: where a robot's own seat was last woken is not asked for — a Found line of S2.8",
    ),
)

/**
 * One office on a throwaway project: master's rules, facts from a walk, faked operations and seats, a clock that moves
 * a minute per step. [seed] puts the stage in a state, with [counters], before the mailroom starts — what an earlier
 * part of the log would have left — [others] puts other stages in theirs, and each of the walk's seats is in `ready`.
 */
class Office(
    walk: String = "H-1-prime", seed: String? = null, counters: String = "{}", others: Map<String, String> = emptyMap(),
    /** The robots host (contracts.md §8): off, the mailroom of S1.3 only carries messages. */
    val robots: Boolean = false,
    /** The seats in `ready` before the mailroom starts. */
    seats: List<String> = listOf("coder-1", "reviewer-1"),
    /** When the office's clock starts. */
    at: String = START,
    /** Counters an other stage has moved, as its seeded transition carries them. */
    otherCounters: Map<String, String> = emptyMap(),
    /** Seats in another state than `ready`. */
    seatStates: Map<String, String> = emptyMap(),
) {
    val project: Path = throwawayProject()
    val texts = Texts()
    val facts = FakeFacts()
    val operations = FakeOperations()
    val seats = FakeSeats()
    val clock = FakeClock(Instant.parse(at))
    private val walk = Walk.named(walk)
    private val ids = HashMap<String, String>()
    private var next = 0
    /** The id of every message submitted through this office: each must have its line and a record about it. */
    val submitted = ArrayList<String>()
    lateinit var mailroom: Mailroom

    init {
        opened += this
        facts.set(this.walk.facts)
        // What an earlier, unseen part of the log left: one judgement, closed (contracts.md §3).
        LogWriter.open(project, panda).use { log ->
            val judgement = log.judgement()
            fun line(text: String) = judgement.add(json("""{"v":1,"at":"$at",""" + text.removePrefix("{")))
            seed?.let { line("""{"event":"transition","machine":"stage","subject":"$STAGE","from":"planned","to":"$it","cause":"$SEEDED","counted":$counters}""") }
            others.forEach { (stage, state) ->
                line("""{"event":"transition","machine":"stage","subject":"$stage","from":"planned","to":"$state","cause":"$SEEDED","counted":${otherCounters[stage] ?: "{}"}}""")
            }
            seats.forEach { line("""{"event":"transition","machine":"seat","subject":"$it","from":"provisioned","to":"${seatStates[it] ?: "ready"}","cause":"$SEEDED"}""") }
            if (log.last > 0) judgement.close(at)
        }
        start()
    }

    fun start() { mailroom = Mailroom.start(project, panda, texts, facts, operations, seats, clock, robots = robots) }
    fun stop() = mailroom.close()
    fun restart() { stop(); start() }

    /**
     * The daemon killed after the line [after] matches (S1.10): the mailroom is started again on a disk that, once the
     * start is done, writes that line and no line after it — the next write throws [WriterStopped], and nothing more
     * of the mailroom runs. [restart] then starts it on the real disk, as Panda's service would.
     */
    fun stopWriterAfter(after: (Envelope) -> Boolean) {
        val disk = StoppingDisk(after)
        stop()
        mailroom = Mailroom.start(project, panda, texts, facts, operations, seats, clock, robots = robots, disk = disk)
        disk.armed = true
    }

    /** As [start], on a disk that stops the writer after the line [after] matches — what the start itself writes included. */
    fun startStoppedAfter(after: (Envelope) -> Boolean) {
        mailroom = Mailroom.start(project, panda, texts, facts, operations, seats, clock, robots = robots, disk = StoppingDisk(after).also { it.armed = true })
    }

    /**
     * Where the office's log ends — what a restart must reach again: every machine's state, the counters, the kinds
     * delivered in order, the open waits, the open tickets, the operations in flight and each seat's unread mail, by
     * kind. Ids are left out: a restart's own lines number what follows them differently.
     */
    fun end(): List<String> {
        val state = fold(readBack().lines)
        val kind = { id: String -> state.delivered[id]?.kind ?: state.pending[id]?.kind ?: id }
        return state.machines.flatMap { (machine, subjects) -> subjects.map { (subject, at) -> "$machine $subject ${at.state}" } }.sorted() +
            state.counters.flatMap { (subject, counters) -> counters.map { (name, value) -> "counter $subject $name=$value" } }.sorted() +
            state.delivered.values.map { "delivered ${it.kind} from ${it.from}" } +
            state.deadlines.keys.map { "waits ${kind(it)}" }.sorted() +
            state.openTickets().values.map { "ticket ${kind(it.item)}" }.sorted() +
            state.operations.map { "in flight ${it.op}" } +
            state.unread.flatMap { (seat, ids) -> ids.map { "unread $seat ${kind(it)}" } }.sorted()
    }

    fun send(seat: String, input: String): Answer = mailroom.submit(Sender.Seat(seat), json(input)).answered().also { submitted += it.id }
    fun bora(input: String, signed: Boolean = true): Answer = mailroom.submit(Sender.Bora(signed), json(input)).answered().also { submitted += it.id }

    /** Bora runs a command that is no message (`kp status`): signed when he ran it with `sudo` (contracts.md §6). */
    fun command(signed: Boolean) = mailroom.command(signed)

    /** The world reports that [fact] changed — about [params], for [stage] — with [value]; the fact table says so from now on. */
    fun changed(fact: String, value: Any?, stage: String? = null, params: Map<String, String> = emptyMap()) {
        if (stage != null) facts.set(stage, fact, value) else facts.set(fact, value)
        mailroom.changed(FactRef(fact, params.mapValues { Datum.Text(it.value) }), Datum.of(value), stage)
    }

    /** Sets the clock to [time] (`HH:MM`) on the day it shows, or the next day when that time has passed. */
    fun at(time: String): Office = apply {
        val (hours, minutes) = time.split(':').map(String::toInt)
        var next = clock.now.atZone(java.time.ZoneOffset.UTC).withHour(hours).withMinute(minutes).withSecond(0).toInstant()
        if (next.isBefore(clock.now)) next = next.plus(java.time.Duration.ofDays(1))
        clock.now = next
    }

    /**
     * Plays the walk's next step of each kind, in order, from its sender with its args and facts — and the facts of the
     * operations that follow it, which the fake performer finishes at once. Returns the stage's state after each. A step
     * is `re` the step it answers when that one was played here — unless [answering] is false: then it answers nothing.
     */
    fun play(kinds: List<String>, answering: Boolean = true): List<String?> = kinds.map { kind ->
        val index = walk.steps.withIndex().first { (i, step) -> i >= next && step["kind"].text() == kind && step["who"].text() != "mailroom" }.index
        val step = walk.steps[index]
        next = index + 1
        walk.steps.drop(index).takeWhile { it == step || it["operation"] != null }.forEach { facts.set(it["facts"].fields()) }
        clock.advance(1.minutes)
        val re = step["re"].text()?.takeIf { answering }?.let { ids[it] }
        val input = """{"kind":"$kind","stage":"$STAGE","subject":"$kind","args":${(step["args"] ?: Datum.Fields(emptyMap())).json()}""" +
            (re?.let { ""","re":"$it"""" } ?: "") + "}"
        val who = step["who"].text()!!
        val answer = if (who == "bora") bora(input, signed = step["signed"].text() == "bora") else send(who, input)
        step["id"].text()?.let { ids[it] = answer.id }
        check(answer.status == Status.DELIVERED) { "$kind was not delivered: $answer" }
        stage()
    }

    fun play(vararg kinds: String) = play(kinds.toList())

    /** The mailroom's state after each of [kinds]. */
    fun statesAfter(kinds: List<String>): List<State> = kinds.map { play(it); mailroom.state }

    /** The id the walk's step [id] was given. */
    fun idOf(id: String): String = ids.getValue(id)

    /** A stage's state as its log gives it: every line of every file, folded. */
    fun stage(id: String = STAGE): String? = fold(readBack().lines).stage(id)

    fun readBack() = ReadBack(project)

    companion object {
        /** Every office a spec opened, so that its log is checked and its writer released when the spec ends. */
        val opened = ArrayList<Office>()

        fun closeAll() {
            opened.forEach { runCatching { it.stop() } }
            opened.clear()
        }

        /** What every spec of the mailroom ends with: its logs are whole — and its offices closed even when they are not (issue #21). */
        fun checkAndCloseAll() = try {
            everyLogIsWhole() shouldBe emptyList()
        } finally {
            closeAll()
        }

        /**
         * In the log of every office a spec opened: `seq` has no gap; no operation is requested twice for one request;
         * every submission has its message line and a record about it — none ended in an exception; and Bora's own
         * message waits for no one — no ticket and no `notify.bora` for a message `by: bora-cli`.
         */
        fun everyLogIsWhole(): List<String> = opened.flatMap { office ->
            val log = office.readBack()
            listOfNotNull("${office.project}: seq has a gap".takeUnless { log.seqRunsWithoutGap() }) + log.requestedTwice() +
                office.submitted.filterNot(log::hasLineAndRecord).map { "$it: no message line, or no record about it" } + log.borasOwnWaiting() +
                log.robotRulesNotIn(robotRuleIds) + log.outsideAJudgement()
        }

        /** The rules of the robot file [file] on master, each with whether it is marked `for: persona`. */
        fun robotRules(file: String): Map<String, Boolean> =
            (kp.rules.Rules.load(Texts().read()) as kp.rules.Loaded.Valid).ruleSet.robots.getValue(file).rules.associate { it.id to it.forPersona }

        /** Every rule id of `robots/` — executed, `for: persona`, or a queue's prose. */
        val robotRuleIds: Set<String> by lazy {
            File(root, "robots").listFiles { file -> file.name.endsWith(".yaml") }!!.flatMap { file ->
                Regex("""(?m)^\s*-?\s*id: ([A-Z]+-[0-9]+)$""").findAll(file.readText()).map { it.groupValues[1] }.toList()
            }.toSet()
        }
    }
}

/** The writer was stopped after the line a spec named: what a daemon killed there leaves on disk is all there is. */
class WriterStopped : RuntimeException("the writer was stopped here")

/** A disk that, once [armed], lets one more line through after a line [after] matches, then throws [WriterStopped]. */
private class StoppingDisk(private val after: (Envelope) -> Boolean) : Channels {
    var armed = false
    private var stopped = false

    override fun open(path: Path, vararg options: java.nio.file.OpenOption): FileChannel = object : FileChannel() {
        private val real = FileChannel.open(path, *options)
        override fun write(src: ByteBuffer): Int {
            if (stopped) throw WriterStopped()
            val text = Charsets.UTF_8.decode(src.duplicate()).toString()
            return real.write(src).also { if (armed && text.endsWith("\n") && after(parseLine(text.trimEnd('\n')))) stopped = true }
        }
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = (offset until offset + length).sumOf { write(srcs[it]).toLong() }
        override fun write(src: ByteBuffer, position: Long): Int = throw UnsupportedOperationException("the writer appends")
        override fun force(metaData: Boolean) = real.force(metaData)
        override fun read(dst: ByteBuffer): Int = real.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
        override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
        override fun position(): Long = real.position()
        override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
        override fun size(): Long = real.size()
        override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
        override fun implCloseChannel() = real.close()
    }
}

/** The log read back: every line of every file under `mail/` (but `_hand/`), by kp-model's reader, in `seq` order. */
class ReadBack(project: Path) {
    val lines: List<Envelope> = project.resolve("mail").walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") && "_hand" !in it.toString() }
        .flatMap { file -> file.readLines().map(::parseLine) }.sortedBy { it.seq }.toList()

    /** The lines a `voided` record says count for nothing: those of its judgement but the first (contracts.md §2.2). */
    fun voidedLines(): List<Envelope> {
        val voided = lines.filter { it.event() == "voided" }.map { (it.long("first") + 1)..it.long("last") }
        return lines.filter { line -> voided.any { line.seq.toLong() in it } }
    }

    /** The log less its voided lines: what counts. */
    val counted: List<Envelope> get() = (lines - voidedLines().toSet())

    /**
     * Each judgement of the log a `judged` line closes, as its lines read — its first line to that `judged` (contracts.md
     * §3). A `voided` record opens the judgement that carries a voided one's first line again; the voided judgement is
     * in [voidedJudgements]. A message reads `<kind> by <by>`, a record its event and what it moved.
     */
    fun judgements(): List<List<String>> {
        val all = ArrayList<List<String>>()
        var open = ArrayList<String>()
        lines.forEach { line ->
            if (line.event() == "voided") open = ArrayList()
            open += told(line)
            if (line.event() == "judged") all += open.also { open = ArrayList() }
        }
        return all
    }

    /** Each judgement a `voided` record names, as its lines read on disk: its first line and those that count for nothing. */
    fun voidedJudgements(): List<List<String>> = lines.filter { it.event() == "voided" }.map { voided ->
        lines.filter { it.seq.toLong() in voided.long("first")..voided.long("last") }.map(::told)
    }

    private fun told(line: Envelope): String = when {
        line is Message -> "${line.kind} by ${line.by}"
        line is Transition -> "transition ${line.from}→${line.to}"
        line is Op -> "op ${line.op} ${line.phase}"
        line.event() == "fact" -> "fact ${line.plain()["fact"]}"
        else -> line.event()!!
    }

    /**
     * Every line that no `judged` closes and no `voided` voids, and a log that does not end with a `judged` line (S1.10's
     * DONE): a judgement runs from its first line to the `judged` naming it; a `voided` names the judgement before it and
     * opens the one that carries its first line again.
     */
    fun outsideAJudgement(): List<String> {
        val problems = ArrayList<String>()
        var first: Long? = null
        lines.forEach { line ->
            val seq = line.seq.toLong()
            when (line.event()) {
                "judged" -> {
                    if (line.long("first") != first) problems += "$seq judged names ${line.long("first")}, not the first line of its judgement ($first)"
                    first = null
                }
                "voided" -> {
                    if (first == null || line.long("first") != first || line.long("last") != seq - 1) problems += "$seq voided names ${line.long("first")}–${line.long("last")}, not the judgement before it"
                    first = seq
                }
                else -> if (first == null) first = seq
            }
        }
        if (lines.isNotEmpty() && lines.last().event() != "judged") problems += "${lines.last().seq}: the log does not end with a judged line"
        return problems
    }

    val messages: List<Message> get() = lines.filterIsInstance<Message>()
    fun message(id: String): Message = messages.single { it.id.value == id }
    fun sent(kind: String): List<Message> = messages.filter { it.kind == kind }
    inline fun <reified T : Record> records(): List<T> = lines.filterIsInstance<T>()
    fun expired(id: String): List<Expired> = records<Expired>().filter { it.id == id }
    fun ops(cause: String): List<String> = records<Op>().filter { it.cause == cause }.map { listOfNotNull(it.op, it.phase, it.reason).joinToString(" ") }
    fun transitions(cause: String): List<String> = records<Transition>().filter { it.cause == cause }.map { "${it.rule} ${it.from}→${it.to}" }
    fun rules(): List<Rules> = records()

    /** [id] has its message line and a `delivered`, `refused` or `held` record. */
    fun hasLineAndRecord(id: String): Boolean =
        messages.any { it.id.value == id } && lines.any { it is Record && it.event in setOf("delivered", "refused", "held") && it.plain()["id"] == id }

    /** Each ticket or `notify.bora` about a message by Bora's command line (contracts.md §2.2: Bora's own message gets neither). */
    fun borasOwnWaiting(): List<String> {
        val boras = messages.filter { it.by == "bora-cli" }.map { it.id.value }.toSet()
        val tickets = lines.filterIsInstance<Record>().filter { it.plain()["ticket"] != null && it.plain()["id"] in boras }.map { "${it.seq}: a ticket for ${it.plain()["id"]}" }
        val notified = records<Op>().filter { it.op == "notify.bora" && (it.plain()["with"] as? Map<*, *>)?.get("item") in boras }.map { "${it.seq}: notify.bora for Bora's own message" }
        return tickets + notified
    }

    /** Each line `by: robot:<id>` — a message or a `held` record — whose rule id is not one of [known] (S1.4's DONE). */
    fun robotRulesNotIn(known: Set<String>): List<String> = lines.mapNotNull { line ->
        val by = line.plain()["by"] as? String ?: return@mapNotNull null
        by.takeIf { it.startsWith("robot:") && it.removePrefix("robot:") !in known }?.let { "${line.seq}: by $it, which is no rule of robots/" }
    }

    /**
     * The log as a story, in its order: one line per message that was delivered, held or refused — its kind, the
     * robot's rule that sent it, what became of it — and one per move of Bora's presence.
     */
    fun story(): List<String> = counted.mapNotNull { line ->
        fun told(id: String, what: String): String {
            val message = message(id)
            return message.kind + (message.by.takeIf { it.startsWith("robot:") }?.let { " by $it" } ?: "") + " " + what
        }
        when (line) {
            is kp.model.Delivered -> told(line.id, "delivered")
            is kp.model.Held -> told(line.id, "held by ${line.by}")
            is kp.model.Refused -> told(line.id, "refused")
            is Transition -> if (line.machine == "presence") "presence ${line.from}→${line.to}" else null
            else -> null
        }
    }

    /** `seq` runs 1, 2, 3 … with no gap and no repeat (contracts.md §2.2). */
    fun seqRunsWithoutGap(): Boolean = lines.map { it.seq.toLong() } == (1L..lines.size).toList()

    /**
     * Every `(op, cause)` that is `requested` more than once (contracts.md §7: at most once per request). A robot's
     * wakes are one per rule: two rules may wake their seat for one cause, each in its own mode; and one message wakes each seat it is
     * delivered to — its addressee, and later a seat it copies (S2.4).
     */
    fun requestedTwice(): List<String> = counted.filterIsInstance<Op>().filter { it.phase == "requested" }
        .groupBy { "${it.op} ${it.cause}" + ((it.plain()["with"] as? Map<*, *>)?.let { with -> listOfNotNull(with["rule"], with["seat"]) }?.joinToString("") { " $it" } ?: "") }
        .filterValues { it.size > 1 }.keys.toList()

    /** Every record about a message — by its `id`, or a transition or operation it caused — that comes before the message's line. */
    fun recordsBeforeTheirMessage(): List<String> {
        val at = messages.associate { it.id.value to it.seq }
        return lines.filterIsInstance<Record>().mapNotNull { record ->
            val about = (record.plain()["id"] ?: record.plain()["cause"]) as? String
            val message = about?.let(at::get)
            when {
                about == null || !about.startsWith("m-") || about == SEEDED -> null
                message == null -> "${record.seq} ${record.event} names $about, which is in no line"
                record.seq < message -> "${record.seq} ${record.event} comes before $about"
                else -> null
            }
        }
    }
}
