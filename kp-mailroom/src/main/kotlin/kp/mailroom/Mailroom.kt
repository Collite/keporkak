package kp.mailroom

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.engine.Decision
import kp.engine.Engine
import kp.engine.FactRef
import kp.engine.Facts
import kp.engine.Outcome
import kp.engine.Requested
import kp.engine.State
import kp.engine.Trigger
import kp.engine.fold
import kp.log.LogReader
import kp.log.LogWriter
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import kp.model.utc
import kp.rules.Destination
import kp.rules.MessageClass
import kp.rules.PARTIES
import kp.rules.Problem
import kp.rules.RuleSet
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Who a message is submitted in the name of: the mailroom sets `from`, `by` and `signed` from it, never from the input. */
sealed interface Sender {
    /** A seat, as its session token names it (contracts.md §5). */
    data class Seat(val name: String) : Sender
    /** Bora's command line; [signed] when it came over a root peer (contracts.md §6) — handed in by the caller. */
    data class Bora(val signed: Boolean) : Sender
}

/** The status of a submitted message (contracts.md §5). */
enum class Status { DELIVERED, HELD, REFUSED }

/** What the sender is answered once the message's lines are on disk (contracts.md §5's result). */
data class Answer(
    val status: Status,
    val id: String,
    val transition: String? = null,
    val rule: String? = null,
    val requirement: String? = null,
    val failed: Datum? = null,
    val refusalsToday: Long? = null,
)

/** A reload's outcome: the new set is in force from the next message, or nothing changed and these are the problems. */
sealed interface Reload {
    data class Accepted(val hash: String) : Reload
    data class Rejected(val problems: List<Problem>) : Reload
}

/** What a seat's inbox returns (contracts.md §5): its messages, and whether more are left. */
data class Inbox(val messages: List<Message>, val more: Boolean)

/** The mailroom would not start: the log or the rules are not fit to work from. */
class MailroomRefusal(message: String) : RuntimeException(message)

/** Who a message is from, as the mailroom writes it (contracts.md §2.1): `from`, `by` and `signed`. */
private class Author(val from: String, val by: String, val signed: Boolean)

private fun Sender.author(): Author = when (this) {
    is Sender.Seat -> Author(name, "seat:$name", false)
    is Sender.Bora -> Author(BORA, BORA_CLI, signed)
}

/**
 * The loop (architecture.md §3): one message from receipt to the log and on to its addressees. It collects the facts
 * the engine names, asks the engine, writes the lines and only then acts — delivers, performs, answers. It decides
 * nothing; its state is a cache of the log. One message, result or expiry is carried at a time.
 */
