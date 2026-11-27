package kp.daemon

import kp.daemon.seats.PoolFacts
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.BudgetFacts
import kp.facts.Observation

/**
 * The four facts the daemon reports when they change (contracts.md §4.4, Amendment 45; S3.4 T5): `git.stray_changes`
 * for each stage in an active state, `budget.slots_over` for each dimension, `budget.consumption` and
 * `budget.vs_daily_median` for each subscription. A robot's rule `on: {fact: …}` waits for such a report. This is the
 * one place that holds what was last reported of each; a report is a `fact` record, written through
 * [Office.recorded], with the value and the evidence an ask would answer.
 *
 * When it reports is Amendment 45's, and nothing else: a value is compared with the one last reported of it — the same
 * number of stray paths or slots over, the same tenth of the vendor's fraction ([BudgetFacts.tenth]), the same whole
 * part of the day against the median ([BudgetFacts.whole]) — and with no threshold; what a value means is the robots'.
 * What reads the facts is S3.13's and S3.1's: [project] and [pool] as the service reads them. Each is read outside
 * the lock that guards what was last reported, and written outside it too: a judgement that moved a seat tells this
 * from inside the mailroom's turn, while the watch tells it from its own thread — no lock of this may be held while
 * the mailroom judges a report (contracts.md §7, Amendment 36).
 *
 * What is decided first is written first (S3.15 T4): a report decided is queued under that lock, in the order decided,
 * and one caller at a time writes the queue, outside it. A caller that finds another writing leaves its reports to that
 * one and returns, and waits for nothing — the writer may itself be waiting for the mailroom whose turn told this. [write]
 * is where a report goes: [Office.recorded].
 */
