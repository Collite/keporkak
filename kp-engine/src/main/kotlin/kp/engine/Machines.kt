package kp.engine

import kp.rules.Machine
import kp.rules.RuleSet
import kp.rules.Transition

/** The machines by their names in `stage-lifecycle.yaml`. */
internal const val STAGE_MACHINE = "stage"
internal const val SEAT_MACHINE = "seat"
internal const val PHASE_MACHINE = "phase"
internal const val PRESENCE_MACHINE = "presence"
internal const val FLAG_MACHINE = "flag"
private const val FOUND_RULES = "found_item"
private const val FLAG_RULES = "flag_thread"
private const val FOUND = "found"

/** Reserved state names (contracts.md §4.1). */
private const val ANY = "*"
private const val ACTIVE = "*active"
internal const val PREVIOUS = "*previous"

/**
 * The stage machine and its five companions as the state holds them: by the name a `transition` record gives the
 * machine (contracts.md §2.2), per subject. A subject with no record yet is in its machine's first state.
 */
internal class Machines(private val rules: RuleSet, private val state: State) {
    /** The record's name for a machine: `found_item` is `found`, `flag_thread` is `flag`. */
    fun recordName(machine: String): String = when (machine) {
        FOUND_RULES -> FOUND
        FLAG_RULES -> FLAG_MACHINE
        else -> machine
    }

    private fun byRecordName(name: String): Machine? = rules.lifecycle.machines.values.firstOrNull { recordName(it.name) == name }

    fun state(recordName: String, subject: String): String? =
        state.machine(recordName, subject)?.state ?: byRecordName(recordName)?.states?.keys?.firstOrNull()

    /** Whether any transition of the machine leaves [from]: an open state. */
    fun leaves(recordName: String, from: String?): Boolean = byRecordName(recordName)?.transitions?.any { it.from == from } == true

    /**
     * Whom a machine's transition moves (contracts.md §2.2): the stage of the message; the Found line it names
     * (`args.found`); its thread; the seat it names (`args.seat`), else each seat bound to the message's stage; the
     * stage's phase; Bora's presence. Empty: this trigger moves no subject of that machine.
     */
    fun subjects(machine: Machine, context: Context): List<String> {
        val stage = context.stage
        fun arg(name: String) = (context.args.fields[name] as? Datum.Text)?.value
        return when (recordName(machine.name)) {
            STAGE_MACHINE -> listOfNotNull(stage)
            FOUND -> listOfNotNull(stage?.let { at -> arg(FOUND)?.let { "$at#$it" } })
            FLAG_MACHINE -> listOfNotNull(context.message?.thread)
            SEAT_MACHINE -> arg(SEAT_MACHINE)?.let(::listOf) ?: stage?.let(::seatsOf).orEmpty()
            PHASE_MACHINE -> listOfNotNull(stage?.let(::phaseOf))
            PRESENCE_MACHINE -> listOf(BORA)
            else -> emptyList()
        }
    }

    /**
     * The seats bound to [stage], in the order the log first moved them: each whose last move a delivered message of
     * the stage caused — that message's own transition, or the result of an operation it requested (a `transition`
     * record's `cause` is the message in both). A seat brought up for the stage, given its work or brought back in it
     * is one — a reviewer's seat too; a seat whose last move was about another stage, or that no message moved, is not.
     */
    private fun seatsOf(stage: String): List<String> = state.machines[SEAT_MACHINE].orEmpty()
        .filterValues { seat -> state.delivered[seat.cause]?.stage == stage }.keys.toList()

    /** The candidates (contracts.md §4.1): this machine's transitions for the trigger, from the subject's state, in file order. */
    fun candidates(machine: Machine, subject: String, context: Context): List<Transition> {
        val current = state(recordName(machine.name), subject)
        return machine.transitions.filter { transition ->
            transition.on == context.event && from(transition.from, current) && when (context.event) {
                kp.rules.Event.FACT -> transition.kind == null && transition.require.any { (it as? kp.rules.Requirement.Fact)?.fact?.name == context.changed }
                else -> transition.kind == context.kind
            }
        }
    }

    private fun from(from: String, current: String?): Boolean = from == current || from == ANY || (from == ACTIVE && current in rules.lifecycle.activeStates)

    /** `*previous` is the state the subject was in before it entered its present one. */
    fun destination(transition: Transition, machine: Machine, subject: String): String? =
        if (transition.to == PREVIOUS) state.machine(recordName(machine.name), subject)?.previous else transition.to
}
