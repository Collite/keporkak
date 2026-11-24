package kp.daemon.seats

import kp.daemon.InForceRules
import kp.daemon.Journal
import kp.daemon.Office
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.facts.Observation
import kp.mailroom.FactSource
import kp.model.Envelope
import java.time.Instant

/**
 * The pool's facts and the budgets' (contracts.md §4.4, Amendment 44; S3.13): the nine facts the rules ask that nothing
 * else answers, each answered when a judgement or a view asks, from the pool as in force at that ask and the folded
 * state, with a value and its evidence. A fact that is unknown is answered null; what was missing goes to the journal
 * when a judgement asked, and a view writes none (Amendment 34).
 */
class PoolFacts(private val office: String, private val say: (String) -> Unit = Journal::say) : FactSource {
    /**
     * What it reads: the rules in force, the fold, the log as it counts, the clock; and of the seats the daemon drives —
     * none for `just facts`, which runs no seat — the model and the effort each was started with ([bound]) and what the
     * vendor last reported of a subscription through them ([reports]).
     */
    class Reading(
        val rules: () -> InForceRules?,
        val state: () -> State,
        val lines: () -> List<Envelope>,
        val now: () -> Instant,
        val bound: (String) -> Pair<String, String>? = { null },
        val reports: () -> List<kp.facts.VendorReport> = { emptyList() },
    )

    @Volatile private var reading: Reading? = null

    /** Reads [office]'s rules, fold, log and clock from now on, and what [seats] know of the seats they drive. */
    fun reading(office: Office, seats: Seats? = null) = reading(Reading({ office.rules }, { office.state }, { office.counted() }, { office.clock.now() },
        bound = { seat -> seats?.binding(seat)?.let { it.model to it.effort } }, reports = { seats?.reports().orEmpty() }))

    /** Reads what [reading] hands over from now on. */
    fun reading(reading: Reading) {
        this.reading = reading
    }

    /** A judgement's ask: what is unknown goes to the journal, each time. */
    override fun observe(fact: FactRef, stage: String?): Datum? = answer(fact, stage, journal = true)

    /** A view's ask: the same answer, and no journal line (Amendment 34). */
    val view: FactSource = FactSource { fact, stage -> answer(fact, stage, journal = false) }

    /** [fact]'s answer for an ask about [stage], with its evidence, and the fact as it was read; null when it is not the pool's. */
    fun observation(fact: FactRef, stage: String?): Pair<FactRef, Observation>? {
        if (fact.name !in ANSWERED) return null
        return fact to Observation.Unknown("not answered yet")
    }

    private fun answer(fact: FactRef, stage: String?, journal: Boolean): Datum? = when (val answer = observation(fact, stage)?.second) {
        null -> null
        is Observation.Value -> Datum.of(answer.value)
        is Observation.Unknown -> {
            if (journal) say("facts: $fact for ${stage ?: "no stage"} is unknown — ${answer.missing}")
            null
        }
    }

    companion object {
        /** The nine (Amendment 44). */
        val ANSWERED: Set<String> = setOf(
            "roster.rank", "roster.model_rank", "roster.floor_rank", "pool.seat_available", "office.computer_use",
            "budget.slot_open", "budget.slots_over", "budget.consumption", "budget.vs_daily_median",
        )
    }
}
