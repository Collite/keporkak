package kp.engine

import kp.rules.Action
import kp.rules.Destination
import kp.rules.Intercept
import kp.rules.MessageClass
import kp.rules.Requirement
import kp.rules.Robot
import kp.rules.RobotTrigger
import kp.rules.RuleSet

/**
 * What a robot's rule sends (contracts.md §8): through the mailroom like any seat's message, from the robot's [role],
 * `by: robot:<rule>`. [to] and [args] are resolved — they may come from the trigger. A send that answers a message is
 * [re] it, and is about its [stage].
 */
data class RobotSend(val rule: String, val role: String, val kind: String, val to: String?, val args: Map<String, Datum>, val re: String?, val stage: String?)

/** A robot's `{wake: <mode>}`: its own seat, in one of its persona's modes — it reaches nobody else. */
data class RobotWake(val rule: String, val role: String, val mode: String)

/**
 * An arriving message a robot takes instead of its addressee (contracts.md §2.2 `held`, §8): [by] the rule, [until]
 * what releases it. One the Deputy holds waits for Bora — it gets a [ticket] — and one QA's queue holds waits for QA.
 */
data class RobotHold(val by: String, val until: String, val ticket: Boolean, val messageClass: MessageClass?)

/**
 * What the robots do with one trigger: the rules that fired, in order, their sends and wakes; for an arrival, whether a
 * robot keeps the message; for a [Trigger.Release], the held messages to deliver now, in order.
 */
class Reaction internal constructor(
    val fired: List<String>,
    val sends: List<RobotSend>,
    val wakes: List<RobotWake>,
    val held: RobotHold?,
    val released: List<String>,
    /** Every fact the robots read, by [FactRef]'s written form. */
    val observed: Map<String, Datum>,
) {
    val isEmpty: Boolean get() = fired.isEmpty() && held == null && released.isEmpty()

    override fun toString(): String = "Reaction(fired=$fired, sends=$sends, wakes=$wakes, held=$held, released=$released)"

    internal companion object {
        val NONE = Reaction(emptyList(), emptyList(), emptyList(), null, emptyList(), emptyMap())
    }
}

/**
 * The engine applied to the robots' files (architecture.md §1): each active robot's rules that [trigger] fires —
 * read with the same evaluator and requirement forms as the guard's, `{robot: …}` from the robot's own thresholds —
 * and what they keep and let go. A rule `for: persona` is never read.
 */
internal class Reacting(private val rules: RuleSet, private val state: State, private val trigger: Trigger, private val facts: Facts) {
    private val context = Context.ofRobots(trigger, state)
    private val observed = LinkedHashMap<String, Datum>()

    private fun evaluation(robot: Robot) = Evaluation(rules, state, context!!, facts, robot)

    private fun Evaluation.active(robot: Robot): Boolean = holds(robot.activeWhen).also { observed.putAll(this.observed) }

    fun run(): Reaction {
        context ?: return Reaction.NONE
        if (trigger is Trigger.Release) return freed(trigger)
        val fired = ArrayList<String>()
        val sends = ArrayList<RobotSend>()
        val wakes = ArrayList<RobotWake>()
        rules.robots.values.forEach { robot ->
            val evaluation = evaluation(robot)
            if (!evaluation.active(robot)) return@forEach
            robot.rules.filter { !it.forPersona && hears(it.on!!) }.forEach { rule ->
                val failure = evaluation.firstFailure(rule.condition)
                observed.putAll(evaluation.observed)
                if (failure != null) return@forEach
                fired += rule.id
                rule.then.forEach { action ->
                    when (action) {
                        is Action.Send -> sends += RobotSend(rule.id, robot.role, action.kind, (evaluation.value(action.to) as? Datum.Text)?.value,
                            action.args.mapValues { evaluation.value(it.value) ?: Datum.Null }, context.message?.id, context.stage)
                        is Action.Wake -> wakes += RobotWake(rule.id, robot.role, action.mode)
                    }
                }
                observed.putAll(evaluation.observed)
            }
        }
        val held = (trigger as? Trigger.Arrival)?.let { keeps(sends) }
        return Reaction(fired, sends, wakes, held, emptyList(), observed.toMap())
    }

    /** Whether a rule's `on` is this trigger (contracts.md §8). */
    private fun hears(on: RobotTrigger): Boolean = when (on) {
        is RobotTrigger.Fact -> trigger is Trigger.Changed && trigger.fact == on.name
        is RobotTrigger.Delivered -> trigger is Trigger.Delivered && context?.kind == on.kind
        is RobotTrigger.Arrives -> trigger is Trigger.Arrival && context?.kind == on.kind
        is RobotTrigger.Expired -> trigger is Trigger.Expiry && context?.kind == on.kind
        is RobotTrigger.Presence -> trigger is Trigger.Presence && trigger.state == on.state
        is RobotTrigger.Operation -> trigger is Trigger.Result && trigger.operation == on.operation && trigger.ok == (on.outcome == kp.rules.Outcome.OK)
    }

