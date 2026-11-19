package kp.engine

import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Held
import kp.model.Judged
import kp.model.Message
import kp.model.Op
import kp.model.Read
import kp.model.Refused
import kp.model.Rules
import kp.model.Transition
import kp.model.Voided
import kp.model.plain
import kp.model.utc

/** A machine's subject's current state; [previous] is the state before it (what `*previous` names). */
data class MachineState(val state: String, val previous: String?, val since: String, val cause: String, val rule: String?)

/**
 * A message that waits for an answer by its [deadline]: the last one any line about the message carries (contracts.md
 * §2.2) — null while it is held, which suspends it.
 */
data class Wait(val kind: String, val stage: String?, val from: String, val to: String, val deadline: String?, val expiries: Int)

/** An operation requested and not yet `ok` or `failed`: which, for which message ([cause]), with what, since when. */
data class InFlight(val op: String, val cause: String, val with: Datum.Fields, val since: String)

/** A held message: which robot held it, what releases it, since when. */
data class Hold(val by: String, val until: String, val since: String)

/**
 * An item that waits for Bora: the message it is about, the record that raised it, and Bora's answer once given — for a
 * ticket raised when a message was held, the message itself once it is let go and waits no more (contracts.md §2.2).
 */
data class Ticket(val item: String, val raisedBy: String, val at: String, val answeredBy: String? = null)

/** A seat's refusals on one UTC day, as its last refusal that day counted them. */
data class Refusals(val day: String, val count: Long)

/** A message written and not yet delivered or refused — what its records need to know of it. */
data class Pending(
    val kind: String, val from: String, val to: String, val stage: String?, val thread: String, val re: String?, val deadline: String?,
    val by: String = "", val args: Datum.Fields = Datum.Fields(emptyMap()), val signed: Boolean = false,
)

/**
 * A delivered message, as the engine reads it back: what `log.count`, `request.open`, a `{re: …}` requirement and a
 * kind's `max_fires` window count or compare. [at] is when it was delivered; [messageClass] the class it went under.
 */
data class Delivery(
    val id: String, val kind: String, val from: String, val to: String, val stage: String?, val thread: String, val re: String?,
    val by: String, val args: Datum.Fields, val messageClass: String, val at: String, val signed: Boolean = false,
)

/**
 * The state the mailroom works from — a fold of the log (architecture.md §3). It applies recorded outcomes and
 * never consults a rule; every time in it is taken from a line's `at`.
 */
