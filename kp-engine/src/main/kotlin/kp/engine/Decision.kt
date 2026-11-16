package kp.engine

import kp.rules.MessageClass

/** What a judgement comes to. A message is delivered or refused; any other trigger fires something or nothing. */
enum class Outcome { DELIVER, REFUSE, FIRE, NOTHING }

/**
 * One machine's move, as its `transition` record needs it (contracts.md §2.2): the machine by its record name, the
 * subject, the states, the rule, and the new values of the counters its effects moved.
 */
data class Move(val machine: String, val subject: String, val from: String, val to: String, val rule: String, val counted: Map<String, Long> = emptyMap())

/** An effect the judgement requests, its `if` already decided (contracts.md §4.1, §4.2). Values are resolved. */
sealed interface Requested {
    /** The transition or expiry that requested it. */
    val rule: String
    data class Emit(override val rule: String, val kind: String, val to: String?, val args: Map<String, Datum>) : Requested
    data class Do(override val rule: String, val operation: String, val with: Map<String, Datum>) : Requested
    /** An item in Bora's inbox: the `expired` record that carries `report: bora`. */
    data class Report(override val rule: String, val to: String, val messageClass: MessageClass) : Requested
}

/**
 * The engine's judgement. Only `kp-engine` constructs one (architecture.md §2): it carries everything the `delivered`
 * or `refused` record and the `transition` records need, and the effects requested.
 */
class Decision private constructor(
    val outcome: Outcome,
    /** The transition fired (the stage machine's first), or the rule that refused: a transition id or a kind's name. */
    val rule: String?,
    /** A refusal's requirement, in the rule file's own prose (contracts.md §4: what a refusal quotes). */
    val requirement: String?,
    /**
     * The first requirement that failed, in its file form — the `refused` record's `failed` (contracts.md §2.2). Null
     * when what failed is one the engine enforces for every kind with no requirement written: [requirement] names it.
     */
    val failed: Datum?,
    /** Facts the judgement read that the table did not supply: unknown, so what read them failed. */
    val unsupplied: List<FactRef>,
    val moves: List<Move>,
    /** The new values of every counter the judgement moved. */
    val counted: Map<String, Long>,
    val effects: List<Requested>,
    val messageClass: MessageClass?,
    /** Class B: the outcome waits for Bora (a ticket) — unless it is Bora's own message (contracts.md §2.2). */
    val waitsForBora: Boolean,
    /**
     * The deadline a delivery sets, when its kind has one. For an expiry: the wait's new deadline when it goes on, null
     * when it is over (the `expired` record's `deadline`, contracts.md §2.2).
     */
    val deadline: String?,
    /** Every fact the judgement read, by [FactRef]'s written form; a fact not supplied is [UNKNOWN_FACT]. */
    val observed: Map<String, Datum>,
    /** What the sender attested (`args.attest`). */
    val attested: List<String>,
    /**
     * The waiting message this delivery answers — the `delivered` record's `answers` (contracts.md §2.2, §4.2): the
     * one its `re` names, when that message waits and its kind's `replies` lists this kind.
     */
    val answers: String?,
) {
    override fun toString(): String =
        "Decision($outcome, rule=$rule, requirement=$requirement, failed=$failed, unsupplied=$unsupplied, moves=$moves, counted=$counted, " +
            "effects=$effects, class=$messageClass, waitsForBora=$waitsForBora, deadline=$deadline, observed=$observed, attested=$attested, answers=$answers)"

    internal companion object {
        @JvmOverloads
        fun create(
            outcome: Outcome = Outcome.NOTHING,
            rule: String? = null,
            requirement: String? = null,
            failed: Datum? = null,
            unsupplied: List<FactRef> = emptyList(),
            moves: List<Move> = emptyList(),
            counted: Map<String, Long> = emptyMap(),
            effects: List<Requested> = emptyList(),
            messageClass: MessageClass? = null,
            waitsForBora: Boolean = false,
            deadline: String? = null,
            observed: Map<String, Datum> = emptyMap(),
            attested: List<String> = emptyList(),
            answers: String? = null,
        ): Decision = Decision(outcome, rule, requirement, failed, unsupplied, moves, counted, effects, messageClass, waitsForBora, deadline, observed, attested, answers)
    }
}

/** How [Decision.observed] writes a fact the table did not supply. */
val UNKNOWN_FACT: Datum = Datum.Fields(mapOf("unknown" to Datum.Text("not supplied")))