class Mailroom private constructor(
    private val project: Path,
    private val office: OfficeId,
    private val facts: FactSource,
    private val operations: Operations,
    private val deliveries: Deliveries,
    private val clock: Clock,
    private val writer: LogWriter,
    private val texts: RuleTexts,
    private var inForce: InForce,
) : AutoCloseable {
    /** The rule set every judgement reads: one per message, replaced only between two (architecture.md §3). */
    private val rules: RuleSet get() = inForce.ruleSet
    private var folded = State()
    /** Every message on the log the state was folded from, by id: what a reply, an inbox and a record's file read. */
    private val messages = HashMap<String, Message>()
    /** Per seat, the messages delivered to it, in the order they were: what an inbox of read and unread mail returns. */
    private val addressed = HashMap<String, MutableList<String>>()
    /** What follows a judgement once its lines are written: operations to perform, results to judge, messages to carry. */
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false
    private var closed = false
    /** A stage's file closed: the state is read again once the step that closed it is done. */
    private var reread = false

    /** The fold of the log as the mailroom works from it. */
    val state: State @Synchronized get() = folded

    /** Carries one message submitted in [sender]'s name; [input] holds what a sender supplies (contracts.md §2.1, §5). */
    @Synchronized
    fun submit(sender: Sender, input: JsonObject): Answer = arrive(sender.author(), input).also { settle(); drain() }

    /**
     * A seat's inbox (contracts.md §5): its unread messages — or, with [unread] false, every message delivered to it —
     * oldest first, at most [limit]. Returning a message marks it read for the seat: a `read` record names those it had
     * not read.
     */
    @Synchronized
    fun inbox(seat: String, unread: Boolean = true, limit: Int? = null): Inbox {
        val ids = if (unread) folded.unread(seat) else addressed[seat].orEmpty()
        val shown = limit?.let(ids::take) ?: ids
        val seen = shown.filter { it in folded.unread(seat) }
        if (seen.isNotEmpty()) write(line(now(), EVENT to text("read"), SEAT to text(seat), IDS to texts(seen)))
        return Inbox(shown.map(messages::getValue), more = ids.size > shown.size)
    }

    /**
     * Fires every deadline the clock has passed, earliest first (architecture.md §3: deadlines are timers on the clock).
     * The timers are the state's open waits — what the log says, so they are rebuilt by any start. Each wait is looked
     * at again before it fires: what an earlier expiry carried may have ended it or moved it.
     */
    @Synchronized
    fun tick() {
        val now = clock.now()
        val due = folded.deadlines.filterValues { wait -> wait.deadline?.let { !instant(it).isAfter(now) } == true }
            .entries.sortedWith(compareBy({ instant(it.value.deadline!!) }, { it.key })).map { it.key }
        due.forEach { id ->
            val wait = folded.deadlines[id]?.deadline ?: return@forEach
            if (instant(wait).isAfter(now)) return@forEach
            expire(id)
            settle()
            drain()
        }
    }

    /** The earliest deadline of an open wait — when [tick] has something to fire — for the process to set its timer. */
    @Synchronized
    fun nextDeadline(): Instant? = folded.deadlines.values.mapNotNull { it.deadline }.minOfOrNull(::instant)

    /**
     * Reads the rule texts again and puts them through `kp-rules` (architecture.md §3, hot reload). A valid set is in
     * force from the next message and leaves a `rules` record; an invalid one changes nothing — no line — and its
     * problems are the answer, for the caller to report. A message in flight finishes under the set it began with.
     */
    @Synchronized
    fun reload(): Reload {
        val (next, loaded) = InForce.load(texts.read())
        if (next == null) return Reload.Rejected(loaded.problems)
        inForce = next
        recordRules()
        return Reload.Accepted(next.hash)
    }

    /** The `rules` record (contracts.md §2.2): the set in force, at start and at every accepted reload. */
    private fun recordRules() =
        write(line(now(), EVENT to text("rules"), HASH to text(inForce.hash), FILES to JsonObject(inForce.files.mapValues { JsonPrimitive(it.value) })))

    @Synchronized
    override fun close() {
        closed = true
        writer.close()
    }

    // ---- the loop

    /**
     * One message: composed from what the sender supplied and what the mailroom sets, judged, written — its line, then
     * the record that says what happened to it, then the transitions — and only then answered.
     */
    private fun arrive(author: Author, input: JsonObject): Answer {
        val now = now()
        val message = compose(author, input, now)
        val engine = Engine(rules)
        val trigger = Trigger.Arrival(message)
        val decision = engine.judge(folded, trigger, table(engine, trigger, message.stage?.value, now))
        val id = message.id.value
        val deadline = decision.deadline.takeIf { decision.outcome == Outcome.DELIVER }
        write(JsonObject(message.json - "seq" + listOfNotNull(deadline?.let { DEADLINE to JsonPrimitive(it) })))
        if (decision.outcome == Outcome.REFUSE) {
            val refusals = folded.refusalsOn(author.from, utc(now).toLocalDate().toString()) + 1
            write(line(now, EVENT to text("refused"), ID to text(id), RULE to text(decision.rule), REQUIREMENT to text(decision.requirement),
                FAILED to decision.failed?.element(), OBSERVED to fields(decision.observed), REFUSALS_TODAY to JsonPrimitive(refusals)))
            return Answer(Status.REFUSED, id, rule = decision.rule, requirement = decision.requirement, failed = decision.failed, refusalsToday = refusals)
        }
        val written = messages.getValue(id)
        val addressees = addressees(message)
        val woke = listOf(addressees.first()).filter { it !in PARTIES && deliveries.idle(it) }
        val ticket = (writer.last + 1).takeIf { decision.waitsForBora }
        write(line(now, EVENT to text("delivered"), ID to text(id), TO to texts(addressees), WOKE to woke.takeIf { it.isNotEmpty() }?.let(::texts),
            CLASS to text(decision.messageClass?.name), RULE to text(decision.rule), TICKET to ticket?.let(::JsonPrimitive),
            COUNTED to counted(decision.counted), OBSERVED to fields(decision.observed), ATTESTED to decision.attested.takeIf { it.isNotEmpty() }?.let(::texts),
            ANSWERS to text(decision.answers), DEADLINE to deadline?.let(::text)))
        transitions(decision, id, now)
        effects(decision, id, now)
        if (ticket != null && decision.messageClass == MessageClass.B) notify(ticket, id, now)
        woke.filter { deliveries.wake(it, written) }.forEach { seat -> write(line(now(), EVENT to text("read"), SEAT to text(seat), IDS to texts(listOf(id)))) }
        return Answer(Status.DELIVERED, id, transition = decision.rule)
    }

    /** What the judgement requested, in its order: a declared emit is carried after this judgement's lines. */
    private fun effects(decision: Decision, cause: String, now: String) = decision.effects.forEach { effect ->
        when (effect) {
            is Requested.Emit -> emit(effect, declaredBy(effect), cause)
            is Requested.Do -> request(effect.operation, cause, Datum.Fields(effect.with), now)
            is Requested.Report -> Unit
        }
    }

    /**
     * What an emit is `by` (contracts.md §2.1): `expiry:<kind>` when a kind's expiry declares it — whether or not that
     * expiry fired a transition — and `effect:T-n` when a transition's effects declare it.
     */
    private fun declaredBy(effect: Requested.Emit): String =
        if (effect.rule in rules.kinds) "$EXPIRY_OF${effect.rule}" else "$EFFECT_OF${effect.rule}"

    /**
     * A deadline passed (contracts.md §4.2): the engine judges the expiry, and its `expired` record says what became of
     * the wait — a new deadline, or null when it is over — and, when the expiry reports to Bora, is that inbox item:
     * `report: bora`, its class, its ticket. Then the transitions it fired and its effects; `notify.bora` for class B.
     */
    private fun expire(id: String) {
        val now = now()
        val engine = Engine(rules)
        val trigger = Trigger.Expiry(id)
        val decision = engine.judge(folded, trigger, table(engine, trigger, messages[id]?.stage?.value, now))
        val report = decision.effects.filterIsInstance<Requested.Report>().firstOrNull()
        val ticket = (writer.last + 1).takeIf { decision.waitsForBora }
        write(line(now, EVENT to text("expired"), ID to text(id), RULE to text(decision.rule), REPORT to text(report?.to),
            CLASS to text(report?.messageClass?.name), TICKET to ticket?.let(::JsonPrimitive), DEADLINE to text(decision.deadline), keepNull = setOf(DEADLINE)))
        record(decision, id, now)
        if (ticket != null && report?.messageClass == MessageClass.B) notify(ticket, id, now)
    }

    /** What a judgement of a result or an expiry decided: its transitions and its effects, caused by [cause]. */
    private fun record(decision: Decision, cause: String, now: String) {
        transitions(decision, cause, now)
        effects(decision, cause, now)
    }

    /**
     * A message the rules make the mailroom send (`engagement.yaml:mailroom_declared_effects`): from `mailroom`, [by]
     * the rule that declared it, in reply to the message that caused it — its stage, its thread — and judged like any
     * other, once the lines of the judgement that requested it are written.
     */
    private fun emit(effect: Requested.Emit, by: String, cause: String) {
        val input = buildJsonObject {
            put(KIND, JsonPrimitive(effect.kind))
            effect.to?.let { put(TO, JsonPrimitive(it)) }
            put(RE, JsonPrimitive(cause))
            put(SUBJECT, JsonPrimitive("${effect.kind} ($by)"))
            put(POINTERS, texts(listOf("mail:$cause")))
            put(ARGS, fields(effect.args))
        }
        queue += { arrive(Author(MAILROOM, by, false), input) }
    }

    /**
     * Whom a message is delivered to: its addressee, then the kind's cc — a party as it is; a role or a seat as the
     * seat the world names for it in the message's stage (`seat.name`), or as written when none is named.
     */
    private fun addressees(message: Message): List<String> {
        fun seat(party: String): String = if (party in PARTIES) party
            else (facts.observe(FactRef(SEAT_NAME, mapOf(OF to Datum.Text(party))), message.stage?.value) as? Datum.Text)?.value ?: party
        return (listOf(message.to.value) + message.cc.orEmpty().map { it.value }).map(::seat).distinct()
    }

    /** `notify.bora` for a class-B item in Bora's inbox (contracts.md §4.4), requested for its ticket. */
    private fun notify(ticket: Long, item: String, now: String) =
        request(NOTIFY_BORA, "#$ticket", Datum.Fields(mapOf(TICKET to Datum.of(ticket), ITEM to Datum.Text(item))), now)

    // ---- operations (contracts.md §7)

    /** Writes the request, and performs it only once that line is on disk — after the judgement's own lines. */
    private fun request(operation: String, cause: String, with: Datum.Fields, now: String) {
        write(line(now, EVENT to text("op"), OP to text(operation), PHASE to text(REQUESTED), CAUSE to text(cause),
            WITH to with.takeIf { it.fields.isNotEmpty() }?.element()))
        queue += { perform(OperationRequest(operation, cause, with)) }
    }

    /** Hands the request to the performer once; a result it gives twice is taken once. */
    private fun perform(request: OperationRequest) {
        val answered = AtomicBoolean(false)
        operations.perform(request) { result -> if (answered.compareAndSet(false, true)) finished(request, result) }
    }

    /** A result — now, from inside [perform], or later, from the performer's own thread — joins the queue. */
    @Synchronized
    private fun finished(request: OperationRequest, result: OperationResult) {
        if (closed) return
        queue += { complete(request, result) }
        drain()
    }

    /**
     * The result's record, then the result as a trigger for the engine (contracts.md §4.1, `on: op-result`): what it fires
     * is recorded as caused by the request. An operation that starts or re-seats a seat names it in its result.
     */
    private fun complete(request: OperationRequest, result: OperationResult) {
        val now = now()
        write(when (result) {
            is OperationResult.Ok -> line(now, EVENT to text("op"), OP to text(request.operation), PHASE to text(OK), CAUSE to text(request.cause),
                RESULT to seated(request, result.result).takeIf { it.fields.isNotEmpty() }?.element())
            is OperationResult.Failed -> line(now, EVENT to text("op"), OP to text(request.operation), PHASE to text(FAILED), CAUSE to text(request.cause),
                REASON to text(result.reason))
        })
        val trigger = Trigger.Result(request.cause, request.operation, result is OperationResult.Ok)
        val engine = Engine(rules)
        record(engine.judge(folded, trigger, table(engine, trigger, messages[request.cause]?.stage?.value, now)), request.cause, now)
    }

    /**
     * At start, each operation the fold says is in flight — requested, with no result — is recorded `failed` with the
     * reason `interrupted` and judged as that result (contracts.md §4.4, §7). It is not performed again: a retry is
     * another firing of its kind.
     */
    private fun interrupted() = folded.operations.forEach { flight ->
        complete(OperationRequest(flight.op, flight.cause, flight.with), OperationResult.Failed(INTERRUPTED))
    }

    /** `seat.start` and `seat.reseat` are `ok(seat)` (contracts.md §4.4, Amendment 13): the result names the seat, the one it was asked for when the performer did not. */
    private fun seated(request: OperationRequest, result: Datum.Fields): Datum.Fields {
        val seat = request.with.fields[SEAT]
        return if (request.operation !in SEATING || seat == null || SEAT in result.fields) result else Datum.Fields(result.fields + (SEAT to seat))
    }

    /** Carries what the queue holds, in order, until it is empty; what a step adds runs after it. */
    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty() && !closed) {
                queue.removeFirst()()
                settle()
            }
        } finally {
            draining = false
        }
    }

    /**
     * When a stage's file has closed, the state is what start-up would fold from the log now (contracts.md §3: the
     * open stage files and the office's), so that a mailroom started again works from the same state as this one.
     */
    private fun settle() {
        if (!reread) return
        reread = false
        folded = State()
        messages.clear()
        addressed.clear()
        take(LogReader(project, office).read().lines.map { it.entry })
    }

    /**
     * The message as the mailroom writes it (contracts.md §2.1): from the input only what a sender supplies — `kind`,
     * `to`, `stage`, `subject`, `body`, `pointers`, `args`, `re`; `id`, `thread`, `at`, `created`, `from`, `by` and
     * `signed` set here. A reply's thread, stage and addressee default to the message it answers'.
     */
    private fun compose(author: Author, input: JsonObject, now: String): Message {
        fun field(name: String) = input[name]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        fun string(name: String) = field(name)?.jsonPrimitive?.contentOrNull
        val kind = requireNotNull(string(KIND)) { "a message names its kind" }
        val seq = writer.last + 1
        val id = "m-${office.value}-%06d".format(seq)
        val re = string(RE)?.let(messages::get)
        val args = field(ARGS)?.jsonObject
        val declared = rules.kinds[kind]
        val to = string(TO) ?: declared?.to?.let { destination ->
            when (destination) {
                is Destination.Parties -> destination.names.first()
                is Destination.Reply -> re?.from?.value
                Destination.AnySeat -> args?.get(SEAT)?.jsonPrimitive?.contentOrNull
            }
        }
        requireNotNull(to) { "a message of kind '$kind' names its addressee" }
        val subject = string(SUBJECT) ?: re?.subject?.let { "re: $it".take(SUBJECT_LIMIT) } ?: kind
        return Message.read(line(now,
            ID to text(id), THREAD to text(re?.thread?.value ?: id), RE to field(RE), FROM to text(author.from), TO to text(to),
            CC to declared?.cc?.takeIf { it.isNotEmpty() }?.let(::texts), KIND to text(kind), STAGE to (field(STAGE) ?: re?.stage?.value?.let(::text)),
            SUBJECT to text(subject), BODY to field(BODY), POINTERS to field(POINTERS)?.jsonArray, ARGS to args, CREATED to text(now),
            SIGNED to JsonPrimitive(true).takeIf { author.signed }, BY to text(author.by),
        ).let { JsonObject(it + (SEQ to JsonPrimitive(seq))) })
    }

    /** The facts the engine names for [trigger], collected — `now` from the clock, the others from the world. */
    private fun table(engine: Engine, trigger: Trigger, stage: String?, now: String): Facts =
        Facts.collect(engine.needs(folded, trigger)) { fact -> if (fact.name == NOW) Datum.Text(now) else facts.observe(fact, stage) }

    /** One `transition` record per machine the decision moves, caused by [cause]. */
    private fun transitions(decision: Decision, cause: String, now: String) = decision.moves.forEach { move ->
        write(line(now, EVENT to text("transition"), MACHINE to text(move.machine), SUBJECT to text(move.subject), FROM to text(move.from),
            TO to text(move.to), RULE to text(move.rule), CAUSE to text(cause), COUNTED to counted(move.counted)))
    }

    // ---- the log

    /**
     * Appends one line, synced, to the file of the stage it is about — a message to its own — or to the office's file
     * when it is about no stage, or its stage's file is closed; then folds it. Nothing is acted on before this returns.
     */
    private fun write(draft: JsonObject): Envelope {
        val expected = writer.last + 1
        val stage = if (draft[KIND] != null) null else about(draft)?.let(::StageId)?.takeUnless(writer::isClosed)
        val written = writer.append(draft, stage)
        check(written.seq.longValueExact() == expected) { "the log gave seq ${written.seq}, not $expected" }
        if (stage != null && writer.isClosed(stage)) reread = true
        take(listOf(written))
        return written
    }

    /** The stage a record is about (contracts.md §3): its message's, its subject's for a stage-like machine; none for seats, phases, presence, rules, reads. */
    private fun about(record: JsonObject): String? {
        fun field(name: String) = record[name]?.jsonPrimitive?.contentOrNull
        fun stageOf(id: String?) = id?.let(messages::get)?.stage?.value
        return when (field(EVENT)) {
            "delivered", "refused", "held", "expired" -> stageOf(field(ID))
            "op" -> stageOf(field(CAUSE))
            "transition" -> when (field(MACHINE)) {
                "stage" -> field(SUBJECT)
                "found" -> field(SUBJECT)?.substringBefore('#')
                "flag" -> stageOf(field(SUBJECT))
                else -> null
            }
            else -> null
        }
    }

    /** Folds [lines] into the state and keeps their messages, and to whom each was delivered. */
    private fun take(lines: List<Envelope>) {
        lines.forEach { line ->
            if (line is Message) messages[line.id.value] = line
            if (line is Delivered) line.to.forEach { addressed.getOrPut(it, ::ArrayList) += line.id }
        }
        folded = fold(lines, folded)
    }

    private fun now(): String = clock.now().truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {
        /** Opens the log, folds it, puts the rules in force and records what start-up records (contracts.md §2.2, §4.4). */
        fun start(project: Path, office: OfficeId, rules: RuleTexts, facts: FactSource, operations: Operations, deliveries: Deliveries, clock: Clock): Mailroom {
            val writer = LogWriter.open(project, office)
            try {
                val read = LogReader(project, office).read()
                if (read.problems.isNotEmpty()) throw MailroomRefusal("the log has problems:\n" + read.problems.joinToString("\n"))
                val (inForce, loaded) = InForce.load(rules.read())
                if (inForce == null) throw MailroomRefusal("the rules are not valid:\n" + loaded.problems.joinToString("\n"))
                return Mailroom(project, office, facts, operations, deliveries, clock, writer, rules, inForce).apply {
                    take(read.lines.map { it.entry })
                    recordRules()
                    interrupted()
                    settle()
                    drain()
                }
            } catch (error: Throwable) {
                writer.close()
                throw error
            }
        }
    }
}