data class State(
    /** The `seq` of the last line folded; a line at or below it has been folded already. */
    val seq: Long = 0,
    val at: String? = null,
    /** The hash of the rule set in force: the last `rules` record. */
    val rules: String? = null,
    /** machine → subject → state; the machines are `stage` and its five companions. */
    val machines: Map<String, Map<String, MachineState>> = emptyMap(),
    /** subject → counter → value: the last value a record carried. */
    val counters: Map<String, Map<String, Long>> = emptyMap(),
    /** Messages on the log whose delivery or refusal is not (yet) recorded, by id. */
    val pending: Map<String, Pending> = emptyMap(),
    /** Open waits by message id: delivered or held, a deadline, and no answer or final expiry yet. */
    val deadlines: Map<String, Wait> = emptyMap(),
    val held: Map<String, Hold> = emptyMap(),
    val tickets: Map<Long, Ticket> = emptyMap(),
    /** party → ids delivered to it and in no `read` record of it, in delivery order. */
    val unread: Map<String, List<String>> = emptyMap(),
    /** thread → its delivered messages. */
    val hops: Map<String, Int> = emptyMap(),
    /** seat → its refusals on the day of its last refusal. */
    val refusals: Map<String, Refusals> = emptyMap(),
    /** Every delivered message, by id, in delivery order. */
    val delivered: Map<String, Delivery> = emptyMap(),
    /** The ids of delivered messages a later `delivered` record `answers` (contracts.md §2.2, Amendment 8). */
    val answered: Set<String> = emptySet(),
    /** seat → when its last `seat.start` ended `ok`: where its `session` window begins (contracts.md §4.2). */
    val sessions: Map<String, String> = emptyMap(),
    /** The operations in flight, in the order they were requested (contracts.md §4.4). */
    val operations: List<InFlight> = emptyList(),
    /**
     * role → mode → how many messages had been delivered when that robot's own seat was last woken in that mode: a
     * wake is an `op` `seat.wake` with its `role` and `mode`, counted when it is `ok` (`log.count … since: {wake: …}`).
     */
    val wakes: Map<String, Map<String, Int>> = emptyMap(),
    /**
     * The lines read of the judgement no `judged` line has closed yet (contracts.md §3, Amendment 23), in order: none is
     * applied until it is. The first stands for the judgement's first line — when a `voided` record opened it, the line
     * that record carries again.
     */
    val unjudged: List<Envelope> = emptyList(),
    /** The `seq` of the first line on disk of the judgement [unjudged] holds; null when none is open. */
    val opened: Long? = null,
) {
    fun stage(id: String): String? = machine("stage", id)?.state
    fun machine(machine: String, subject: String): MachineState? = machines[machine]?.get(subject)
    fun presence(subject: String): String? = machine("presence", subject)?.state
    fun counter(subject: String, name: String): Long = counters[subject]?.get(name) ?: 0L
    fun unread(seat: String): List<String> = unread[seat].orEmpty()
    fun openTickets(): Map<Long, Ticket> = tickets.filterValues { it.answeredBy == null }
    fun hops(thread: String): Int = hops[thread] ?: 0
    /** The seat's refusals on [day] (UTC, `yyyy-mm-dd`); the state keeps only the day of its last refusal. */
    fun refusalsOn(seat: String, day: String): Long = refusals[seat]?.takeIf { it.day == day }?.count ?: 0L
}

/**
 * Folds [lines], in `seq` order, onto [from]. A line whose `seq` is not above the state's has been folded already
 * and is skipped, so folding the same lines twice changes nothing.
 *
 * A judgement is applied when its `judged` line is read (contracts.md §2.2, §3): until then its lines wait in
 * [State.unjudged]. A `voided` record keeps the judgement's first line, which it carries again in the judgement it
 * opens, and lets the others go — they count for nothing; a judgement whose first line is not among them leaves
 * nothing. A hand log has neither record: each of its lines is a judgement of its own, applied as it is read.
 */
fun fold(lines: Iterable<Envelope>, from: State = State()): State = lines.fold(from) { state, line ->
    val seq = line.seq.longValueExact()
    if (seq <= state.seq) state else state.read(line, seq).copy(seq = seq, at = line.at.value)
}

/**
 * The state with the open judgement's lines applied as they were written — what the writer of that judgement reads
 * while it writes it, and what a re-judging reads before each line (`kp check`). A whole log needs none of it.
 */
fun State.settled(): State = unjudged.fold(copy(unjudged = emptyList(), opened = null)) { state, line -> Folding(state).apply(line).state }

private fun State.read(line: Envelope, seq: Long): State = when {
    line is Judged -> settled()
    line is Voided -> copy(unjudged = listOfNotNull(unjudged.firstOrNull()?.takeIf { opened == line.first }), opened = seq)
    line is Message && line.by.startsWith(HAND) -> Folding(this).apply(line).state
    else -> copy(unjudged = unjudged + line, opened = opened ?: seq)
}

/**
 * One line's changes to the state. What ends a wait, from the records alone (contracts.md §2.2): the `delivered`
 * record that `answers` it — which also answers its ticket; an `ok` or `failed` operation the message caused, for a
 * request to the daemon; an expiry without a new deadline. A wait's deadline is the last one a line about it carries.
 * A ticket is also answered by any signed delivery whose `re` names its item (Amendment 13).
 */
private class Folding(state: State) {
    private var s = state

