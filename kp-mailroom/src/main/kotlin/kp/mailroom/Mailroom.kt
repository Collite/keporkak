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
import kp.engine.Gate
import kp.engine.InFlight
import kp.engine.Facts
import kp.engine.Need
import kp.engine.Outcome
import kp.engine.Reaction
import kp.engine.Requested
import kp.engine.RobotHold
import kp.engine.State
import kp.engine.Trigger
import kp.engine.fold
import kp.engine.settled
import kp.log.Channels
import kp.log.LogLine
import kp.log.LogReader
import kp.log.LogWriter
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Fact
import kp.model.Judged
import kp.model.Message
import kp.model.OfficeId
import kp.model.Op
import kp.model.Party
import kp.model.StageId
import kp.model.Voided
import kp.model.plain
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

/** The stop gate's answer to the stop hook (contracts.md §11): block, with the rule's prose as [reason], or let the turn end. */
data class Stop(val block: Boolean, val reason: String)

/**
 * A kind a seat may send (contracts.md §5, `whoami`'s `allowed`): to whom, the kinds that answer it, its deadline as the
 * rules write it, its window, and how many firings are left in it — null when nothing counts it.
 */
data class Allowed(val kind: String, val to: String, val replies: List<String>, val deadline: String?, val window: String, val left: Long?)

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
 * nothing; its state is a cache of the log. One message, result, expiry or fact is carried at a time, and what it
 * produces is one judgement (contracts.md §3, Amendment 23): its lines, the messages the rules and the robots send in
 * answer among them, reach the disk together, closed by a `judged` line — and the mailroom turns to the world only then.
 */