/** A time the log writes (`at`, `deadline`), as an instant. */
private fun instant(at: String): Instant = utc(at).toInstant(ZoneOffset.UTC)

private const val BORA = "bora"
private const val MAILROOM = "mailroom"
private const val SEAT_NAME = "seat.name"
private const val NOTIFY_BORA = "notify.bora"
private const val REQUESTED = "requested"
private const val OK = "ok"
private const val OF = "of"
private const val ITEM = "item"
private const val INTERRUPTED = "interrupted"
/** The operations whose `ok` names a seat (contracts.md §4.4). */
private val SEATING = setOf("seat.start", "seat.reseat")
private const val BORA_CLI = "bora-cli"
/** The `by` of an emit a kind's expiry declares, and of one a transition's effects declare (contracts.md §2.1). */
private const val EXPIRY_OF = "expiry:"
private const val EFFECT_OF = "effect:"
private const val NOW = "now"
private const val SUBJECT_LIMIT = 120

private const val SEQ = "seq"
private const val EVENT = "event"
private const val ID = "id"
private const val THREAD = "thread"
private const val RE = "re"
private const val FROM = "from"
private const val TO = "to"
private const val CC = "cc"
private const val KIND = "kind"
private const val STAGE = "stage"
private const val SUBJECT = "subject"
private const val BODY = "body"
private const val POINTERS = "pointers"
private const val ARGS = "args"
private const val CREATED = "created"
private const val SIGNED = "signed"
private const val BY = "by"
private const val DEADLINE = "deadline"
private const val CLASS = "class"
private const val RULE = "rule"
private const val REQUIREMENT = "requirement"
private const val FAILED = "failed"
private const val OBSERVED = "observed"
private const val REFUSALS_TODAY = "refusals_today"
private const val COUNTED = "counted"
private const val ATTESTED = "attested"
private const val ANSWERS = "answers"
private const val MACHINE = "machine"
private const val CAUSE = "cause"
private const val SEAT = "seat"
private const val HASH = "hash"
private const val FILES = "files"
private const val REPORT = "report"
private const val IDS = "ids"
private const val WOKE = "woke"
private const val TICKET = "ticket"
private const val OP = "op"
private const val PHASE = "phase"
private const val WITH = "with"
private const val RESULT = "result"
private const val REASON = "reason"
