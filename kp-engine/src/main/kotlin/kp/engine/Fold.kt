package kp.engine

import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Held
import kp.model.Message
import kp.model.Op
import kp.model.Read
import kp.model.Refused
import kp.model.Rules
import kp.model.Transition
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

/** An item that waits for Bora: the message it is about, the record that raised it, and Bora's answer once given. */
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
 */
fun fold(lines: Iterable<Envelope>, from: State = State()): State = lines.fold(from) { state, line ->
    val seq = line.seq.longValueExact()
    if (seq <= state.seq) state else Folding(state).apply(line).done(seq, line.at.value)
}

/**
 * One line's changes to the state. What ends a wait, from the records alone (contracts.md §2.2): the `delivered`
 * record that `answers` it — which also answers its ticket; an `ok` or `failed` operation the message caused, for a
 * request to the daemon; an expiry without a new deadline. A wait's deadline is the last one a line about it carries.
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
                s = s.copy(
                    pending = s.pending - line.id,
                    held = s.held - line.id,
                    unread = s.unread + line.to.associateWith { party -> s.unread(party) + line.id },
                )
            }
            is Refused -> {
                s.pending[line.id]?.let { message ->
                    val day = utc(at).toLocalDate().toString()
                    val earlier = s.refusals[message.from]?.takeIf { it.day == day }?.count ?: 0L
                    s = s.copy(refusals = s.refusals + (message.from to Refusals(day, line.refusalsToday?.longValueExact() ?: earlier.inc())))
                }
                s = s.copy(pending = s.pending - line.id, held = s.held - line.id)
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
                s = if (line.phase == REQUESTED) s.copy(operations = s.operations + InFlight(line.op, line.cause, Datum.of(line.plain()[WITH] ?: emptyMap<String, Any?>()) as Datum.Fields, at))
                else s.copy(deadlines = s.deadlines - line.cause, operations = s.operations.minusFirst { it.op == line.op && it.cause == line.cause })
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

    private fun raise(ticket: java.math.BigInteger?, item: String, event: String, at: String) {
        val number = ticket?.longValueExact() ?: return
        if (number !in s.tickets) s = s.copy(tickets = s.tickets + (number to Ticket(item, event, at)))
    }

    /** A ticket is answered once: by the first `delivered` record that `answers` its item. */
    private fun answer(item: String, by: String) {
        s = s.copy(tickets = s.tickets.mapValues { (_, ticket) -> if (ticket.item == item && ticket.answeredBy == null) ticket.copy(answeredBy = by) else ticket })
    }

    private fun count(subject: String, counted: Map<String, java.math.BigInteger>?) {
        if (counted.isNullOrEmpty()) return
        val values = counted.mapValues { it.value.longValueExact() }
        s = s.copy(counters = s.counters + (subject to (s.counters[subject].orEmpty() + values)))
    }

    fun done(seq: Long, at: String): State = s.copy(seq = seq, at = at)
}

private const val SEAT_START = "seat.start"
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

