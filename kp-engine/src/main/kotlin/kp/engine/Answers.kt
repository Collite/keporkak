package kp.engine

import kp.model.utc
import kp.rules.FactCall
import kp.rules.duration

/**
 * The facts whose source is exactly L (contracts.md §4.4): the engine answers them itself, from the folded state.
 * Null is unknown — a fact this state cannot answer.
 */
internal class Answers(private val evaluation: Evaluation) {
    private val state = evaluation.state
    private val context = evaluation.context
    private val machines = Machines(evaluation.rules, state)

    fun answer(ref: FactRef): Datum? {
        fun param(name: String): String? = (ref.params[name] as? Datum.Text)?.value
        val stage = param("stage") ?: context.stage
        val thread = param("thread") ?: context.message?.thread
        return when (ref.name) {
            "stage.state" -> stage?.let { machines.state(STAGE_MACHINE, it) }?.let(Datum::Text)
            "phase.state" -> (param("phase") ?: stage?.let(::phaseOf))?.let { machines.state(PHASE_MACHINE, it) }?.let(Datum::Text)
            "seat.state" -> param("seat")?.let { machines.state(SEAT_MACHINE, it) }?.let(Datum::Text)
            "seat.inbox_replayed" -> param("seat")?.let { Datum.Bool(state.unread(it).isEmpty()) }
            "bora.present" -> Datum.Bool(machines.state(PRESENCE_MACHINE, BORA) == PRESENT)
            "bora.absent_since" -> state.machine(PRESENCE_MACHINE, BORA)?.takeIf { it.state != PRESENT }?.let { Datum.Text(it.since) }
            "thread.hops" -> thread?.let { Datum.of(state.hops(it)) }
            "thread.state" -> thread?.let { machines.state(FLAG_MACHINE, it) }?.let(Datum::Text)
            "thread.open" -> thread?.let { Datum.Bool(machines.leaves(FLAG_MACHINE, machines.state(FLAG_MACHINE, it))) }
            "queue.names" -> stage?.let { queued(it) }
            "log.count" -> count(ref)
            "request.open" -> stage?.let { at -> param("kind")?.let { kind -> Datum.Bool(state.delivered.values.any { it.kind == kind && it.stage == at && it.id !in state.answered }) } }
            "review.open_blockers" -> stage?.let(::openBlockers)
            "refusals.today" -> param("seat")?.let { seat -> now()?.let { Datum.of(state.refusalsOn(seat, utc(it).toLocalDate().toString())) } }
            else -> null
        }
    }

    private fun now(): String? = (evaluation.fact(FactCall(NOW, emptyMap())) as? Datum.Text)?.value

    /** In a signed queue: the last message Bora's command line delivered that names stages names this one. */
    private fun queued(stage: String): Datum? {
        val queue = state.delivered.values.lastOrNull { it.by == BORA_CLI && it.args.fields[STAGES] is Datum.Items } ?: return Datum.Bool(false)
        return Datum.Bool(Datum.Text(stage) in (queue.args.fields.getValue(STAGES) as Datum.Items).items)
    }

    /** The blocker ids of the last delivered message of the stage that carries blockers. */
    private fun openBlockers(stage: String): Datum {
        val last = state.delivered.values.lastOrNull { it.stage == stage && it.args.fields[BLOCKERS] is Datum.Items }
        val blockers = (last?.args?.fields?.get(BLOCKERS) as? Datum.Items)?.items.orEmpty()
        return Datum.Items(blockers.mapNotNull { (it as? Datum.Fields)?.fields?.get(ID) })
    }

    /** Deliveries of a kind in a scope (contracts.md §4.4): matching `where`, within `window`, after the last `since`. */
    private fun count(ref: FactRef): Datum? {
        val kind = (ref.params["kind"] as? Datum.Text)?.value ?: return null
        val scope = (ref.params["scope"] as? Datum.Text)?.value ?: STAGE_MACHINE
        val seat = (context.args.fields[SEAT_MACHINE] as? Datum.Text)?.value ?: context.message?.from
        val scoped = state.delivered.values.filter {
            when (scope) {
                "seat" -> seat != null && (it.from == seat || it.to == seat || (it.args.fields[SEAT_MACHINE] as? Datum.Text)?.value == seat)
                "phase" -> it.stage?.let(::phaseOf) == context.stage?.let(::phaseOf)
                "effort" -> it.stage?.substringBefore('/') == context.stage?.substringBefore('/')
                "office" -> true
                else -> it.stage == ((ref.params["stage"] as? Datum.Text)?.value ?: context.stage)
            }
        }
        val since = when (val after = ref.params["since"]) {
            null -> scoped
            is Datum.Text -> scoped.drop(scoped.indexOfLast { it.kind == after.value }.inc())
            // `{wake: <mode>}` counts from the entity's last wake in that mode (contracts.md §4.4, Amendment 2): in a
            // robot's rule, the robot's own; elsewhere, any entity's last.
            is Datum.Fields -> {
                val mode = (after.fields[WAKE] as? Datum.Text)?.value ?: return null
                val marks = state.wakes.filterKeys { role -> evaluation.robot?.let { it.role == role } ?: true }.values.mapNotNull { it[mode] }
                val later = state.delivered.keys.drop(marks.maxOrNull() ?: 0).toSet()
                scoped.filter { it.id in later }
            }
            else -> return null
        }
        val window = ref.params["window"]?.let { (it as? Datum.Text)?.value?.let(::duration) ?: return null }
        val inWindow = if (window == null) since else {
            val now = now()?.let(::utc) ?: return null
            since.filter { !utc(it.at).isBefore(now.minusSeconds(window.inWholeSeconds)) }
        }
        val where = (ref.params["where"] as? Datum.Fields)?.fields.orEmpty()
        return Datum.of(inWindow.count { delivery -> delivery.kind == kind && where.all { (key, value) -> delivery.args.fields[key] == value } })
    }
}

internal fun phaseOf(stage: String): String = stage.substringBeforeLast('/')

/**
 * The machine a fact the engine answers is read from, when it is read from one: what a `held` record names as what ends it
 * (contracts.md §2.2, `held.until`: `presence`).
 */
internal fun machineRead(fact: String): String? = when (fact) {
    "stage.state" -> STAGE_MACHINE
    "phase.state" -> PHASE_MACHINE
    "seat.state", "seat.inbox_replayed" -> SEAT_MACHINE
    "bora.present", "bora.absent_since" -> PRESENCE_MACHINE
    "thread.state", "thread.open" -> FLAG_MACHINE
    else -> null
}

internal const val NOW = "now"
internal const val BORA = "bora"
internal const val BORA_CLI = "bora-cli"
internal const val PRESENT = "present"
private const val STAGES = "stages"
private const val BLOCKERS = "blockers"
private const val ID = "id"
private const val WAKE = "wake"
