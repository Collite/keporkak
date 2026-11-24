package kp.daemon

import kp.daemon.seats.PoolFacts
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.BudgetFacts
import kp.engine.fold
import kp.facts.Observation
import kp.facts.ProjectFacts
import kp.facts.StageState
import kp.facts.WhatTheLogKnows
import kp.log.LogReader
import kp.log.counted
import kp.model.OfficeId
import java.nio.file.Path

/**
 * `just facts <stage>` (contracts.md §12, Amendment 34; S3.8 T5): every fact of the project and of git for a stage, its
 * phase and its effort — each with its value and evidence, or unknown and what is missing — read by the daemon's own
 * world, [ProjectWorld], on the office's project checkout and main checkouts, with what the office's log knows; then the
 * pool's facts and the budgets' (S3.13 T7, [PoolFacts]) — the three slots open and over, the floor's rank, the office's
 * computer use, and each subscription's consumption and its day against the daily median. It
 * writes nothing — no line to the log, none to the journal, no file or ref of a checkout — and needs no running daemon:
 * it reads the log as any reader does, without the writer's lock, and folds it as a start would before it carries a
 * judgement again — a judgement the log ends in, unfinished, counts for nothing yet.
 */
object StageFacts {
    /** What `just facts` prints for [stage], on [office]'s checkouts under the rules [rules], its project checkout being [project]. */
    fun lines(rules: InForceRules, office: String, project: Path, stage: String, path: String? = System.getenv("PATH")): List<String> {
        val world = ProjectWorld.of(rules, office, project, path) { }
        val read = LogReader(project, OfficeId(office)).read()
        val state = fold(read.lines.map { it.entry })
        val counted = read.lines.counted().map { it.entry }
        world.reading(object : WhatTheLogKnows {
            override fun state(stage: String): StageState? = state.machine(STAGE, stage)?.let { StageState(it.state, it.since) }
            override fun stages(): Map<String, StageState> = state.machines[STAGE].orEmpty().mapValues { (_, machine) -> StageState(machine.state, machine.since) }
            override fun activeStates(): Set<String> = rules.ruleSet.lifecycle.activeStates.toSet()
            override fun observedHead(message: String): String? = observedHead(counted, message)
            override fun lastDelivered(stage: String, kinds: Set<String>): String? = state.lastDelivered(stage, kinds)
        })
        val known = state.machine(STAGE, stage)
        val facts = (ProjectFacts.ANSWERED.keys + ProjectFacts.NOT_READ_YET.keys).map { name -> world.observation(FactRef(name, emptyMap()), stage)!! }
        // The pool's facts and the budgets' (S3.13 T7, Amendment 44), from the pool in force and the fold; no seat is driven here.
        val pool = PoolFacts(office) { }.also { it.reading(PoolFacts.Reading({ rules }, { state }, { counted }, serviceClock::now)) }
        val asked = DIMENSIONS.map { FactRef(SLOT_OPEN, mapOf(DIMENSION to Datum.Text(it))) } + DIMENSIONS.map { FactRef(SLOTS_OVER, mapOf(DIMENSION to Datum.Text(it))) } +
            FactRef(FLOOR_RANK, emptyMap()) + FactRef(COMPUTER_USE, emptyMap()) +
            rules.ruleSet.pool.roster.vendors.keys.flatMap { subscription ->
                listOf(FactRef(CONSUMPTION, mapOf(SUBSCRIPTION to Datum.Text(subscription), WINDOW to Datum.Text(BudgetFacts.FULLEST))), FactRef(VS_DAILY_MEDIAN, mapOf(SUBSCRIPTION to Datum.Text(subscription))))
            }
        val budgets = asked.map { pool.observation(it, stage)!! }
        return buildList {
            add("facts of $stage on office $office · rules ${rules.hash.take(HASH)}")
            add(world.reads.removePrefix("facts: "))
            read.problems.forEach { add("the log has a problem: $it") }
            add("the log: " + (known?.let { "$stage is ${it.state} since ${it.since} — the log's word" } ?: "no record of $stage — the project's word") +
                " · ${read.lines.size} lines, at seq ${state.seq}")
            (facts + listOf(null) + budgets).forEach { fact ->
                add(when (val observation = fact?.second) {
                    null -> "the pool's facts and the budgets' (Amendment 44) — this command drives no seat: what the vendor says of a subscription reaches the running daemon's seats only"
                    is Observation.Value -> "${fact.first} = ${shown(observation.value)} — ${observation.evidence}"
                    is Observation.Unknown -> "${fact.first} unknown — ${observation.missing}"
                })
            }
        }
    }

    /** A value as `just facts` prints it: a text quoted, a set in brackets. */
    private fun shown(value: Any): String = when (value) {
        is List<*> -> value.joinToString(", ", "[", "]")
        is String -> "\"$value\""
        else -> value.toString()
    }

    private const val STAGE = "stage"
    private const val HASH = 12
    private const val SLOT_OPEN = "budget.slot_open"
    private const val SLOTS_OVER = "budget.slots_over"
    private const val FLOOR_RANK = "roster.floor_rank"
    private const val COMPUTER_USE = "office.computer_use"
    private const val CONSUMPTION = "budget.consumption"
    private const val VS_DAILY_MEDIAN = "budget.vs_daily_median"
    private const val DIMENSION = "dimension"
    private const val SUBSCRIPTION = "subscription"
    private const val WINDOW = "window"
    /** The three dimensions of a slot (contracts.md §4.4, Amendment 44). */
    private val DIMENSIONS = listOf("office", "subscription", "effort")
}