    fun apply(line: Envelope): Folding = apply {
        val at = line.at.value
        when (line) {
            is Message -> {
                val args = Datum.of(line.plainArgs) as Datum.Fields
                s = s.copy(pending = s.pending + (line.id.value to Pending(line.kind, line.from.value, line.to.value, line.stage?.value, line.thread.value, line.re?.value, line.deadline?.value, line.by, args, line.signed)))
            }
            is Transition -> {
                val before = s.machine(line.machine, line.subject)
                val previous = if (line.from != line.to) line.from else before?.previous
                val moved = MachineState(line.to, previous, at, line.cause, line.rule)
                s = s.copy(machines = s.machines + (line.machine to (s.machines[line.machine].orEmpty() + (line.subject to moved))))
                count(line.subject, line.counted)
            }
            is Delivered -> {
                val message = s.pending[line.id]
                message?.stage?.let { count(it, line.counted) }
                raise(line.ticket, line.id, line.event, at)
                if (message != null) {
                    val delivery = Delivery(line.id, message.kind, message.from, message.to, message.stage, message.thread, message.re, message.by, message.args, line.deliveryClass, at, message.signed)
                    s = s.copy(delivered = s.delivered + (line.id to delivery))
                    wait(line.id, message, if (line.carriesDeadline) Carried(line.deadline?.value) else null)
                    s = s.copy(hops = s.hops + (message.thread to s.hops(message.thread).inc()))
                }
                line.answers?.let { answered ->
                    s = s.copy(answered = s.answered + answered, deadlines = s.deadlines - answered)
                    answer(answered, line.id)
                }
                // Bora answers an item by any signed delivery whose `re` names it (Amendment 13): an item that does not wait too.
                message?.takeIf { it.signed }?.re?.let { answer(it, line.id) }
                // The ticket raised when a message was held closes when it is let go (contracts.md §2.2, Amendments 21, 23): it no
                // longer waits — unless its delivery waits for Bora in its own right: a class-B item that is not his own message.
                if (line.id in s.held && !(line.deliveryClass == CLASS_B && message?.from != BORA)) answer(line.id, line.id)
                s = s.copy(
                    pending = s.pending - line.id,
                    held = s.held - line.id,
                    unread = s.unread + line.to.associateWith { party -> s.unread(party) + line.id },
                )
            }
            is Refused -> {
                // `refusals.today` is a seat's (contracts.md §4.4): a refusal of Bora's message, or of a robot's, is counted for no one.
                s.pending[line.id]?.takeIf { it.by.startsWith(SEAT_BY) }?.let { message ->
                    val day = utc(at).toLocalDate().toString()
                    val earlier = s.refusals[message.from]?.takeIf { it.day == day }?.count ?: 0L
                    s = s.copy(refusals = s.refusals + (message.from to Refusals(day, line.refusalsToday?.longValueExact() ?: earlier.inc())))
                }
                // A held message refused when it is let go waits no more, and the ticket raised when it was held closes (contracts.md §2.2, Amendment 21).
                if (line.id in s.held) answer(line.id, line.id)
                s = s.copy(pending = s.pending - line.id, held = s.held - line.id, deadlines = s.deadlines - line.id)
            }
            is Held -> {
                s = s.copy(held = s.held + (line.id to Hold(line.by, line.until, at)))
                raise(line.ticket, line.id, line.event, at)
                s.pending[line.id]?.let { wait(line.id, it, if (line.carriesDeadline) Carried(line.deadline?.value) else null) }
            }
            is Expired -> {
                raise(line.ticket, line.id, line.event, at)
                val next = line.deadline?.value
                val open = s.deadlines[line.id]
                s = when {
                    next == null -> s.copy(deadlines = s.deadlines - line.id, held = s.held - line.id, pending = if (line.id in s.held) s.pending - line.id else s.pending)
                    open != null -> s.copy(deadlines = s.deadlines + (line.id to open.copy(deadline = next, expiries = open.expiries.inc())))
                    else -> s
                }
            }
            is Op -> {
                if (line.op == SEAT_WAKE && line.phase == OK) s.operations.firstOrNull { it.op == line.op && it.cause == line.cause }?.let(::woken)
                // A wake is no operation its message asked for (contracts.md §2.2, §4.4, Amendment 22): its result ends no wait.
                s = if (line.phase == REQUESTED) s.copy(operations = s.operations + InFlight(line.op, line.cause, Datum.of(line.plain()[WITH] ?: emptyMap<String, Any?>()) as Datum.Fields, at))
                else s.copy(deadlines = if (line.op == SEAT_WAKE) s.deadlines else s.deadlines - line.cause, operations = s.operations.minusFirst { it.op == line.op && it.cause == line.cause })
                if (line.op == SEAT_START && line.phase == OK) seatUp(line)?.let { seat -> s = s.copy(sessions = s.sessions + (seat to at)) }
            }
            is Read -> {
                val seen = line.ids.toSet()
                val left = s.unread(line.seat).filterNot { it in seen }
                s = s.copy(unread = if (left.isEmpty()) s.unread - line.seat else s.unread + (line.seat to left))
            }
            is Rules -> s = s.copy(rules = line.hash)
            else -> Unit
        }
    }