    /**
     * Whether an active robot takes the arriving message (contracts.md §8). Its queue takes every request of a kind
     * the queue orders that is addressed to the robot's role: it is handed over by [freed]. Its `intercepts` — a
     * kind, or a class and an addressee — route the message to the robot, which holds it when its rules answer the
     * asker with a kind addressed back to the asker (`to: {re: from}`, as DEP-1 says: send it "to the asker"); a message a robot
     * intercepts and answers otherwise goes on to its addressee.
     */
    private fun keeps(sends: List<RobotSend>): RobotHold? {
        val message = context!!.message!!
        val kind = rules.kinds[message.kind] ?: return null
        rules.robots.values.forEach { robot ->
            val evaluation = evaluation(robot)
            if (!evaluation.active(robot)) return@forEach
            val queue = robot.queue
            if (queue != null && message.kind in queue.order && roleOwns(robot.role, message.to)) {
                return RobotHold(ROBOT + (queue.prose.firstOrNull()?.id ?: robot.role), releasedBy(queue.nextWhen), ticket = false, classOf(rules, kind, evaluation))
            }
            val messageClass = classOf(rules, kind, evaluation).also { observed.putAll(evaluation.observed) }
            val intercepted = robot.intercepts.any { intercept ->
                when (intercept) {
                    is Intercept.Kind -> intercept.name == message.kind
                    is Intercept.ClassTo -> intercept.messageClass == messageClass && intercept.to == message.to
                }
            }
            if (!intercepted) return@forEach
            val answer = sends.firstOrNull { it.role == robot.role && rules.kinds[it.kind]?.to is Destination.Reply } ?: return@forEach
            return RobotHold(ROBOT + answer.rule, releasedBy(robot.activeWhen), ticket = true, messageClass)
        }
        return null
    }

    /**
     * The messages the robot of [asked] keeps whose holding has ended, in the order to deliver them. What its
     * `intercepts` took is released, in the order it was held, once the robot is no longer active. Its queue hands
     * over one request at a time: none while the last one handed over still waits for its answer, none until its
     * `next_when` holds for that last one; then the first waiting by the queue's order of kinds, then first come.
     */
    private fun freed(asked: Trigger.Release): Reaction {
        val robot = rules.robots[asked.robot] ?: return Reaction.NONE
        val evaluation = evaluation(robot)
        val queueIds = robot.queue?.prose.orEmpty().map { ROBOT + it.id }.ifEmpty { listOf(ROBOT + robot.role) }.toSet()
        val ruleIds = robot.rules.map { ROBOT + it.id }.toSet()
        val held = state.held.filterValues { it.by in ruleIds || (robot.queue != null && it.by in queueIds) }
        val intercepted = held.filterValues { it.by in ruleIds }.keys.toList()
        val released = ArrayList<String>()
        if (intercepted.isNotEmpty() && !evaluation.active(robot)) released += intercepted
        val queue = robot.queue
        val waiting = held.filterValues { it.by in queueIds }.keys.toList()
        if (queue != null && waiting.isNotEmpty()) {
            val job = asked.job
            val free = job == null || (job !in state.deadlines && evaluation.holds(queue.nextWhen))
            observed.putAll(evaluation.observed)
            if (free) waiting.sortedBy { id -> state.pending[id]?.kind?.let(queue.order::indexOf) ?: Int.MAX_VALUE }.firstOrNull()?.let { released += it }
        }
        return Reaction(emptyList(), emptyList(), emptyList(), null, released, observed.toMap())
    }

    /** What a `held` record names as ending it: the machine the requirement reads (`presence`), or the first fact it reads. */
    private fun releasedBy(requirement: Requirement): String = facts(requirement).firstOrNull()?.let { machineRead(it) ?: it } ?: render(requirement)

    private fun facts(requirement: Requirement): List<String> = when (requirement) {
        is Requirement.Fact -> listOf(requirement.fact.name)
        is Requirement.Any -> requirement.items.flatMap(::facts)
        is Requirement.Not -> facts(requirement.item)
        else -> emptyList()
    }
}

/** The facts [Reacting] may read and the table must supply, found by reading the robots' files. */
internal class RobotNeeds(private val rules: RuleSet, private val state: State, private val trigger: Trigger) {
    fun collect(): List<Need> {
        val context = Context.ofRobots(trigger, state) ?: return emptyList()
        val needs = LinkedHashSet<Need>()
        needs += Need(NOW, emptyMap())
        rules.robots.values.forEach { robot ->
            val reading = Needs(rules, state, context, robot)
            reading.requirement(robot.activeWhen)
            when (trigger) {
                is Trigger.Release -> robot.queue?.let { reading.requirement(it.nextWhen) }
                else -> robot.rules.filter { !it.forPersona }.forEach { rule ->
                    rule.condition.forEach(reading::requirement)
                    rule.then.filterIsInstance<Action.Send>().forEach { send -> (send.args.values + send.to).forEach(reading::value) }
                }
            }
            if (trigger is Trigger.Arrival) rules.kinds[trigger.message.kind]?.let(reading::classCases)
            needs += reading.needs
        }
        return needs.toList()
    }
}

/** The robots holding something, each with the last request its queue handed over: what [Trigger.Release] asks about. */
internal fun holders(rules: RuleSet, state: State): List<Trigger.Release> = rules.robots.mapNotNull { (file, robot) ->
    val ids = robot.rules.map { ROBOT + it.id } + robot.queue?.prose.orEmpty().map { ROBOT + it.id } + (ROBOT + robot.role)
    if (state.held.values.none { it.by in ids }) return@mapNotNull null
    val job = robot.queue?.let { queue -> state.delivered.values.lastOrNull { it.kind in queue.order && roleOwns(robot.role, it.to) }?.id }
    Trigger.Release(file, job)
}

private const val ROBOT = "robot:"
