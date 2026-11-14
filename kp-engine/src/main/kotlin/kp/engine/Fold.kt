package kp.engine

import kp.model.Delivered
import kp.model.Envelope
import kp.model.Message
import kp.model.Rules
import kp.model.Transition

/** A machine's subject's current state; [previous] is the state before it (what `*previous` names). */
data class MachineState(val state: String, val previous: String?, val since: String, val cause: String, val rule: String?)

/** A message that waits for an answer by its `deadline`. */
data class Wait(val kind: String, val stage: String?, val from: String, val to: String, val deadline: String, val expiries: Int)

/** A message written and not yet delivered or refused — what its records need to know of it. */
data class Pending(val kind: String, val from: String, val to: String, val stage: String?, val thread: String, val re: String?, val deadline: String?)

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
    val deadlines: Map<String, Wait> = emptyMap(),
    val unread: Map<String, List<String>> = emptyMap(),
) {
    fun stage(id: String): String? = machine("stage", id)?.state
    fun machine(machine: String, subject: String): MachineState? = machines[machine]?.get(subject)
    fun presence(subject: String): String? = machine("presence", subject)?.state
    fun counter(subject: String, name: String): Long = counters[subject]?.get(name) ?: 0L
}

/**
 * Folds [lines], in `seq` order, onto [from]. A line whose `seq` is not above the state's has been folded already
 * and is skipped, so folding the same lines twice changes nothing.
 */
fun fold(lines: Iterable<Envelope>, from: State = State()): State = lines.fold(from) { state, line ->
    val seq = line.seq.longValueExact()
    if (seq <= state.seq) state else Folding(state).apply(line).done(seq, line.at.value)
}

/** One line's changes to the state. */
private class Folding(state: State) {
    private var s = state

    fun apply(line: Envelope): Folding = apply {
        when (line) {
            is Message -> s = s.copy(pending = s.pending + (line.id.value to Pending(line.kind, line.from.value, line.to.value, line.stage?.value, line.thread.value, line.re?.value, line.deadline?.value)))
            is Transition -> {
                val before = s.machine(line.machine, line.subject)
                val previous = if (line.from != line.to) line.from else before?.previous
                val moved = MachineState(line.to, previous, line.at.value, line.cause, line.rule)
                s = s.copy(machines = s.machines + (line.machine to (s.machines[line.machine].orEmpty() + (line.subject to moved))))
                count(line.subject, line.counted)
            }
            is Delivered -> {
                val message = s.pending[line.id]
                message?.stage?.let { count(it, line.counted) }
                s = s.copy(pending = s.pending - line.id)
            }
            is Rules -> s = s.copy(rules = line.hash)
        }
    }

    private fun count(subject: String, counted: Map<String, java.math.BigInteger>?) {
        if (counted.isNullOrEmpty()) return
        val values = counted.mapValues { it.value.longValueExact() }
        s = s.copy(counters = s.counters + (subject to (s.counters[subject].orEmpty() + values)))
    }

    fun done(seq: Long, at: String): State = s.copy(seq = seq, at = at)
}
