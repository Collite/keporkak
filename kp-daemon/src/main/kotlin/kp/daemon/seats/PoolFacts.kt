package kp.daemon.seats

import kp.daemon.InForceRules
import kp.daemon.Journal
import kp.daemon.Office
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.facts.BudgetFacts
import kp.facts.Observation
import kp.facts.TurnEnded
import kp.driver.SeatFacts
import kp.driver.UsageWindow
import kp.model.Fact
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kp.mailroom.FactSource
import kp.model.Envelope
import java.time.Instant

/**
 * The pool's facts and the budgets' (contracts.md §4.4, Amendment 44; S3.13): the nine facts the rules ask that nothing
 * else answers, each answered when a judgement or a view asks, from the pool as in force at that ask and the folded
 * state, with a value and its evidence; and a seat's four — `seat.name`, `seat.role`, `seat.model`, `seat.effort` — of a
 * role in a stage or of a seat, from the log (Amendment 47; S3.14). A fact that is unknown is answered null; what was
 * missing goes to the journal when a judgement asked, and a view writes none (Amendment 34).
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

    /**
     * [fact]'s answer for an ask about [stage], with its evidence, and the fact as it was read — a parameter left out
     * taken as the ask's — what `just facts` prints; null when it is not the pool's. Nothing it reads throws out of it:
     * what cannot be read is unknown, with why.
     */
    fun observation(fact: FactRef, stage: String?): Pair<FactRef, Observation>? {
        if (fact.name !in ANSWERED) return null
        val read = reading ?: return fact to Observation.Unknown("the daemon has not folded its log yet")
        val rules = read.rules() ?: return fact to Observation.Unknown("the daemon has not read its rules yet")
        val asked = Asked(fact, stage, read, rules)
        return asked.fact to try {
            asked.answer()
        } catch (error: Exception) {
            Observation.Unknown(error.toString())
        }
    }

    /** One ask: [given] about [stage], read from [read] with [rules] — the pool as in force at the ask. */
    private inner class Asked(given: FactRef, private val stage: String?, private val read: Reading, private val rules: InForceRules) {
        private val pool = rules.ruleSet.pool
        private val roster = pool.roster
        private val levels = Levels(roster, rules.texts)
        private val state by lazy { read.state() }
        private val log by lazy { SeatLog(read.lines()) }

        /** The fact as it is read: `office.computer_use` with no office asks for this one's. */
        val fact: FactRef = if (given.name == COMPUTER_USE && OFFICE !in given.params) FactRef(given.name, given.params + (OFFICE to Datum.Text(office))) else given

        private fun param(name: String): String? = (fact.params[name] as? Datum.Text)?.value

        fun answer(): Observation = when (fact.name) {
            RANK -> seat { seat, line -> Observation.Value(line.index, "$seat runs ${line.value.model} at ${line.value.effort}: ${where(ROSTER, "$ORDER.${line.index}")}") }
            MODEL_RANK -> seat { seat, line ->
                val first = firstOf(line.value.model)!!
                Observation.Value(first.index, "$seat runs ${line.value.model}, whose first line is ${where(ROSTER, "$ORDER.${first.index}")}")
            }
            FLOOR_RANK -> firstOf(roster.reviewFloor)?.let { first ->
                Observation.Value(first.index, "review.floor ${roster.reviewFloor} (${where(ROSTER, "review.floor")}), whose first line is ${where(ROSTER, "$ORDER.${first.index}")}")
            } ?: Observation.Unknown("the review floor ${roster.reviewFloor} is in no line of $ROSTER's order")
            SEAT_AVAILABLE -> available()
            COMPUTER_USE -> computerUse()
            SLOT_OPEN -> rooms { rooms -> Observation.Value(slotOpen(rooms), rooms.joinToString("; ") { it.told }) }
            SLOTS_OVER -> rooms { rooms -> Observation.Value(slotsOver(rooms), rooms.joinToString("; ") { it.told }) }
            CONSUMPTION -> subscription { subscription ->
                val window = param(WINDOW) ?: return@subscription Observation.Unknown("no parameter window")
                BudgetFacts.consumption(read.reports(), subscription, window, read.now())
            }
            SEAT_NAME -> named { seat, how -> Observation.Value(seat, how) }
            SEAT_ROLE -> named { seat, how ->
                log.known[seat]?.role?.let { Observation.Value(it, "$how; started as $it") } ?: Observation.Unknown("the log names no role $seat was started for")
            }
            SEAT_MODEL, SEAT_EFFORT -> named { seat, how ->
                when (val line = lineOf(seat)) {
                    is Lined.Of -> Observation.Value(if (fact.name == SEAT_MODEL) line.line.value.model else line.line.value.effort,
                        "$how; it runs ${line.line.value.model} at ${line.line.value.effort}: ${where(ROSTER, "$ORDER.${line.line.index}")}")
                    is Lined.Not -> Observation.Unknown(line.why)
                }
            }
            VS_DAILY_MEDIAN -> subscription { subscription ->
                val (span, least) = pool.budgets.usage.median
                val leastAt = pool.lines[BUDGETS]?.get(LEAST)?.let { "$BUDGETS line $it, $LEAST" } ?: "$BUDGETS, $LEAST"
                BudgetFacts.vsDailyMedian(read.lines().filterIsInstance<Fact>().mapNotNull(::turnEnded), subscription, read.now(), span, least, leastAt)
            }
            else -> Observation.Unknown("not answered yet")
        }

        // ---- the budgets (Amendment 44: what the vendor itself says, and the turns' own records)

        /** The subscription the fact names: one of the roster's vendors. */
        private fun subscription(answer: (String) -> Observation): Observation {
            val subscription = param(SUBSCRIPTION) ?: return Observation.Unknown("no parameter subscription")
            if (subscription !in roster.vendors) return Observation.Unknown("$ROSTER names no subscription $subscription")
            return answer(subscription)
        }

        /**
         * A turn's end, as its record says it (Seats): `seat.usage` of the window `turn` whose evidence begins as a turn's end
         * does — its weighted count, when it ended, its seat's subscription. The watch's reports of a turn in flight are none.
         */
        private fun turnEnded(fact: Fact): TurnEnded? {
            if (fact.fact != SeatFacts.USAGE || (fact.params?.get(WINDOW) as? JsonPrimitive)?.contentOrNull != UsageWindow.TURN.word) return null
            val evidence = fact.evidence?.takeIf { it.startsWith(Seats.TURN_ENDED) } ?: return null
            val count = (fact.value as? JsonPrimitive)?.longOrNull ?: return null
            return TurnEnded(evidence.substringAfterLast(SUBSCRIBED), Instant.parse(fact.at.value), count)
        }

        // ---- the slots (Amendment 44: a seat is in use from the judgement that requests its start until it is retired)

        /** `budget.slot_open`: every limit read has room — fewer in use than it allows. A count held against a limit of the pool by its definition (Amendment 44). */
        private fun slotOpen(rooms: List<Room>): Boolean = rooms.all { it.used < it.limit }

        /** `budget.slots_over`: the number in use above the limit, zero at or under it; of several limits read, the largest (Amendment 44). */
        private fun slotsOver(rooms: List<Room>): Int = rooms.map { it.used - it.limit }.plus(0).max()

        /** The limits the asked dimension reads, each with what is counted against it; unknown with why when one cannot be read. */
        private fun rooms(answer: (List<Room>) -> Observation): Observation {
            val dimension = param(DIMENSION) ?: return Observation.Unknown("no parameter dimension")
            val counted = when (dimension) {
                OFFICE -> officeRooms()
                SUBSCRIPTION -> subscriptionRooms()
                EFFORT -> effortRooms()
                else -> Counted.Not("no dimension $dimension: office, subscription or effort")
            }
            return when (counted) {
                is Counted.Of -> answer(counted.rooms)
                is Counted.Not -> Observation.Unknown(counted.why)
            }
        }

        /** The seats in use: every seat the fold holds in a state but `retired` — `gone` too, whose re-seat comes back into the same slot. */
        private val inUse: List<Pair<String, String>> by lazy {
            state.machines[SEAT_MACHINE].orEmpty().filterValues { it.state != RETIRED }.map { (seat, machine) -> seat to machine.state }
        }

        private fun told(seats: List<Pair<String, String>>): String = "in use ${seats.size} — ${seats.joinToString(", ") { (seat, at) -> "$seat $at" }.ifEmpty { "none" }} —"

        /** `office`: the seats in use, against `slots.per_office` of this office. */
        private fun officeRooms(): Counted {
            val limit = pool.budgets.slots.perOffice[office] ?: return Counted.Not("$BUDGETS gives the office $office no limit")
            return Counted.Of(listOf(Room(inUse.size, limit, "office $office: ${told(inUse)} of ${limitAt("slots.per_office.$office", limit)}")))
        }

        /**
         * `subscription`: the seats in use of a subscription — the vendor of its line — against `slots.per_subscription` of
         * the subscription the rule passes, else of every subscription the pool names.
         */
        private fun subscriptionRooms(): Counted {
            val vendors = inUse.associate { (seat, _) ->
                when (val line = lineOf(seat)) {
                    is Lined.Of -> seat to line.line.value.vendor
                    is Lined.Not -> return Counted.Not("the subscription of $seat is not known: ${line.why}")
                }
            }
            val subscriptions = param(SUBSCRIPTION)?.let(::listOf) ?: pool.budgets.slots.perSubscription.keys.toList()
            return Counted.Of(subscriptions.map { subscription ->
                val limit = pool.budgets.slots.perSubscription[subscription] ?: return Counted.Not("$BUDGETS gives the subscription $subscription no limit")
                val counted = inUse.filter { (seat, _) -> vendors[seat] == subscription }
                Room(counted.size, limit, "subscription $subscription: ${told(counted)} of ${limitAt("slots.per_subscription.$subscription", limit)}")
            })
        }

        /**
         * `effort`: the stages of an effort in an active state (§4.1) against `slots.stages_in_flight_per_effort` — the
         * effort the rule passes, else the asked stage's, else every effort of the office's project.
         */
        private fun effortRooms(): Counted {
            val known = pool.offices[office]?.project?.efforts ?: return Counted.Not("$OFFICES names no office $office")
            val efforts = param(EFFORT)?.let(::listOf) ?: stage?.substringBefore('/')?.let(::listOf) ?: known.keys.toList()
            efforts.firstOrNull { it !in known }?.let { return Counted.Not("the project of office $office names no effort $it") }
            val active = rules.ruleSet.lifecycle.activeStates
            val limit = pool.budgets.slots.stagesInFlightPerEffort
            return Counted.Of(efforts.map { effort ->
                val stages = state.machines[STAGE_MACHINE].orEmpty().filter { (id, machine) -> id.substringBefore('/') == effort && machine.state in active }.map { (id, machine) -> id to machine.state }
                Room(stages.size, limit, "effort $effort: ${told(stages).replace("in use", "in flight")} of ${limitAt("slots.stages_in_flight_per_effort", limit)}")
            })
        }

        /** A limit of `pool/budgets.yaml`: its line, its path and its value. */
        private fun limitAt(path: String, limit: Int): String = pool.lines[BUDGETS]?.get(path)?.let { "$BUDGETS line $it ($path: $limit)" } ?: "$BUDGETS ($path: $limit)"

        // ---- the roster (Amendment 44: a seat's line is the order line of the model and effort it was started with)

        /** The seat the fact names — by `of` or `seat`: a seat, or a role in the asked stage, as `seat.name` names one — and its line. */
        private fun seat(answer: (String, IndexedValue<kp.rules.Line>) -> Observation): Observation {
            val named = param(OF) ?: param(SEAT) ?: return Observation.Unknown("no parameter of or seat names a seat")
            val seat = if (isRole(named)) {
                val at = stage ?: return Observation.Unknown("no stage to find the role $named in")
                boundTo(named, at) ?: return Observation.Unknown("no seat is bound to the role $named in $at")
            } else named
            return when (val line = lineOf(seat)) {
                is Lined.Of -> answer(seat, line.line)
                is Lined.Not -> Observation.Unknown(line.why)
            }
        }

        /**
         * The seat a seat's fact names (Amendment 47) — by `of` or `seat`: a role in the asked stage, whose seat is the last
         * the log started for it there and not retired; else a seat the log started or the seats hold, itself — with how it
         * was found. Unknown with the role and the stage when the role has no such seat.
         */
        private fun named(answer: (String, String) -> Observation): Observation {
            val named = param(OF) ?: param(SEAT) ?: return Observation.Unknown("no parameter of or seat names a seat")
            if (isRole(named)) {
                val at = stage ?: return Observation.Unknown("no stage to find the role $named in")
                val seat = boundTo(named, at) ?: return Observation.Unknown("no seat is bound to the role $named in $at")
                return answer(seat, "$seat: the last seat started for $named in $at and not retired")
            }
            if (named !in log.known && read.bound(named) == null) return Observation.Unknown("no seat $named is known to the log or to the seats")
            return answer(named, "$named: a seat ${if (named in log.known) "the log started" else "the seats hold"}")
        }

        /** Whether [name] is a role: a persona declares it (`agents/<role>/AGENT.md`). */
        private fun isRole(name: String): Boolean = "agents/$name/AGENT.md" in rules.texts

        /** The seat bound to [role] in [stage]: the last the log started for it there that is not retired. */
        private fun boundTo(role: String, stage: String?): String? =
            log.known.entries.lastOrNull { (seat, known) -> known.role == role && known.stage == stage && state.machine(SEAT_MACHINE, seat)?.state != RETIRED }?.key

        /**
         * [seat]'s line: the model and the effort the daemon's seats hold it was started with; else, for a seat the log
         * started, the line a start gives its role there — the reading a start uses (Levels).
         */
        private fun lineOf(seat: String): Lined {
            read.bound(seat)?.let { (model, effort) ->
                return roster.order.withIndex().firstOrNull { it.value.model == model && it.value.effort == effort }?.let(Lined::Of)
                    ?: Lined.Not("$seat runs $model at $effort, which no line of $ROSTER's order holds")
            }
            val known = log.known[seat] ?: return Lined.Not("no seat $seat is known to the log or to the seats")
            val role = known.role ?: return Lined.Not("the log names no role $seat was started for")
            return when (val seated = levels.line(role, Levels.asked(role, known.stage, known.cause, state.delivered)) { other -> lineOn(other, known.stage) }) {
                is Levels.Result.Seated -> roster.order.withIndex().firstOrNull { it.value == seated.line }?.let(Lined::Of)
                    ?: Lined.Not("$seat would be seated on a line no line of $ROSTER's order holds")
                is Levels.Result.Unseated -> Lined.Not("$seat cannot be seated as $role: ${seated.why}")
            }
        }

        /** [role]'s line on [stage]: its seat's, when one is bound there; else the line the roster gives the role there. */
        private fun lineOn(role: String, stage: String?): kp.rules.Line? =
            boundTo(role, stage)?.let { (lineOf(it) as? Lined.Of)?.line?.value }
                ?: (levels.line(role, Levels.asked(role, stage, null, state.delivered)) { null } as? Levels.Result.Seated)?.line

        /** The first line of [model] in the order, with its index. */
        private fun firstOf(model: String): IndexedValue<kp.rules.Line>? = roster.order.withIndex().firstOrNull { it.value.model == model }

        /**
         * `pool.seat_available` (role, level): true when a `seat.start` for the role at the level would be seated — the
         * roster gives a line by the reading a start uses, and its model is not reserved for another role. No slot is
         * counted here: `budget.slot_open` is asked beside it.
         */
        private fun available(): Observation {
            val role = param(ROLE) ?: return Observation.Unknown("no parameter role")
            if (!isRole(role)) return Observation.Unknown("no persona declares the role $role")
            val level = param(LEVEL)
            if (level != null && !levels.knows(level)) return Observation.Unknown("$ROSTER has no level $level")
            val asked = "$role${level?.let { " at $it" } ?: ""}"
            return when (val seated = levels.line(role, level) { other -> lineOn(other, stage) }) {
                is Levels.Result.Unseated -> Observation.Value(false, "a start of $asked would not be seated: ${seated.why}")
                is Levels.Result.Seated -> {
                    val line = seated.line
                    val at = roster.order.withIndex().firstOrNull { it.value == line }?.let { where(ROSTER, "$ORDER.${it.index}") } ?: ROSTER
                    val reserved = roster.reserved[line.model]
                    val free = reserved?.let { role in it } ?: true
                    Observation.Value(free, "$asked is seated on ${line.model} ${line.effort} ($at); " +
                        (reserved?.let { "the model is reserved for ${it.joinToString(", ")} (${where(ROSTER, "reserved.${line.model}")})" } ?: "the model is reserved for no role"))
                }
            }
        }

        /** `office.computer_use` (office): the office's `computer_use`, this office's when none is named. */
        private fun computerUse(): Observation {
            val name = param(OFFICE)!!
            val entry = pool.offices[name] ?: return Observation.Unknown("$OFFICES names no office $name")
            return Observation.Value(entry.computerUse, where(OFFICES, "offices.$name.computer_use"))
        }

        /** Where a value of a pool file stands: the file, its line, and the value's path in it. */
        private fun where(file: String, path: String): String = pool.lines[file]?.get(path)?.let { "$file line $it ($path)" } ?: "$file ($path)"
    }

    /** A limit of the pool and the count held against it — of one office, subscription or effort — with what was counted. */
    private class Room(val used: Int, val limit: Int, val told: String)

    /** The limits a dimension reads, or why one cannot be read. */
    private sealed interface Counted {
        class Of(val rooms: List<Room>) : Counted
        class Not(val why: String) : Counted
    }

    /** A seat's line of the order, or why it has none. */
    private sealed interface Lined {
        class Of(val line: IndexedValue<kp.rules.Line>) : Lined
        class Not(val why: String) : Lined
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
        private const val RANK = "roster.rank"
        private const val MODEL_RANK = "roster.model_rank"
        private const val FLOOR_RANK = "roster.floor_rank"
        private const val SEAT_AVAILABLE = "pool.seat_available"
        private const val COMPUTER_USE = "office.computer_use"
        private const val SLOT_OPEN = "budget.slot_open"
        private const val SLOTS_OVER = "budget.slots_over"
        private const val CONSUMPTION = "budget.consumption"
        private const val VS_DAILY_MEDIAN = "budget.vs_daily_median"
        private const val SEAT_NAME = "seat.name"
        private const val SEAT_ROLE = "seat.role"
        private const val SEAT_MODEL = "seat.model"
        private const val SEAT_EFFORT = "seat.effort"

        /** The nine (Amendment 44), and a seat's four the service answers from the log (Amendment 47). */
        val ANSWERED: Set<String> = setOf(RANK, MODEL_RANK, FLOOR_RANK, SEAT_AVAILABLE, COMPUTER_USE, SLOT_OPEN, SLOTS_OVER, CONSUMPTION, VS_DAILY_MEDIAN,
            SEAT_NAME, SEAT_ROLE, SEAT_MODEL, SEAT_EFFORT)

        private const val ROSTER = "pool/roster.yaml"
        private const val OFFICES = "pool/offices.yaml"
        private const val BUDGETS = "pool/budgets.yaml"
        private const val DIMENSION = "dimension"
        private const val SUBSCRIPTION = "subscription"
        private const val EFFORT = "effort"
        private const val STAGE_MACHINE = "stage"
        private const val WINDOW = "window"
        private const val LEAST = "usage.median.least"
        /** How a turn's end names its seat's subscription, last in its evidence (Seats). */
        private const val SUBSCRIBED = " · subscription "
        private const val ORDER = "order"
        private const val OF = "of"
        private const val SEAT = "seat"
        private const val ROLE = "role"
        private const val LEVEL = "level"
        private const val OFFICE = "office"
        private const val SEAT_MACHINE = "seat"
        private const val RETIRED = "retired"
    }
}