class Reports(
    private val office: Office,
    private val project: ProjectWorld?,
    private val pool: PoolFacts,
    private val write: (FactRef, Datum, String?, String?) -> Unit = office::recorded,
) {
    /** What was last reported, by the fact as read and the stage it was reported for. */
    private val last = HashMap<Pair<FactRef, String?>, Any>()
    /** Under [last]'s lock: the reports decided and not yet written, in the order decided; whether a caller is writing them. */
    private val unwritten = ArrayDeque<Reading>()
    private var writing = false

    /** A start (Amendment 45): each of the four once, as it reads now; one that is unknown is left out. */
    fun started() = report(strays() + slots() + consumptions() + medians(), always = true)

    /** The watch, every `watch.every` (§9): `git.stray_changes` for each stage in an active state, when it changed for it. */
    fun watched() {
        val active = activeStages()
        synchronized(last) { last.keys.removeAll { (fact, stage) -> fact.name == STRAY && stage !in active } }
        report(strays(active))
    }

    /** A judgement moved a seat or a stage: `budget.slots_over` for each dimension, when it changed. */
    fun moved() = report(slots())

    /** The pool was reloaded: `budget.slots_over` for each dimension, when it changed. */
    fun reloaded() = report(slots())

    /** The vendor reported a window through a seat: `budget.consumption` of each subscription, when its fraction is in another tenth. */
    fun vendorReported() = report(consumptions())

    /** A turn's end was recorded: `budget.vs_daily_median` of each subscription, when its whole part changed. */
    fun turnEnded() = report(medians())

    /** One reading: the fact as read, the stage it is for, its value and evidence, and what tells a change of it. */
    private class Reading(val fact: FactRef, val stage: String?, val value: Observation.Value, val unread: Any?, val same: (Any?, Any?) -> Boolean)

    /** [readings] decided ([decided]), then what is decided and not yet written, written ([say]). */
    private fun report(readings: List<Reading>, always: Boolean = false) {
        decided(readings, always)
        say()
    }

    /**
     * Of [readings], those to report — with [always] each, at a start; else each whose value is not the same as the one last
     * reported of it (for a stage's stray paths, none reported reads as no path) — queued to be written, and what was last
     * reported, updated.
     */
    private fun decided(readings: List<Reading>, always: Boolean = false) = synchronized(last) {
        readings.forEach { reading ->
            val key = reading.fact to reading.stage
            val before = last[key] ?: reading.unread
            if (always || !reading.same(reading.value.value, before)) {
                last[key] = reading.value.value
                unwritten.addLast(reading)
            }
        }
    }

    /**
     * Writes the reports decided and not yet written, oldest first, holding no lock while each is written — unless another
     * caller is writing them: that one writes these too.
     */
    private fun say() {
        synchronized(last) { if (writing) return else writing = true }
        try {
            while (true) {
                val reading = next() ?: return
                write(reading.fact, Datum.of(reading.value.value), reading.stage, reading.value.evidence)
            }
        } catch (error: Throwable) {
            // What is left stays queued, for the next caller to write.
            synchronized(last) { writing = false }
            throw error
        }
    }

    /** The oldest report decided and not yet written; none — and the caller writing them is done, under the same lock. */
    private fun next(): Reading? = synchronized(last) { unwritten.removeFirstOrNull().also { if (it == null) writing = false } }

    /** `git.stray_changes`, read once, for each stage of [stages]: none with no stage in an active state. */
    private fun strays(stages: List<String> = activeStages()): List<Reading> {
        val world = project ?: return emptyList()
        if (stages.isEmpty()) return emptyList()
        val (fact, read) = world.observation(FactRef(STRAY), stages.first()) ?: return emptyList()
        val value = read as? Observation.Value ?: return emptyList()
        return stages.map { stage -> Reading(fact, stage, value, NO_PATH, ::equal) }
    }

    private fun slots(): List<Reading> = DIMENSIONS.mapNotNull { dimension -> read(FactRef(SLOTS_OVER, mapOf(DIMENSION to Datum.Text(dimension))), ::equal) }

    private fun consumptions(): List<Reading> = subscriptions().mapNotNull { subscription ->
        read(FactRef(CONSUMPTION, mapOf(SUBSCRIPTION to Datum.Text(subscription), WINDOW to Datum.Text(BudgetFacts.FULLEST)))) { now, before -> BudgetFacts.tenth(now) == BudgetFacts.tenth(before) }
    }

    private fun medians(): List<Reading> = subscriptions().mapNotNull { subscription ->
        read(FactRef(VS_DAILY_MEDIAN, mapOf(SUBSCRIPTION to Datum.Text(subscription)))) { now, before -> BudgetFacts.whole(now) == BudgetFacts.whole(before) }
    }

    /** A pool's fact, as it reads now about no stage; null when it is unknown — unknown is never reported. */
    private fun read(fact: FactRef, same: (Any?, Any?) -> Boolean): Reading? {
        val (asked, read) = pool.observation(fact, null) ?: return null
        return (read as? Observation.Value)?.let { Reading(asked, null, it, null, same) }
    }

    private fun subscriptions(): List<String> = office.rules.ruleSet.pool.roster.vendors.keys.toList()

    /** The stages in an active state (§4.1), as the fold holds them. */
    private fun activeStages(): List<String> {
        val active = office.rules.ruleSet.lifecycle.activeStates.toSet()
        return office.state.machines[STAGE].orEmpty().filterValues { it.state in active }.keys.toList()
    }

    private companion object {
        const val STRAY = "git.stray_changes"
        const val SLOTS_OVER = "budget.slots_over"
        const val CONSUMPTION = "budget.consumption"
        const val VS_DAILY_MEDIAN = "budget.vs_daily_median"
        const val STAGE = "stage"
        const val DIMENSION = "dimension"
        const val SUBSCRIPTION = "subscription"
        const val WINDOW = "window"
        /** The three dimensions of a slot (contracts.md §4.4, Amendment 44). */
        val DIMENSIONS = listOf("office", "subscription", "effort")
        /** What a stage's stray paths read as before any is reported for it: a stage that becomes active is reported while above it. */
        const val NO_PATH = 0

        fun equal(now: Any?, before: Any?): Boolean = now == before
    }
}