    /**
     * A delivered or held message with a deadline waits. The deadline in force is the last one a line carries:
     * [carried] — the record's own, a time or null (held: suspended) — else the wait's, else the message's.
     */
    private fun wait(id: String, message: Pending, carried: Carried?) {
        val open = s.deadlines[id]
        val deadline = if (carried != null) carried.deadline else if (open != null) open.deadline else message.deadline
        if (open == null && deadline == null && message.deadline == null) return
        s = s.copy(deadlines = s.deadlines + (id to (open?.copy(deadline = deadline) ?: Wait(message.kind, message.stage, message.from, message.to, deadline, 0))))
    }

    /** A robot's own seat woken in a mode: from here on, `log.count … since: {wake: <mode>}` counts. */
    private fun woken(wake: InFlight) {
        val role = (wake.with.fields[ROLE] as? Datum.Text)?.value ?: return
        val mode = (wake.with.fields[MODE] as? Datum.Text)?.value ?: return
        s = s.copy(wakes = s.wakes + (role to (s.wakes[role].orEmpty() + (mode to s.delivered.size))))
    }

    private fun raise(ticket: java.math.BigInteger?, item: String, event: String, at: String) {
        val number = ticket?.longValueExact() ?: return
        if (number !in s.tickets) s = s.copy(tickets = s.tickets + (number to Ticket(item, event, at)))
    }

    /** A ticket is answered once: by the first `delivered` record that `answers` its item, or that is Bora's signed reply to it. */
    private fun answer(item: String, by: String) {
        s = s.copy(tickets = s.tickets.mapValues { (_, ticket) -> if (ticket.item == item && ticket.answeredBy == null) ticket.copy(answeredBy = by) else ticket })
    }

    private fun count(subject: String, counted: Map<String, java.math.BigInteger>?) {
        if (counted.isNullOrEmpty()) return
        val values = counted.mapValues { it.value.longValueExact() }
        s = s.copy(counters = s.counters + (subject to (s.counters[subject].orEmpty() + values)))
    }

    val state: State get() = s
}

private const val HAND = "hand:"
/** The `by` of a seat's own message (contracts.md §2.1). */
private const val SEAT_BY = "seat:"
private const val CLASS_B = "B"
private const val SEAT_START = "seat.start"
private const val SEAT_WAKE = "seat.wake"
private const val ROLE = "role"
private const val MODE = "mode"
private const val REQUESTED = "requested"
private const val OK = "ok"
private const val SEAT = "seat"
private const val WITH = "with"

/** A deadline a line carries: a time, or null — which, on a `held` record, suspends the wait. */
private class Carried(val deadline: String?)

private fun <T> List<T>.minusFirst(match: (T) -> Boolean): List<T> = indexOfFirst(match).let { if (it < 0) this else take(it) + drop(it.inc()) }

/** The seat a `seat.start` brought up: its result's `seat` (`ok(seat)`, contracts.md §4.4), else the `seat` it was asked `with`. */
private fun seatUp(line: Op): String? {
    val plain = line.plain()
    fun seat(field: String) = ((plain[field] as? Map<*, *>)?.get(SEAT) as? String)
    return seat("result") ?: seat("with")
}

