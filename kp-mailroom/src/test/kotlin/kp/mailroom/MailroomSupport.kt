package kp.mailroom

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.engine.fold
import kp.log.LogWriter
import kp.model.Envelope
import kp.model.Expired
import kp.model.Message
import kp.model.OfficeId
import kp.model.Op
import kp.model.Record
import kp.model.Rules
import kp.model.Transition
import kp.model.parseLine
import kp.model.plain
import java.io.File
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

/** A walk of `rules/walks/`, as the Python walker reads it (`check-rules.py --trace`). */
class Walk private constructor(val facts: Map<String, Datum>, val steps: List<Map<String, Datum>>) {
    companion object {
        private val read = HashMap<String, Walk>()

        fun named(name: String): Walk = read.getOrPut(name) {
            val process = ProcessBuilder(File(root, "build/python/bin/python").path, "scripts/check-rules.py", "--trace", name)
                .directory(root).redirectErrorStream(true).start()
            val line = process.inputStream.bufferedReader().readLines().single { it.startsWith("WALK ") }
            process.waitFor()
            val walk = (Datum.of(plain(line.removePrefix("WALK "))) as Datum.Fields).fields
            Walk((walk.getValue("facts") as Datum.Fields).fields, (walk.getValue("steps") as Datum.Items).items.map { (it as Datum.Fields).fields })
        }
    }
}

private fun Datum?.text(): String? = (this as? Datum.Text)?.value
private fun Datum?.fields(): Map<String, Datum> = (this as? Datum.Fields)?.fields.orEmpty()

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
        LogWriter.open(project, panda).use { log ->
            fun line(text: String) = log.append(json("""{"v":1,"at":"$at",""" + text.removePrefix("{")))
            seed?.let { line("""{"event":"transition","machine":"stage","subject":"$STAGE","from":"planned","to":"$it","cause":"$SEEDED","counted":$counters}""") }
            others.forEach { (stage, state) ->
                line("""{"event":"transition","machine":"stage","subject":"$stage","from":"planned","to":"$state","cause":"$SEEDED","counted":${otherCounters[stage] ?: "{}"}}""")
            }
            seats.forEach { line("""{"event":"transition","machine":"seat","subject":"$it","from":"provisioned","to":"${seatStates[it] ?: "ready"}","cause":"$SEEDED"}""") }
        }
        start()
    }

    fun start() { mailroom = Mailroom.start(project, panda, texts, facts, operations, seats, clock, robots = robots) }
    fun stop() = mailroom.close()
    fun restart() { stop(); start() }

    fun send(seat: String, input: String): Answer = mailroom.submit(Sender.Seat(seat), json(input)).also { submitted += it.id }
    fun bora(input: String, signed: Boolean = true): Answer = mailroom.submit(Sender.Bora(signed), json(input)).also { submitted += it.id }

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
     * operations that follow it, which the fake performer finishes at once. Returns the stage's state after each.
     */
    fun play(kinds: List<String>): List<String?> = kinds.map { kind ->
        val index = walk.steps.withIndex().first { (i, step) -> i >= next && step["kind"].text() == kind && step["who"].text() != "mailroom" }.index
        val step = walk.steps[index]
        next = index + 1
        walk.steps.drop(index).takeWhile { it == step || it["operation"] != null }.forEach { facts.set(it["facts"].fields()) }
        clock.advance(1.minutes)
        val re = step["re"].text()?.let { ids.getValue(it) }
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
                log.robotRulesNotIn(robotRuleIds)
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

/** The log read back: every line of every file under `mail/` (but `_hand/`), by kp-model's reader, in `seq` order. */
class ReadBack(project: Path) {
    val lines: List<Envelope> = project.resolve("mail").walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") && "_hand" !in it.toString() }
        .flatMap { file -> file.readLines().map(::parseLine) }.sortedBy { it.seq }.toList()

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
    fun story(): List<String> = lines.mapNotNull { line ->
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
     * wakes are one per rule: two rules may wake their seat for one cause, each in its own mode.
     */
    fun requestedTwice(): List<String> = records<Op>().filter { it.phase == "requested" }
        .groupBy { "${it.op} ${it.cause}" + ((it.plain()["with"] as? Map<*, *>)?.get("rule")?.let { rule -> " $rule" } ?: "") }.filterValues { it.size > 1 }.keys.toList()

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