class Mailroom private constructor(
    private val office: OfficeId,
    private val facts: FactSource,
    private val operations: Operations,
    private val deliveries: Deliveries,
    private val clock: Clock,
    private val writer: LogWriter,
    private val texts: RuleTexts,
    private var inForce: InForce,
    /**
     * The robots host (contracts.md §8, architecture.md §1): each active robot hears what its rules trigger on, and
     * what it sends goes through the loop like any seat's message, `by: robot:<rule>`; what it keeps is held. Off,
     * the mailroom only carries messages.
     */
    private val robots: Boolean,
) : AutoCloseable {
    /** The rule set every judgement reads: one per message, replaced only between two (architecture.md §3). */
    private val rules: RuleSet get() = inForce.ruleSet
    private var folded = State()
    /** Every message on the log the state was folded from, by id: what a reply, an inbox and a record's file read. */
    private val messages = HashMap<String, Message>()
    /** Per seat, the messages delivered to it, in the order they were: what an inbox of read and unread mail returns. */
    private val addressed = HashMap<String, MutableList<String>>()
    /** The judgement being written (contracts.md §3): its lines reach the disk together, closed by a `judged` line. */
    private var judgement: LogWriter.Judgement? = null
    private var judging = false
    /** What the judgement being written still carries: the messages the rules and the robots send in answer, the held messages let go. */
    private val carrying = ArrayDeque<() -> Unit>()
    /** What waits for a judgement's `judged` line to be on disk: the operations it requested, performed, and the seats it wakes. */
    private val world = ArrayDeque<() -> Unit>()
    /** Results that arrived, each to be judged in a judgement of its own. */
    private val arrivals = ArrayDeque<() -> Unit>()
    private var draining = false
    private var closed = false

    /** The fold of the log as the mailroom works from it. */
    val state: State @Synchronized get() = folded

    /**
     * Carries one message submitted in [sender]'s name; [input] holds what a sender supplies (contracts.md §2.1, §5).
     * A message from Bora's command line is a command of his: what it does to his presence comes first.
     */
    @Synchronized
    fun submit(sender: Sender, input: JsonObject): Answer {
        if (sender is Sender.Bora) command(sender.signed)
        return judged { arrive(sender.author(), input) }.also { drain() }
    }

    /**
     * As [submit], for input a seat's tool hands on as it came (contracts.md §5): input that is no message at all — no
     * kind, a kind, stage, subject, body, pointers, `args` or `re` outside its form — is answered [Offer.Malformed],
     * naming the field, and nothing is written.
     */
    @Synchronized
    fun offer(sender: Sender, input: JsonObject): Offer = malformed(input) ?: Offer.Answered(submit(sender, input))

    /**
     * Bora ran a command (contracts.md §6) — [signed] when it came over a root peer. Only a signed command counts as
     * acting: it is the fact `bora.acted`, which moves presence through its `on: fact` transitions (§4.1). An unsigned
     * command leaves presence where it was.
     */
    @Synchronized
    fun command(signed: Boolean) {
        if (signed) judged { fact(FactRef(BORA_ACTED), Datum.Bool(true), null, SIGNED_COMMAND) }
        drain()
    }

    /**
     * The world reports that [fact] changed (contracts.md §4.1 `on: fact`, §8): [value] is its value now, [stage] the
     * stage it concerns, when one, and [evidence] what shows it. What it triggers — a transition, a robot's rule — is
     * judged; when it triggers anything, a `fact` record says so first (§2.2). `bora.acted` is no fact the world
     * reports: only a signed command is one ([command]).
     */
    @Synchronized
    fun changed(fact: FactRef, value: Datum, stage: String? = null, evidence: String? = null) {
        require(fact.name != BORA_ACTED) { "$BORA_ACTED is a signed command: command(signed = true)" }
        judged { fact(fact, value, stage, evidence) }
        drain()
    }

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
        if (seen.isNotEmpty()) judged { write(line(now(), EVENT to text("read"), SEAT to text(seat), IDS to texts(seen))) }
        return Inbox(shown.map(messages::getValue), more = ids.size > shown.size)
    }

    /**
     * [seat]'s unread messages, oldest first, without marking them read (contracts.md §2.2): what a re-seat replays.
     */
    @Synchronized
    fun unread(seat: String): List<Message> = folded.unread(seat).map(messages::getValue)

    /**
     * [seat] has seen [ids] — a re-seat's prompt replayed them (contracts.md §4.4, Amendment 22): a `read` record names
     * those still unread, a judgement of its own.
     */
    @Synchronized
    fun read(seat: String, ids: List<String>) {
        val seen = ids.filter { it in folded.unread(seat) }
        if (seen.isNotEmpty()) judged { write(line(now(), EVENT to text("read"), SEAT to text(seat), IDS to texts(seen))) }
        drain()
    }

    /**
     * [seat]'s turn ended, or it has just come up (contracts.md §4.4, Amendment 22): when it is idle and has unread mail,
     * it is woken with the oldest.
     */
    @Synchronized
    fun idle(seat: String) {
        judged { wakeOldest(seat) }
        drain()
    }

    /**
     * The messages of the thread [id] (contracts.md §5), in the order they were delivered — shown only to a seat that
     * sent, received or was copied on one of them; null otherwise, as for a thread that is not on the log. Nothing is
     * marked read: only the inbox does that.
     */
    @Synchronized
    fun thread(seat: String, id: String): List<Message>? {
        val delivered = folded.delivered.values.filter { it.thread == id }
        val received = addressed[seat].orEmpty().toSet()
        return delivered.takeIf { all -> all.any { it.from == seat || it.id in received } }?.map { messages.getValue(it.id) }
    }

    /**
     * Each kind [seat] may send about [stage] (contracts.md §5, `whoami`): to whom, what answers it, its deadline, its
     * window and how many firings are left in it now — asked of the engine, which reads the rules and the state.
     */
    @Synchronized
    fun allowed(seat: String, stage: String?): List<Allowed> = Engine(rules).allowed(folded, seat, stage, now()).map {
        Allowed(it.kind, it.to, it.replies, it.deadline, it.window, it.left)
    }

    /**
     * The threads with an open wait (contracts.md §2.2) that waits for [seat] — a message delivered to it — or that it
     * waits on — a message it sent; each once, in the order the waits opened.
     */
    @Synchronized
    fun open(seat: String): List<String> {
        val received = addressed[seat].orEmpty().toSet()
        return folded.deadlines.filter { (id, wait) -> wait.from == seat || id in received }.keys.mapNotNull { messages[it]?.thread?.value }.distinct()
    }

    /**
     * The stop gate (contracts.md §4.3, §11): [seat]'s turn is about to end after [blocks] consecutive blocks. The engine
     * says end, or block with the gate's rule. At the cap the turn ends and one `fact` record, `turn.blocked`, says
     * so — written whatever else the fact triggers (§2.2), and heard like any fact that changed.
     */
    @Synchronized
    fun stopGate(seat: String, blocks: Int): Stop {
        val engine = Engine(rules)
        val gate = engine.turnEnds(folded, seat, blocks, table(engine.turnNeeds(folded, seat), null, now()))
        if (gate is Gate.End && gate.capped) {
            judged { fact(FactRef(TURN_BLOCKED, mapOf(SEAT to Datum.Text(seat))), Datum.of(blocks), null, AT_THE_CAP, always = true) }
            drain()
        }
        return if (gate is Gate.Block) Stop(block = true, reason = gate.reason) else Stop(block = false, reason = "")
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
            judged { expire(id) }
            drain()
        }
    }

    /** The earliest deadline of an open wait — when [tick] has something to fire — for the process to set its timer. */
    @Synchronized
    fun nextDeadline(): Instant? = folded.deadlines.values.mapNotNull { it.deadline }.minOfOrNull(::instant)

    /**
     * Reads the rule texts again and puts them through `kp-rules` (architecture.md §3, hot reload). A valid set is in
     * force from the next message and leaves a `rules` record. An invalid one leaves the old set in force, writes no
     * `rules` record and requests `notify.bora` with the cause `rules` and its problems in `with` (contracts.md §2.2);
     * the problems are also the answer. A message in flight finishes under the set it began with.
     */
    @Synchronized
    fun reload(): Reload {
        val (next, loaded) = InForce.load(texts.read())
        if (next == null) {
            judged { request(NOTIFY_BORA, RULES, Datum.Fields(mapOf(PROBLEMS to Datum.Items(loaded.problems.map { Datum.Text("$it") }))), now()) }
            drain()
            return Reload.Rejected(loaded.problems)
        }
        inForce = next
        judged { recordRules() }
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
        return arrived(compose(author, input, now), now) { line -> write(line) }
    }

    /**
     * A message judged as it arrives; [first] writes its line — or, when a start carries it again, the `voided` record
     * that stands for the line already on the log (contracts.md §3).
     */
    private fun arrived(message: Message, now: String, first: (JsonObject) -> Unit): Answer {
        val engine = Engine(rules)
        val trigger = Trigger.Arrival(message)
        val stage = message.stage?.value
        val facts = table(engine.needs(folded, trigger) + hears(engine, trigger), stage, now)
        val reaction = if (robots) engine.react(folded, trigger, facts) else null
        val decision = engine.judge(folded, trigger, facts)
        // A message QA's queue keeps waits for QA: it is judged now, and refused now when it would be. One the Deputy
        // keeps waits for Bora: only what holds for every kind is checked — its kind's own requirements may hold only
        // once he is back (the deadman of `merge`).
        reaction?.held?.let { held ->
            val refusal = if (held.ticket) engine.admit(folded, trigger, facts) else decision.takeIf { it.outcome == Outcome.REFUSE }
            first(JsonObject(message.json - "seq"))
            return keep(message, held, refusal, reaction, now)
        }
        val deadline = decision.deadline.takeIf { decision.outcome == Outcome.DELIVER }
        first(JsonObject(message.json - "seq" + listOfNotNull(deadline?.let { DEADLINE to JsonPrimitive(it) })))
        return settle(message, decision, now, arrival = reaction)
    }

    /**
     * A robot keeps the arriving message instead of its addressee (contracts.md §2.2, §8) — unless what holds for every
     * kind refuses it ([refusal]): it is then written and refused like any other. Kept, its line carries no deadline —
     * holding suspends it — and its `held` record names the rule, what ends it, and, when it waits for Bora, its ticket
     * (`notify.bora` for class B). Then the robot's sends and wakes.
     */
    private fun keep(message: Message, held: RobotHold, refusal: Decision?, reaction: Reaction, now: String): Answer {
        if (refusal != null) return settle(message, refusal, now)
        val id = message.id.value
        writeHeld(id, held, now)
        carry(reaction, id, now)
        return Answer(Status.HELD, id, rule = held.by)
    }

    /**
     * The `held` record (contracts.md §2.2): by the rule, until what ends it, its deadline suspended; one that waits for
     * Bora has a ticket — the one its item already has, else a new one, with `notify.bora` for class B.
     */
    private fun writeHeld(id: String, held: RobotHold, now: String) {
        val raised = folded.tickets.entries.firstOrNull { it.value.item == id }?.key
        val ticket = if (held.ticket) raised ?: (writer.last + 1) else null
        write(line(now, EVENT to text("held"), ID to text(id), BY to text(held.by), UNTIL to text(held.until), TICKET to ticket?.let(::JsonPrimitive),
            DEADLINE to null, keepNull = setOf(DEADLINE)))
        if (raised == null && ticket != null && held.messageClass == MessageClass.B) notify(ticket, id, now)
    }

    /**
     * What a judgement of a written message decided, written — the refusal, or the delivery and its transitions — and
     * then acted on: effects, notification, wakes, what the robots hear. A message let go after a robot held it keeps
     * the ticket its hold raised, and its deadline is counted from now (contracts.md §2.2, Amendment 8).
     */
    private fun settle(message: Message, decision: Decision, now: String, arrival: Reaction? = null): Answer {
        val id = message.id.value
        if (decision.outcome == Outcome.REFUSE) {
            // `refusals.today` is a seat's (contracts.md §4.4): Bora's, a robot's and the mailroom's refusals are not reported.
            val refusals = if (message.by.startsWith(SEAT_BY)) folded.refusalsOn(message.from.value, utc(now).toLocalDate().toString()) + 1 else null
            write(line(now, EVENT to text("refused"), ID to text(id), RULE to text(decision.rule), REQUIREMENT to text(decision.requirement),
                FAILED to decision.failed?.element(), OBSERVED to fields(decision.observed), REFUSALS_TODAY to refusals?.let(::JsonPrimitive)))
            // The one fact a robot triggers on that only the log changes (POOL-4): a seat's refusals today.
            refusals?.let { fact(FactRef(REFUSALS_TODAY_FACT, mapOf(SEAT to Datum.Text(message.from.value))), Datum.of(it), message.stage?.value, "refused $id") }
            return Answer(Status.REFUSED, id, rule = decision.rule, requirement = decision.requirement, failed = decision.failed, refusalsToday = refusals)
        }
        val deadline = decision.deadline.takeIf { decision.outcome == Outcome.DELIVER }
        val addressees = addressees(message)
        // A delivery to an idle seat wakes it (contracts.md §4.4, Amendment 22): the addressee's, and each seat it copies.
        val woke = addressees.filter(::awake)
        val raised = folded.tickets.entries.firstOrNull { it.value.item == id }?.key
        val ticket = raised ?: (writer.last + 1).takeIf { decision.waitsForBora }
        write(line(now, EVENT to text("delivered"), ID to text(id), TO to texts(addressees), WOKE to woke.takeIf { it.isNotEmpty() }?.let(::texts),
            CLASS to text(decision.messageClass?.name), RULE to text(decision.rule), TICKET to ticket?.let(::JsonPrimitive),
            COUNTED to counted(decision.counted), OBSERVED to fields(decision.observed), ATTESTED to decision.attested.takeIf { it.isNotEmpty() }?.let(::texts),
            ANSWERS to text(decision.answers), DEADLINE to deadline?.let(::text)))
        transitions(decision, id, now)
        effects(decision, id, now)
        if (raised == null && ticket != null && decision.messageClass == MessageClass.B) notify(ticket, id, now)
        // A wake is an operation of its own (contracts.md §4.4, Amendment 22) — unless the rules' effects already ask it.
        woke.filterNot { seat -> decision.effects.any { it is Requested.Do && it.operation == SEAT_WAKE && (it.with[SEAT] as? Datum.Text)?.value == seat } }
            .forEach { seat -> request(SEAT_WAKE, id, Datum.Fields(mapOf(SEAT to Datum.Text(seat))), now) }
        arrival?.let { carry(it, id, now) }
        hear(Trigger.Delivered(id), id, now, message.stage?.value)
        return Answer(Status.DELIVERED, id, transition = decision.rule)
    }

    /**
     * A held message is let go (contracts.md §2.2): judged now, under the state now, and delivered — or refused — like
     * an arrival whose line is already written. Let go by one robot, it may be another active robot's to keep (§8),
     * held again by it: a deploy the Deputy kept goes to QA's queue and is handed over in its turn; one QA's queue
     * kept, let go while Bora is away, goes to the Deputy — an arrival there, so the Deputy answers the asker and wakes
     * as it would have on arrival (review-015 B1). Refused when it is let go, its sender is told ([tell]).
     */
    private fun release(id: String) {
        val hold = folded.held[id] ?: return
        val message = messages.getValue(id)
        val now = now()
        val engine = Engine(rules)
        val trigger = Trigger.Arrival(message)
        val facts = table(engine.needs(folded, trigger) + hears(engine, trigger), message.stage?.value, now)
        val reaction = if (robots) engine.react(folded, trigger, facts) else null
        val other = reaction?.held?.takeIf { it.by != hold.by }
        if (reaction != null && other != null) {
            writeHeld(id, other, now)
            if (other.ticket) carry(reaction, id, now)
            return
        }
        val answer = settle(message, engine.judge(folded, trigger, facts), now)
        if (answer.status == Status.REFUSED) tell(message, answer)
    }

    /**
     * Whether [seat] is to be woken now (contracts.md §4.4, Amendment 22): a seat, idle — a live session and no turn in
     * flight, as the seats say — and no wake of it in flight.
     */
    private fun awake(seat: String): Boolean =
        seat !in PARTIES && folded.operations.none { it.op == SEAT_WAKE && (it.with.fields[SEAT] as? Datum.Text)?.value == seat } && deliveries.idle(seat)

    /** [seat], idle with unread mail, is woken with the oldest (contracts.md §4.4, Amendment 22): `seat.wake` requested. */
    private fun wakeOldest(seat: String) {
        val oldest = folded.unread(seat).firstOrNull() ?: return
        if (awake(seat)) request(SEAT_WAKE, oldest, Datum.Fields(mapOf(SEAT to Datum.Text(seat))), now())
    }

    /**
     * A refusal told later (contracts.md §5, §2.1): the sender of a held message refused when it was let go is sent a
     * `nudge` — in reply to it, with the rule and the guard that refused it, `by: mailroom`, the one message the mailroom
     * sends of itself — in place of the tool result it could not be given when it was answered `held`. It is carried
     * after the refusal's lines.
     */
    private fun tell(message: Message, refusal: Answer) {
        val input = buildJsonObject {
            put(KIND, JsonPrimitive(NUDGE))
            put(RE, JsonPrimitive(message.id.value))
            put(SUBJECT, JsonPrimitive("$NUDGE ($MAILROOM)"))
            put(POINTERS, texts(listOf("mail:${message.id.value}")))
            put(ARGS, buildJsonObject {
                put(RULE, JsonPrimitive(refusal.rule))
                put(FAILED_GUARD, JsonPrimitive(refusal.requirement))
            })
        }
        carrying += { arrive(Author(MAILROOM, MAILROOM, false), input) }
    }

    // ---- the robots (contracts.md §8)

    /**
     * A fact that changed: the guard judges its `on: fact` transitions and the robots hear it. When it triggers
     * nothing, nothing is written; otherwise its `fact` record first — about whom its parameters name, else its stage,
     * else the party its name is about, else the office — then the transitions, effects, sends and wakes it caused.
     * [always]: the record is written though it triggers nothing — `turn.blocked` at the stop gate's cap (§2.2).
     */
    private fun fact(fact: FactRef, value: Datum, stage: String?, evidence: String?, always: Boolean = false, first: ((JsonObject) -> Unit)? = null) {
        val now = now()
        val engine = Engine(rules)
        val trigger = Trigger.Changed(fact.name, fact.params, stage)
        val facts = table(engine.needs(folded, trigger) + hears(engine, trigger), stage, now, mapOf(fact to value))
        val decision = engine.judge(folded, trigger, facts)
        val reaction = if (robots) engine.react(folded, trigger, facts) else null
        val carried = first != null
        if (!always && !carried && decision.moves.isEmpty() && decision.effects.isEmpty() && reaction?.fired.isNullOrEmpty() && freed(fact to value, stage, now).isEmpty()) return
        val party = fact.name.substringBefore('.').takeIf { it in PARTIES }
        val subject = fact.params.values.joinToString(", ") { (it as? Datum.Text)?.value ?: it.json() }.ifEmpty { stage ?: party ?: office.value }
        // The record carries its parameters and its stage (contracts.md §2.2, Amendment 24): a start carries it again exactly.
        val line = line(now, EVENT to text("fact"), FACT to text(fact.name), SUBJECT to text(subject), VALUE to value.element(), EVIDENCE to text(evidence),
            PARAMS to JsonObject(fact.params.mapValues { it.value.element() }), STAGE to text(stage), keepNull = setOf(STAGE))
        if (first != null) first(line) else write(line, stage)
        val cause = fact.toString()
        record(decision, cause, now)
        reaction?.let { carry(it, cause, now) }
    }

    /** What the robots will read for [trigger], when the host is on. */
    private fun hears(engine: Engine, trigger: Trigger): List<Need> = if (robots) engine.hears(folded, trigger) else emptyList()

    /** The robots hear [trigger] — in [state], by default the state now — and what they do is carried, caused by [cause]. */
    private fun hear(trigger: Trigger, cause: String, now: String, stage: String?, state: State = folded) {
        if (!robots) return
        val engine = Engine(rules)
        carry(engine.react(state, trigger, table(engine.hears(state, trigger), stage, now)), cause, now)
    }

    /**
     * What a robot does (contracts.md §8): a wake is requested at once — `seat.wake` for its own seat, with its role,
     * mode and rule — and a send is carried after the lines being written, through the loop, from the robot's role,
     * `by: robot:<rule>`: in the same judgement, judged in turn.
     */
    private fun carry(reaction: Reaction, cause: String, now: String) {
        reaction.wakes.forEach { wake ->
            request(SEAT_WAKE, cause, Datum.Fields(mapOf(ROLE to Datum.Text(wake.role), MODE to Datum.Text(wake.mode), RULE to Datum.Text(wake.rule))), now)
        }
        reaction.sends.forEach { send ->
            val input = buildJsonObject {
                put(KIND, JsonPrimitive(send.kind))
                send.to?.let { put(TO, JsonPrimitive(it)) }
                send.re?.let { put(RE, JsonPrimitive(it)) }
                send.stage?.let { put(STAGE, JsonPrimitive(it)) }
                put(SUBJECT, JsonPrimitive("${send.kind} ($ROBOT${send.rule})"))
                put(ARGS, fields(send.args))
            }
            carrying += { arrive(Author(send.role, ROBOT + send.rule, false), input) }
        }
    }

    /**
     * What the robots keep that is let go now (contracts.md §2.2, §8): each robot that holds something is asked, with
     * the facts about the last request its queue handed over; what it lets go is delivered in order, after what the
     * judgement being written already carries — in it.
     */
    private fun letGo() = freed(null, null, now()).forEach { id -> carrying += { release(id) } }

    /**
     * What the robots would let go now, in order — with [known], a fact just reported about [stage], read where the
     * robot asks about that stage.
     */
    private fun freed(known: Pair<FactRef, Datum>?, stage: String?, now: String): List<String> {
        if (!robots) return emptyList()
        val engine = Engine(rules)
        return engine.holders(folded).flatMap { asked ->
            val about = asked.job?.let(messages::get)?.stage?.value
            val facts = table(engine.hears(folded, asked), about, now, listOfNotNull(known?.takeIf { about == stage }).toMap())
            engine.react(folded, asked, facts).released
        }
    }

    /** What the judgement requested, in its order: a declared emit is carried after the lines being written, in the same judgement. */
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
    private fun expire(id: String, standing: Expired? = null, first: ((JsonObject) -> Unit)? = null) {
        val now = now()
        val engine = Engine(rules)
        val trigger = Trigger.Expiry(id)
        val decision = engine.judge(folded, trigger, table(engine, trigger, messages[id]?.stage?.value, now))
        val report = decision.effects.filterIsInstance<Requested.Report>().firstOrNull()
        // Carried again, the `expired` line on the log stands: its ticket is the one it raised.
        val ticket = if (standing != null) standing.ticket?.longValueExact() else (writer.last + 1).takeIf { decision.waitsForBora }
        val line = line(now, EVENT to text("expired"), ID to text(id), RULE to text(decision.rule), REPORT to text(report?.to),
            CLASS to text(report?.messageClass?.name), TICKET to ticket?.let(::JsonPrimitive), DEADLINE to text(decision.deadline), keepNull = setOf(DEADLINE))
        if (first != null) first(line) else write(line)
        record(decision, id, now)
        if (ticket != null && report?.messageClass == MessageClass.B) notify(ticket, id, now)
        hear(trigger, id, now, messages[id]?.stage?.value)
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
        carrying += { arrive(Author(MAILROOM, by, false), input) }
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

    /** Writes the request, and performs it only once its judgement is on disk — closed by its `judged` line. */
    private fun request(operation: String, cause: String, with: Datum.Fields, now: String) {
        write(line(now, EVENT to text("op"), OP to text(operation), PHASE to text(REQUESTED), CAUSE to text(cause),
            WITH to with.takeIf { it.fields.isNotEmpty() }?.element()))
        world += { perform(OperationRequest(operation, cause, with)) }
    }

    /**
     * Hands the request to the performer once; a result it gives twice is taken once. A wake of a seat — `seat.wake`
     * with a `seat` — is the seats' to perform: the seat is prompted with the message that is its cause. A robot's own
     * seat woken in a mode is the performer's (S4.6).
     */
    private fun perform(request: OperationRequest) {
        val answered = AtomicBoolean(false)
        val seat = (request.with.fields[SEAT] as? Datum.Text)?.value?.takeIf { request.operation == SEAT_WAKE }
        val message = request.cause.let(messages::get)
        if (seat != null && message != null) return finished(request, deliveries.prompt(seat, message))
        operations.perform(request) { result -> if (answered.compareAndSet(false, true)) finished(request, result) }
    }

    /** A result — now, from inside [perform], or later, from the performer's own thread — arrives: it is judged in its turn. */
    @Synchronized
    private fun finished(request: OperationRequest, result: OperationResult) {
        if (closed) return
        arrivals += { complete(request, result) }
        drain()
    }

    /**
     * The result's record, then the result as a trigger for the engine (contracts.md §4.1, `on: op-result`): what it fires
     * is recorded as caused by the request. An operation that starts or re-seats a seat names it in its result.
     */
    private fun complete(request: OperationRequest, result: OperationResult, first: (JsonObject) -> Unit = { write(it) }) {
        val now = now()
        first(when (result) {
            is OperationResult.Ok -> line(now, EVENT to text("op"), OP to text(request.operation), PHASE to text(OK), CAUSE to text(request.cause),
                RESULT to seated(request, result.result).takeIf { it.fields.isNotEmpty() }?.element())
            is OperationResult.Failed -> line(now, EVENT to text("op"), OP to text(request.operation), PHASE to text(FAILED), CAUSE to text(request.cause),
                REASON to text(result.reason))
        })
        val returned = (result as? OperationResult.Ok)?.let { seated(request, it.result) } ?: Datum.Fields(emptyMap())
        // A seat woken with a message has seen it (contracts.md §2.2, `read`): its `read` record follows the wake's `ok`.
        val woken = (request.with.fields[SEAT] as? Datum.Text)?.value?.takeIf { request.operation == SEAT_WAKE && result is OperationResult.Ok }
        if (woken != null && request.cause in folded.unread(woken)) write(line(now, EVENT to text("read"), SEAT to text(woken), IDS to texts(listOf(request.cause))))
        val trigger = Trigger.Result(request.cause, request.operation, result is OperationResult.Ok, request.with, returned)
        val engine = Engine(rules)
        val stage = messages[request.cause]?.stage?.value
        record(engine.judge(folded, trigger, table(engine, trigger, stage, now)), request.cause, now)
        hear(trigger, request.cause, now, stage)
        // A seat still idle after it was prompted — one whose turn ends at once — is woken with its next unread message.
        woken?.let(::wakeOldest)
    }

    /**
     * At start, each operation the fold says is in flight — requested, with no result — is recorded `failed` with the
     * reason `interrupted` and judged as that result (contracts.md §4.4, §7). It is not performed again: a retry is
     * another firing of its kind.
     */
    private fun interrupted(flights: List<InFlight>) = flights.forEach { flight ->
        judged { complete(OperationRequest(flight.op, flight.cause, flight.with), OperationResult.Failed(INTERRUPTED)) }
    }

    // ---- a start (contracts.md §3, Amendments 23 and 24)

    /**
     * The start's `rules` record, and the judgement the log ends in that no `judged` line closes — what a stop in the
     * middle of writing it left. With none, the `rules` record is a judgement of its own. Otherwise that judgement gets
     * a `voided` record, from its first line to the last on disk: its other lines count for nothing. The `rules` record
     * follows it, in the judgement it opens, and its first line stands and is carried again there, as if it had just
     * arrived — a message, an operation's result, an expiry, a fact — judged under the rules that record names. A first
     * line that was lost, or one that is no arrival (a `rules` or `read` record, a request), has nothing more to carry.
     * Returns the operations in flight once the `voided` record is written — a standing request among them, a request
     * a standing result ended not — before the judgement it opens requests any: those the start records `failed`,
     * `interrupted` (contracts.md §7).
     */
    private fun unfinished(lines: List<LogLine>, first: Long): List<InFlight> {
        if (folded.opened == null) return folded.operations.also { judged { recordRules() } }
        val standing = folded.unjudged.firstOrNull()?.takeIf { folded.opened == first }
        val voided = line(now(), EVENT to text("voided"), FIRST to JsonPrimitive(first), LAST to JsonPrimitive(writer.last))
        // The `voided` record goes to its first line's file, and so does the `judged` that closes the judgement it opens.
        val stage = standing?.let { line -> lines.firstOrNull { it.entry === line }?.stage }
        var flights = emptyList<InFlight>()
        val stand: (JsonObject) -> Unit = {
            write(voided, stage)
            // Nothing may come before the `voided` record in the judgement it opens; the `rules` record comes before what it carries again.
            recordRules()
            flights = folded.operations
        }
        judged {
            when (standing) {
                is Message -> arrived(standing, now()) { stand(it) }
                is Op -> if (standing.phase == REQUESTED) stand(voided) else {
                    val flight = folded.operations.firstOrNull { it.op == standing.op && it.cause == standing.cause }
                    val with = flight?.with ?: Datum.Fields(emptyMap())
                    val returned = (Datum.of(standing.plain()[RESULT] ?: emptyMap<String, Any?>()) as? Datum.Fields) ?: Datum.Fields(emptyMap())
                    complete(OperationRequest(standing.op, standing.cause, with),
                        if (standing.phase == OK) OperationResult.Ok(returned) else OperationResult.Failed(standing.reason ?: INTERRUPTED)) { stand(it) }
                }
                is Expired -> expire(standing.id, standing) { stand(it) }
                is Fact -> {
                    val (ref, about) = carriedFact(standing, stage)
                    fact(ref, Datum.of(standing.plain()[VALUE]), about, standing.evidence) { stand(it) }
                }
                else -> stand(voided)
            }
        }
        return flights
    }

    /**
     * A `fact` record read back as the fact that changed: the parameters and the stage it carries (contracts.md §2.2,
     * Amendment 24) — a record written before them names neither, and is about the stage whose file it is in.
     */
    private fun carriedFact(record: Fact, file: String?): Pair<FactRef, String?> {
        val params = (record.plain()[PARAMS] as? Map<*, *>).orEmpty().entries.associate { (name, value) -> name as String to Datum.of(value) }
        return FactRef(record.fact, params) to (record.stage ?: file)
    }

    /** `seat.start` and `seat.reseat` are `ok(seat)` (contracts.md §4.4, Amendment 13): the result names the seat, the one it was asked for when the performer did not. */
    private fun seated(request: OperationRequest, result: Datum.Fields): Datum.Fields {
        val seat = request.with.fields[SEAT]
        return if (request.operation !in SEATING || seat == null || SEAT in result.fields) result else Datum.Fields(result.fields + (SEAT to seat))
    }

    /**
     * One judgement (contracts.md §3, Amendment 23): [arrival] writes its first line and what it decides; then what it
     * carries — the messages the rules and the robots send in answer, each judged in turn, and what the robots let go —
     * until nothing is left; then the `judged` line, and the lines reach the disk together. An arrival that writes
     * nothing makes no judgement.
     */
    private fun <T> judged(arrival: () -> T): T {
        check(!judging) { "a judgement is being written" }
        judging = true
        try {
            val result = arrival()
            if (judgement != null) {
                while (true) {
                    if (carrying.isEmpty()) letGo()
                    if (carrying.isEmpty()) break
                    carrying.removeFirst()()
                }
                val judged = judgement!!.close(now())
                judgement = null
                take(listOf(judged))
            }
            return result
        } finally {
            judging = false
        }
    }

    /**
     * Turns to the world once the judgements are on disk: performs what they requested and wakes whom they woke, in
     * order; then judges each result that arrived, a judgement of its own; until nothing is left.
     */
    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (!closed) {
                when {
                    world.isNotEmpty() -> world.removeFirst()()
                    arrivals.isNotEmpty() -> judged(arrivals.removeFirst())
                    else -> break
                }
            }
        } finally {
            draining = false
        }
    }

    /**
     * The message as the mailroom writes it (contracts.md §2.1): from the input only what a sender supplies — `kind`,
     * `to`, `stage`, `subject`, `body`, `pointers`, `args`, `re`; `id`, `thread`, `at`, `created`, `from`, `by` and
     * `signed` set here. A reply's thread, stage and addressee default to the message it answers'. An addressee that
     * cannot be resolved — a `to`, the sender's or a seat kind's `args.seat`, that is not a string naming a party; a
     * `{re: from}` kind with no known `re`; a seat kind with no seat — is written `to: mailroom`, and the judgement
     * refuses it: nothing is thrown at a sender (contracts.md §3).
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
        val to = partyName(field(TO) ?: declared?.to?.let { destination ->
            when (destination) {
                is Destination.Parties -> JsonPrimitive(destination.names.first())
                is Destination.Reply -> re?.from?.value?.let(::JsonPrimitive)
                Destination.AnySeat -> args?.get(SEAT)
            }
        }) ?: MAILROOM
        val subject = string(SUBJECT) ?: re?.subject?.let { "re: $it".take(SUBJECT_LIMIT) } ?: kind
        return Message.read(line(now,
            ID to text(id), THREAD to text(re?.thread?.value ?: id), RE to field(RE), FROM to text(author.from), TO to text(to),
            CC to declared?.cc?.takeIf { it.isNotEmpty() }?.let(::texts), KIND to text(kind), STAGE to (field(STAGE) ?: re?.stage?.value?.let(::text)),
            SUBJECT to text(subject), BODY to field(BODY), POINTERS to field(POINTERS)?.jsonArray, ARGS to args, CREATED to text(now),
            SIGNED to JsonPrimitive(true).takeIf { author.signed }, BY to text(author.by),
        ).let { JsonObject(it + (SEQ to JsonPrimitive(seq))) })
    }

    /** The facts the engine names for [trigger], collected — `now` from the clock, the others from the world. */
    private fun table(engine: Engine, trigger: Trigger, stage: String?, now: String): Facts = table(engine.needs(folded, trigger), stage, now)

    /** [needs] collected: what is [known] already, `now` from the clock, the others from the world about [stage]. */
    private fun table(needs: List<Need>, stage: String?, now: String, known: Map<FactRef, Datum> = emptyMap()): Facts =
        Facts.collect(needs) { fact -> known[fact] ?: if (fact.name == NOW) Datum.Text(now) else facts.observe(fact, stage) }

    /**
     * One `transition` record per machine the decision moves, caused by [cause]. A move of Bora's presence is heard by
     * the robots as they were before it (contracts.md §8 `{presence: …}`: the Deputy hears Bora's return).
     */
    private fun transitions(decision: Decision, cause: String, now: String) = decision.moves.forEach { move ->
        val before = folded
        write(line(now, EVENT to text("transition"), MACHINE to text(move.machine), SUBJECT to text(move.subject), FROM to text(move.from),
            TO to text(move.to), RULE to text(move.rule), CAUSE to text(cause), COUNTED to counted(move.counted)))
        if (move.machine == PRESENCE) hear(Trigger.Presence(move.to), cause, now, null, before)
    }

    // ---- the log

    /**
     * Adds one line to the judgement being written — for the file of the stage it is about, a message to its own, or
     * the office's file when it is about no stage, or its stage's file is closed (contracts.md §3: the writer's to
     * place); then folds it. Closing a file takes nothing out of the state. The line reaches the disk with its
     * judgement's `judged` line, and nothing is acted on before.
     */
    private fun write(draft: JsonObject, about: String? = if (draft[KIND] != null) null else about(draft)): Envelope {
        check(judging) { "a line is written in a judgement" }
        val expected = writer.last + 1
        val stage = about?.let(::StageId)
        val written = (judgement ?: writer.judgement().also { judgement = it }).add(draft, stage)
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

    /**
     * Folds [lines] into the state and keeps their messages, and to whom each was delivered. The mailroom's own lines
     * count as it writes them ([asWritten]): its next decision in the judgement reads them. The log read at a start-up
     * is folded as any reader folds it — whole judgements only — and what it leaves open is the start's to settle.
     */
    private fun take(lines: List<Envelope>, asWritten: Boolean = true, counts: (Envelope) -> Boolean = { true }) {
        lines.forEach { line ->
            if (line is Message) messages[line.id.value] = line
            if (line is Delivered && counts(line)) line.to.forEach { addressed.getOrPut(it, ::ArrayList) += line.id }
        }
        folded = fold(lines, folded).let { if (asWritten) it.settled() else it }
    }

    private fun now(): String = clock.now().truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {
        /**
         * Opens the log, folds it, puts the rules in force and records what start-up records (contracts.md §2.2, §4.4).
         * The writer reaches the disk through [disk].
         */
        fun start(
            project: Path, office: OfficeId, rules: RuleTexts, facts: FactSource, operations: Operations, deliveries: Deliveries, clock: Clock,
            robots: Boolean = false, disk: Channels = Channels.files,
        ): Mailroom {
            val writer = LogWriter.open(project, office, disk)
            try {
                val read = LogReader(project, office).read()
                if (read.problems.isNotEmpty()) throw MailroomRefusal("the log has problems:\n" + read.problems.joinToString("\n"))
                val (inForce, loaded) = InForce.load(rules.read())
                if (inForce == null) throw MailroomRefusal("the rules are not valid:\n" + loaded.problems.joinToString("\n"))
                // Under the mailroom's lock, as every other step: a performer that answers from its own thread while the
                // start is carrying its queue waits for it (issue #21).
                return Mailroom(office, facts, operations, deliveries, clock, writer, rules, inForce, robots).apply {
                    synchronized(this) {
                        val entries = read.lines.map { it.entry }
                        // The judgement the log ends in begins after the last `judged` line — at the `voided` record after it, when
                        // a start that carried a first line again was stopped too. What counts for nothing: the lines a `voided`
                        // record lets go, and those of the judgement this start voids but its first.
                        val lastJudged = entries.filterIsInstance<Judged>().maxOfOrNull { it.seq.longValueExact() } ?: 0L
                        val first = entries.filterIsInstance<Voided>().lastOrNull { it.seq.longValueExact() > lastJudged }?.seq?.longValueExact() ?: (lastJudged + 1)
                        val voided = entries.filterIsInstance<Voided>().map { (it.first + 1)..it.last } + listOf((first + 1)..writer.last)
                        take(entries, asWritten = false) { line -> voided.none { line.seq.longValueExact() in it } }
                        val flights = unfinished(read.lines, first)
                        interrupted(flights.filter { flight -> folded.operations.any { it === flight } })
                        drain()
                        // A start wakes no one again (contracts.md §4.4); a live seat idle with unread mail is woken with the oldest.
                        deliveries.live().sorted().forEach { seat -> idle(seat) }
                    }
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

/** [element] as a message's `to` writes it (contracts.md §2.1) — a string naming a party; null when it is no such string. */
private fun partyName(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf { runCatching { Party(it) }.isSuccess }

private const val BORA = "bora"
private const val MAILROOM = "mailroom"
/** The kind that tells a sender of a refusal, and the guard it names (`message-kinds.yaml`: `nudge`). */
private const val NUDGE = "nudge"
private const val FAILED_GUARD = "failed_guard"
/** A signed command: the one way Bora acts (contracts.md §6). */
private const val BORA_ACTED = "bora.acted"
private const val SIGNED_COMMAND = "signed command"
private const val REFUSALS_TODAY_FACT = "refusals.today"
/** The `by` of a seat's own message (contracts.md §2.1): `seat:<name>`. */
private const val SEAT_BY = "seat:"
private const val PRESENCE = "presence"
private const val SEAT_WAKE = "seat.wake"
private const val ROBOT = "robot:"
private const val ROLE = "role"
private const val MODE = "mode"
private const val UNTIL = "until"
private const val FACT = "fact"
private const val VALUE = "value"
private const val EVIDENCE = "evidence"
private const val SEAT_NAME = "seat.name"
/** The fact the stop gate's cap writes (contracts.md §2.2, §4.4, §11, Amendment 20) — the seat API's, not the driver's — and what shows it. */
private const val TURN_BLOCKED = "turn.blocked"
private const val AT_THE_CAP = "engagement.yaml:stop_gate — at its cap the turn ended"
private const val NOTIFY_BORA = "notify.bora"
private const val REQUESTED = "requested"
private const val OK = "ok"
private const val OF = "of"
private const val ITEM = "item"
private const val INTERRUPTED = "interrupted"
private const val FIRST = "first"
private const val LAST = "last"
/** A `fact` record's parameters (contracts.md §2.2, Amendment 24). */
private const val PARAMS = "params"
/** The cause of the `notify.bora` an invalid reload requests, and where its problems go (contracts.md §2.2). */
private const val RULES = "rules"
private const val PROBLEMS = "problems"
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
