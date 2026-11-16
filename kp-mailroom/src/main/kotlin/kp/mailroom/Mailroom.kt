package kp.mailroom

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.engine.Decision
import kp.engine.Engine
import kp.engine.Facts
import kp.engine.Outcome
import kp.engine.State
import kp.engine.Trigger
import kp.engine.fold
import kp.log.LogReader
import kp.log.LogWriter
import kp.model.Envelope
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import kp.model.utc
import kp.rules.Destination
import kp.rules.Loaded
import kp.rules.Problem
import kp.rules.RuleSet
import kp.rules.Rules
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    private val clock: Clock,
    private val writer: LogWriter,
    private var rules: RuleSet,
) : AutoCloseable {
    private var folded = State()
    /** Every message on the log the state was folded from, by id: what a reply, an inbox and a record's file read. */
    private val messages = HashMap<String, Message>()

    /** The fold of the log as the mailroom works from it. */
    val state: State @Synchronized get() = folded

    /** Carries one message submitted in [sender]'s name; [input] holds what a sender supplies (contracts.md §2.1, §5). */
    @Synchronized
    fun submit(sender: Sender, input: JsonObject): Answer = arrive(sender.author(), input)

    /** A seat's inbox (contracts.md §5): returning a message marks it read for the seat. */
    fun inbox(seat: String, unread: Boolean = true, limit: Int? = null): Inbox = TODO()

    /** Fires every deadline the clock has passed. */
    fun tick(): Unit = TODO()

    /** The next deadline of an open wait, for the process to set its timer. */
    fun nextDeadline(): Instant? = TODO()

    /** Reads the rule texts again; a valid set is in force from the next message, an invalid one changes nothing. */
    fun reload(): Reload = TODO()

    @Synchronized
    override fun close() = writer.close()

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
        write(line(now, EVENT to text("delivered"), ID to text(id), TO to texts(listOf(message.to.value)), CLASS to text(decision.messageClass?.name),
            RULE to text(decision.rule), COUNTED to counted(decision.counted), OBSERVED to fields(decision.observed),
            ATTESTED to decision.attested.takeIf { it.isNotEmpty() }?.let(::texts), ANSWERS to text(decision.answers), DEADLINE to deadline?.let(::text)))
        transitions(decision, id, now)
        return Answer(Status.DELIVERED, id, transition = decision.rule)
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

    /** Folds [lines] into the state and keeps their messages. */
    private fun take(lines: List<Envelope>) {
        lines.filterIsInstance<Message>().forEach { messages[it.id.value] = it }
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
                val ruleSet = when (val loaded = Rules.load(rules.read())) {
                    is Loaded.Valid -> loaded.ruleSet
                    is Loaded.Invalid -> throw MailroomRefusal("the rules are not valid:\n" + loaded.problems.joinToString("\n"))
                }
                return Mailroom(project, office, facts, clock, writer, ruleSet).apply { take(read.lines.map { it.entry }) }
            } catch (error: Throwable) {
                writer.close()
                throw error
            }
        }
    }
}

private const val BORA = "bora"
private const val BORA_CLI = "bora-cli"
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
