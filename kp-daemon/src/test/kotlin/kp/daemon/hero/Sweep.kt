package kp.daemon.hero

import kp.daemon.RuleFiles
import kp.daemon.Walk
import kp.daemon.checkout
import kp.engine.Datum
import kp.engine.Decision
import kp.engine.Outcome
import kp.rules.ArgSpec
import kp.rules.ArgType
import kp.rules.Comparison
import kp.rules.Destination
import kp.rules.Event
import kp.rules.Kind
import kp.rules.Literal
import kp.rules.Loaded
import kp.rules.Machine
import kp.rules.MessageField
import kp.rules.Operator
import kp.rules.PoolFile
import kp.rules.ReplyField
import kp.rules.Requirement
import kp.rules.RuleSet
import kp.rules.Rules
import kp.rules.Scope
import kp.rules.Transition
import kp.rules.Value
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText

/**
 * The refusal sweep (S1.6 T5), generated from the loaded rule set: one case per requirement of every transition and every
 * kind. For each message transition the generator finds a world in which its message is delivered and fires it — the
 * case's control, run through an office — then breaks only the one requirement and runs it again: the office must refuse
 * it, its `refused` record naming that requirement — its `rule` the requirement's owner (the kind for a kind's, the
 * transition for a transition's) and its `failed` the requirement in the rules' own form. A requirement that cannot be
 * broken alone — one written the same way as another rule's, judged first, among them — is an exception, with its
 * reason; a case that goes otherwise is a failure.
 */
class Sweep internal constructor(
    val onMaster: Int, val swept: Int, val exceptions: List<Exception>, val failures: List<String>,
    /** Each transition's control — the case that fires it — by its id; [uncontrolled] names those no case fires. */
    val controls: Map<String, Control>, val transitions: Int, val uncontrolled: List<String>,
) {
    /** A requirement that cannot be broken alone — its owner and place — why, and what it concerns (the other candidate, the robot, the arg). */
    data class Exception(val owner: String, val index: Int, val reason: String, val detail: String? = null) {
        val requirement: String get() = "$owner#$index"
    }

    /** The case that fires a transition: its world, what sets it going, and how the operations it asks for end. */
    class Control(val transition: String, val world: World, val trigger: World.Trigger, val results: Map<String, Boolean> = emptyMap()) {
        /** Whether the case fires its transition under the rules at [root]; false when they do not load. */
        fun fires(root: Path): Boolean = runCatching { transition in world.inOffice(root, trigger, results).fired }.getOrDefault(false)
    }

    /**
     * What taking a transition out of a copy of the rules does to its control: it still fires — the case stays green — or
     * the case goes red, and why (issue #37): the transition is absent and the rest of the rules load, or the copy
     * without it is refused by the loader — a kind's `fires`, an expiry's `{transition: …}`, the file's form names it.
     */
    data class Removal(val transition: String, val red: Boolean, val why: String, val problem: String? = null)

    /** Each transition with a control taken out of a copy of the rule set in turn, and its control run on the copy. */
    val removals: List<Removal> by lazy { removed() }

    /** The transitions whose removal from a copy of the rule set leaves their control green. */
    val removalSurvivors: List<String> get() = removals.filterNot { it.red }.map { it.transition }

    private fun removed(): List<Removal> = controls.values.map { control ->
        val copy = kp.daemon.TestOffice.copyOfTheRules()
        try {
            withoutTransition(copy, control.transition)
            when (val loaded = Rules.load(RuleFiles(copy).read())) {
                is Loaded.Invalid -> Removal(control.transition, red = true, NOT_LOADED, loaded.problems.firstOrNull()?.toString())
                is Loaded.Valid -> if (control.fires(copy)) Removal(control.transition, red = false, STILL_FIRES) else Removal(control.transition, red = true, ABSENT)
            }
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    /** The exceptions by reason, each with the requirements it covers: the list `just hero` prints. */
    fun reasons(): List<String> = exceptions.groupBy { it.reason }.map { (reason, items) ->
        val named = items.groupBy { it.owner to it.detail }.map { (key, same) ->
            val places = same.map { it.index }.sorted()
            val span = if (places.size > 1 && places.last() - places.first() == places.size - 1) "${places.first()}-${places.last()}" else places.joinToString(",")
            "${key.first}#$span" + (key.second?.let { " ($it)" } ?: "")
        }
        "${items.size} · $reason: ${named.joinToString(", ")}"
    }

    /** What `just hero` prints of the sweep: the reasons, then the counts — and why each removal turned its case red. */
    fun report(): List<String> = listOf("exceptions, each with its reason:") + reasons().map { "  $it" } +
        listOf("transitions no world fires: ${uncontrolled.size}" + (if (uncontrolled.isEmpty()) "" else " — ${uncontrolled.joinToString(", ")}"),
            "transitions whose removal from a copy of the rules leaves their case green: ${removalSurvivors.size}" + (if (removalSurvivors.isEmpty()) "" else " — ${removalSurvivors.joinToString(", ")}")) +
        removals.filter { it.red }.groupBy { it.why }.map { (why, red) -> "  red — $why: ${red.size}" + (if (why == NOT_LOADED) " — " + red.joinToString(", ") { it.transition } else "") } +
        listOf("requirements on master: $onMaster · swept: $swept · exceptions: ${exceptions.size} · swept + exceptions = ${swept + exceptions.size}")

    companion object {
        fun ofTheRulesOnMaster(root: Path = checkout): Sweep = Sweeper(root).run()

        /** Why a removal turned its case red, or left it green (issue #37). */
        const val ABSENT = "the transition is absent, and the rest of the rules load"
        const val NOT_LOADED = "the loader refuses the copy without it: a kind's `fires`, an expiry or the file's form names it"
        const val STILL_FIRES = "its case still fires it"

        /**
         * Every reason a requirement may be excepted for — a closed list (issue #37): the sweeper excepting one for any
         * other reason is a failure, as is a requirement no world breaks alone, or one refused naming another requirement.
         */
        val REASONS: List<String> = listOf(
            "written the same way as a requirement of its kind, which is judged first: breaking it breaks the kind's, and the refusal names the kind",
            "judged on an operation's result, which no message carries: nothing is refused — a result that breaks it fires nothing",
            "a later candidate from the same state: when none fires, the refusal names the first candidate's first failure (contracts.md §4.1)",
            "it tells two candidates from the same state apart: breaking it fires the other",
            "judged when a deadline passes, which no message carries: nothing is refused — an expiry that breaks it fires nothing",
            "the kind's args declaration accepts no value that breaks it: what would is refused as args first",
            "another machine moves on the same message from any state: it is delivered",
            "judged when a fact changes, which no message carries: nothing is refused — a fact that breaks it fires nothing",
            "breaking it means Bora is absent, and the Deputy holds the message until he is back: held, not refused",
        )

        /** Takes the transition [id] out of the copy of the rules at [root]: its item of `rules/stage-lifecycle.yaml`, every line of it. */
        fun withoutTransition(root: Path, id: String) {
            val file = root.resolve("rules/stage-lifecycle.yaml")
            val lines = file.readText().lines()
            val at = lines.indexOfFirst { it.trim().removePrefix("- ") == "id: $id" }
            check(at >= 0) { "no transition $id in $file" }
            val start = (at downTo 0).first { lines[it].trimStart().startsWith("- ") && (it == at || lines[it].indexOf('-') < lines[at].indexOf('i')) }
            val indent = lines[start].indexOf('-')
            val end = (start + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && lines[it].indexOfFirst { c -> c != ' ' } <= indent } ?: lines.size
            java.nio.file.Files.writeString(file, (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\n"))
        }
    }
}

/** One requirement on master: whose it is (a transition, or a kind), its place, its typed form and its file form. */
private class Owned(val owner: String, val index: Int, val requirement: Requirement, val form: Datum, val kind: Kind?, val transition: Transition?, val machine: Machine?) {
    val name: String get() = "$owner#${index + 1}"
}

private class Sweeper(private val root: Path) {
    private val rules: RuleSet = (Rules.load(RuleFiles(root).read()) as Loaded.Valid).ruleSet
    private val lifecycle = Walk.parse(root.resolve("rules/stage-lifecycle.yaml").readText()) as Datum.Fields
    private val kindsFile = ((Walk.parse(root.resolve("rules/message-kinds.yaml").readText()) as Datum.Fields).fields.getValue("kinds") as Datum.Fields).fields
    private val exceptions = ArrayList<Sweep.Exception>()
    private val failures = ArrayList<String>()
    private val controls = LinkedHashMap<String, World>()
    private var swept = 0

    /** Every requirement of every transition (each machine, in file order) and every kind (in file order). */
    private fun requirements(): List<Owned> {
        val forms = HashMap<String, List<Datum>>()
        fun take(transitions: Datum?) = (transitions as? Datum.Items)?.items.orEmpty().forEach { item ->
            val fields = (item as Datum.Fields).fields
            forms[(fields.getValue("id") as Datum.Text).value] = (fields["require"] as? Datum.Items)?.items.orEmpty()
        }
        take(lifecycle.fields["transitions"])
        lifecycle.fields.values.filterIsInstance<Datum.Fields>().forEach { take(it.fields["transitions"]) }
        val ofTransitions = rules.lifecycle.machines.values.flatMap { machine ->
            machine.transitions.flatMap { transition ->
                val written = forms.getValue(transition.id)
                check(written.size == transition.require.size) { "${transition.id}: the file and the loaded rule set differ" }
                transition.require.mapIndexed { index, requirement -> Owned(transition.id, index, requirement, written[index], transition.kind?.let(rules.kinds::get), transition, machine) }
            }
        }
        val ofKinds = rules.kinds.values.flatMap { kind ->
            val written = ((kindsFile.getValue(kind.name) as Datum.Fields).fields["require"] as? Datum.Items)?.items.orEmpty()
            kind.require.mapIndexed { index, requirement -> Owned(kind.name, index, requirement, written[index], kind, null, null) }
        }
        return ofTransitions + ofKinds
    }

    fun run(): Sweep {
        val all = requirements()
        all.forEach(::sweep)
        val transitions = rules.lifecycle.machines.values.flatMap { it.transitions }
        val cases = transitions.associate { it.id to caseOf(it) }
        return Sweep(all.size, swept, exceptions, failures, cases.filterValues { it != null }.mapValues { it.value!! }, transitions.size, cases.filterValues { it == null }.keys.toList())
    }

    /** The case that fires [transition] — a message, an operation's result, an expiry or a fact — checked through an office; null when none is found. */
    private fun caseOf(transition: Transition): Sweep.Control? {
        val kind = transition.kind?.let(rules.kinds::get)
        val found: List<Sweep.Control> = when (transition.on) {
            Event.MESSAGE -> listOfNotNull(control(kind!!, transition)?.let { Sweep.Control(transition.id, it, World.Trigger.Sent) })
            Event.OP_RESULT -> {
                // The result it is judged on — the first operation it names, also inside an `any`.
                fun operations(requirement: Requirement): List<Requirement.Operation> = when (requirement) {
                    is Requirement.Operation -> listOf(requirement)
                    is Requirement.Any -> requirement.items.flatMap(::operations)
                    else -> emptyList()
                }
                val asked = transition.require.flatMap(::operations).first()
                val results = mapOf(asked.operation to (asked.outcome == kp.rules.Outcome.OK))
                val rest = transition.require.filter { operations(it).isEmpty() }
                // The message counts as it is delivered: the counters the result reads are seeded one short as well as at their value.
                rules.lifecycle.machines.values.flatMap { it.transitions }.filter { it.kind == kind!!.name && it.on == Event.MESSAGE }
                    .sortedBy { if (it.effects.any { effect -> effect is kp.rules.Effect.Do && effect.operation == asked.operation } && it.to == transition.from) 0 else 1 }
                    .mapNotNull { message -> control(kind!!, message) }
                    // A result that moves another machine than the stage's finds its subject where it starts from.
                    .map { world -> if (rules.lifecycle.stage.transitions.contains(transition)) world else placed(world, transition) }
                    .flatMap { world -> listOf(world, satisfied(rest, world)) + shortOf(rest, world) }.distinct()
                    .map { Sweep.Control(transition.id, it, World.Trigger.Sent, results) }
            }
            Event.EXPIRY -> listOfNotNull(expiring(kind!!, transition))
            Event.FACT -> listOfNotNull(reported(transition))
        }
        return found.firstOrNull { it.fires(root) }
    }

    /** [world] with each counter [requirements] read seeded one below a value that makes it hold. */
    private fun shortOf(requirements: List<Requirement>, world: World): List<World> = requirements.filterIsInstance<Requirement.Counter>().flatMap { counter ->
        counter(counter.name, counter.comparison, holds = true, world).mapNotNull { made ->
            val seeded = made.seeds.filterIsInstance<Seed.Move>().lastOrNull { it.counted.containsKey(counter.name) } ?: return@mapNotNull made
            val short = seeded.counted.getValue(counter.name) - 1
            made.copy(seeds = made.seeds.map { if (it === seeded) seeded.copy(counted = mapOf(counter.name to short)) else it }.filterNot { it is Seed.Move && it.counted[counter.name] == 0L })
        }
    }

    /** A message of [kind] waiting past its deadline in the state [transition] starts from, the expiry's `if` made to hold. */
    private fun expiring(kind: Kind, transition: Transition): Sweep.Control {
        val waiting = Seed.Sent("waiting", kind.name, seatOf(kind.from.first()), (kind.to as? Destination.Parties)?.names?.first()?.let(::seatOf) ?: "teamlead",
            messageClass = kind.classRule.default.name, deadline = "2026-11-17T08:30:00Z")
        val gate = kind.expiry.filterIsInstance<kp.rules.Effect.Transition>().firstOrNull { it.id == transition.id }?.condition
        var world = placed(World(kind.name, seatOf(kind.from.first())), transition)
        world = if (world.re != null) world.copy(seeds = world.seeds.map { if (it is Seed.Thread) it.copy(key = "waiting") else it }.filterNot { it is Seed.Sent && it.key == world.re }.let { listOf<Seed>(waiting) + it }, re = null)
            else world.seeded(waiting)
        world = satisfied(transition.require + listOfNotNull(gate), world)
        return Sweep.Control(transition.id, world, World.Trigger.Expiry("waiting"))
    }

    /** The fact [transition] is judged on, reported with a value that makes its requirement hold; Bora's acting is a signed command. */
    private fun reported(transition: Transition): Sweep.Control? {
        val requirement = transition.require.filterIsInstance<Requirement.Fact>().firstOrNull() ?: return null
        val machine = rules.lifecycle.machines.values.first { transition in it.transitions }
        val world = placed(World("away", World.BORA), transition).let { if (machine.name == "presence" && transition.from != machine.states.keys.first()) it else it }
        if (requirement.fact.name == "bora.acted") return Sweep.Control(transition.id, world, World.Trigger.Acted)
        val value = values(requirement.comparison?.operator, requirement.comparison?.value?.let { value(it, world) }, holds = true).firstOrNull() ?: return null
        return Sweep.Control(transition.id, world, World.Trigger.Changed(requirement.fact.name, value))
    }

    /** An exception, for a reason of the closed list only: any other is a failure (issue #37). */
    private fun except(owned: Owned, reason: String, detail: String? = null) {
        if (reason !in Sweep.REASONS) failures += "${owned.name} ${owned.form.json()}: $reason" + (detail?.let { " ($it)" } ?: "")
        else exceptions += Sweep.Exception(owned.owner, owned.index + 1, reason, detail)
    }

    private fun sweep(owned: Owned) {
        val transition = owned.transition
        when (transition?.on) {
            Event.OP_RESULT -> return except(owned, "judged on an operation's result, which no message carries: nothing is refused — a result that breaks it fires nothing")
            Event.EXPIRY -> return except(owned, "judged when a deadline passes, which no message carries: nothing is refused — an expiry that breaks it fires nothing")
            Event.FACT -> return except(owned, "judged when a fact changes, which no message carries: nothing is refused — a fact that breaks it fires nothing")
            else -> Unit
        }
        val kind = owned.kind ?: return failures.plusAssign("${owned.name}: no kind")
        val targets = if (transition != null) listOf(transition) else rules.lifecycle.machines.values.flatMap { it.transitions }.filter { it.kind == kind.name && it.on == Event.MESSAGE }
            .sortedBy { if (controls.containsKey(it.id)) 0 else 1 }
        val control = if (targets.isEmpty()) control(kind, null) else targets.firstNotNullOfOrNull { control(kind, it) }
        if (control == null) return except(owned, "no world delivers its message and fires its transition: it cannot hold with the rest", targets.joinToString(" or ") { it.id }.ifEmpty { null })
        val broken = breakOnly(owned, control)
        // Only an arg can be unbreakable for its declaration; any other form no world breaks is one the sweep builds no world for (issue #37).
        if (broken == null && owned.requirement is Requirement.Arg) return except(owned, "the kind's args declaration accepts no value that breaks it: what would is refused as args first",
            owned.requirement.name)
        if (broken == null) return except(owned, "no world breaks it alone: the sweep builds none for its form")
        val decision = broken.judge(rules)
        if (names(decision, owned)) {
            val outcome = broken.inOffice(root)
            when {
                outcome.refused?.let { it.rule == owned.owner && it.failed?.toString() == World.json(owned.form).toString() } == true -> swept++
                outcome.held != null -> except(owned, "breaking it means Bora is absent, and the Deputy holds the message until he is back: held, not refused", outcome.held!!.by.removePrefix("robot:"))
                else -> failures += "${owned.name} ${owned.form.json()}: the engine refuses it, the office: $outcome"
            }
            return
        }
        explain(owned, broken, decision).let { (reason, detail) -> except(owned, reason, detail) }
    }

    /** Whether [decision] refuses naming [owned]: its owner the rule that refused, its file form the failure. */
    private fun names(decision: Decision, owned: Owned): Boolean =
        decision.outcome == Outcome.REFUSE && decision.rule == owned.owner && decision.failed == owned.form

    /** Why a world that breaks only [owned] is not refused naming it. */
    private fun explain(owned: Owned, broken: World, decision: Decision): Pair<String, String?> {
        val transition = owned.transition
        // The kind's `require` holds for every transition of the kind (contracts.md §4.2), and the engine judges it before the
        // candidates: a transition's copy of one of its requirements breaks with it, and the refusal names the kind.
        if (decision.outcome == Outcome.REFUSE && transition != null && decision.rule == transition.kind && decision.failed == owned.form)
            return "written the same way as a requirement of its kind, which is judged first: breaking it breaks the kind's, and the refusal names the kind" to transition.kind
        if (decision.outcome == Outcome.REFUSE && transition != null) {
            val first = candidates(broken, owned.machine!!, transition.kind!!).firstOrNull()
            if (first != null && first.id != transition.id && decision.rule == first.id)
                return "a later candidate from the same state: when none fires, the refusal names the first candidate's first failure (contracts.md §4.1)" to first.id
        }
        if (decision.outcome == Outcome.DELIVER) {
            val others = decision.moves.map { it.rule }
            val own = owned.machine?.name
            val elsewhere = others.mapNotNull { rule -> rules.lifecycle.machines.values.first { machine -> machine.transitions.any { it.id == rule } }.let { machine -> machine.transitions.first { it.id == rule }.takeIf { machine.name != own && it.from == "*" } } }
            if (elsewhere.isNotEmpty()) return "another machine moves on the same message from any state: it is delivered" to elsewhere.joinToString { it.id }
            return "it tells two candidates from the same state apart: breaking it fires the other" to others.joinToString()
        }
        return "refused, but naming another requirement" to "${decision.rule} ${decision.failed?.json() ?: decision.requirement}"
    }

    // ---- the world a message is delivered in

    /**
     * A world in which a message of [kind] is delivered and fires [transition] (any delivery when null), found by
     * judging in memory and making the requirement that refused it hold, until none does; then run through an office.
     */
    private fun control(kind: Kind, transition: Transition?): World? {
        transition?.let { controls[it.id] }?.let { return it }
        var world = satisfied(kind.require + transition?.require.orEmpty(), start(kind, transition))
        repeat(SEARCH) {
            val decision = world.judge(rules)
            when (decision.outcome) {
                Outcome.REFUSE -> world = fix(world, kind, transition, decision) ?: return null
                else -> {
                    if (transition != null && decision.moves.none { it.rule == transition.id }) {
                        world = parkEarlier(world, kind, transition) ?: parkedFor(transition, world) ?: return null
                    } else {
                        val outcome = world.inOffice(root)
                        val fired = transition == null || transition.id in outcome.fired
                        if (!outcome.delivered || !fired) {
                            failures += "control of ${transition?.id ?: kind.name}: the engine delivers it in memory, the office: $outcome"
                            return null
                        }
                        transition?.let { controls[it.id] = world }
                        return world
                    }
                }
            }
        }
        return null
    }

    private fun start(kind: Kind, transition: Transition?): World {
        val sender = senderOf(kind)
        var world = World(kind.name, sender, signed = sender == World.BORA, args = requiredArgs(kind))
        if (transition != null) world = placed(world, transition)
        if (kind.to is Destination.Reply && world.re == null) world = replyingTo(world, defaultEarlier(kind))
        return world
    }

    /** The world with [transition]'s machine's subject in the state it starts from. */
    private fun placed(world: World, transition: Transition): World {
        val machine = rules.lifecycle.machines.values.first { transition in it.transitions }
        val from = concrete(machine, transition.from)
        return when (machine.name) {
            "stage" -> world.copy(stageState = from)
            "seat" -> {
                val seat = (world.args["seat"] as? Datum.Text)?.value ?: "coder-1"
                if ("seat" in (rules.kinds[world.kind]?.args ?: emptyMap())) world.copy(args = world.args + ("seat" to Datum.Text(seat)))
                    .let { if (from != machine.states.keys.first()) it.seeded(Seed.Move("seat", seat, machine.states.keys.first(), from)) else it }
                // A message that names no seat moves each seat bound to its stage (contracts.md §2.2): this one, bound by an assign of the stage.
                else world.seeded(Seed.Sent("bound", "assign", "teamlead", seat, mapOf("seat" to Datum.Text(seat))))
                    .seeded(Seed.Move("seat", seat, machine.states.keys.first(), from, cause = "bound"))
            }
            "found_item" -> {
                val found = world.args + ("found" to Datum.Text("F1"))
                world.copy(args = found).let { if (from != machine.states.keys.first()) it.seeded(Seed.Move("found", "$CASE_STAGE#F1", machine.states.keys.first(), from)) else it }
            }
            "flag_thread" -> {
                val replied = if (world.re == null) replyingTo(world, defaultEarlier(rules.kinds.getValue(world.kind))) else world
                if (from != machine.states.keys.first()) replied.seeded(Seed.Thread(replied.re!!, from)) else replied
            }
            "phase" -> if (from != machine.states.keys.first()) world.seeded(Seed.Move("phase", "SWEEP/P1", machine.states.keys.first(), from)) else world
            "presence" -> if (from != machine.states.keys.first()) world.seeded(Seed.Move("presence", "bora", machine.states.keys.first(), from)) else world
            else -> world
        }
    }

    private fun concrete(machine: Machine, state: String): String = when (state) {
        "*" -> machine.states.keys.first()
        "*active" -> rules.lifecycle.activeStates.first()
        else -> state
    }

    /** An earlier candidate of the same machine fired first: break the first of its requirements [transition] does not have. */
    private fun parkEarlier(world: World, kind: Kind, transition: Transition): World? {
        val machine = rules.lifecycle.machines.values.first { transition in it.transitions }
        val earlier = candidates(world, machine, kind.name).takeWhile { it.id != transition.id }
        val decision = world.judge(rules)
        val fired = earlier.firstOrNull { candidate -> decision.moves.any { it.rule == candidate.id } } ?: return null
        val own = transition.require.toSet()
        return fired.require.filter { it !in own }.firstNotNullOfOrNull { make(it, holds = false, world).firstOrNull() }
    }

    /** The candidates of [machine] for a message of [kind] in [world], in file order (contracts.md §4.1). */
    private fun candidates(world: World, machine: Machine, kind: String): List<Transition> {
        val state = when (machine.name) {
            "stage" -> world.stageState
            else -> null
        }
        return machine.transitions.filter { it.kind == kind && it.on == Event.MESSAGE && (state == null || it.from == state || it.from == "*" || (it.from == "*active" && state in rules.lifecycle.activeStates)) }
    }

    /** [requirements] made to hold in turn, each the first way that does; one that cannot is left as it is. */
    private fun satisfied(requirements: List<Requirement>, world: World): World =
        requirements.fold(world) { at, requirement -> make(requirement, holds = true, at).firstOrNull() ?: at }

    /** [world] with every machine but [transition]'s that moves on its message parked, so that its refusal names [transition]'s failure. */
    private fun parkedFor(transition: Transition, world: World): World? {
        val machine = rules.lifecycle.machines.values.first { transition in it.transitions }
        return parked(Owned(transition.id, 0, Requirement.Always(true), Datum.Null, rules.kinds[transition.kind], transition, machine), world)?.takeIf { it != world }
    }

    /**
     * One step of the search: the refusal's cause made to hold — the kind's or the target's requirement. A refusal that
     * names an earlier candidate's failure says only that the target did not fire: its requirements are made to hold again.
     */
    private fun fix(world: World, kind: Kind, transition: Transition?, decision: Decision): World? {
        val failed = decision.failed
        if (failed != null) {
            val own = (kind.require.zip(forms(kind.name)) + transition?.let { it.require.zip(forms(it.id)) }.orEmpty()).firstOrNull { it.second == failed }?.first
            if (own == null && transition != null && decision.rule != transition.id) return satisfied(transition.require, world).takeIf { it != world }
            val requirement = own ?: known(kind, transition, failed) ?: return null
            return make(requirement, holds = true, world).firstOrNull { candidate -> candidate.judge(rules).failed != failed }
        }
        val why = decision.requirement.orEmpty()
        return when {
            why.startsWith("from:") -> world.copy(sender = senderOf(kind))
            why.startsWith("args:") -> world.copy(args = requiredArgs(kind) + world.args.filterKeys { it in kind.args && valid(world.args.getValue(it), kind.args.getValue(it)) })
            why.startsWith("to:") -> when (kind.to) {
                is Destination.Reply -> replyingTo(world, defaultEarlier(kind))
                Destination.AnySeat -> world.copy(args = world.args + ("seat" to Datum.Text("coder-1")))
                is Destination.Parties -> world.copy(to = (kind.to as Destination.Parties).names.first())
            }
            else -> null
        }
    }

    /** The requirement whose file form is [failed]: the kind's, the transition's, or an earlier candidate's. */
    private fun known(kind: Kind, transition: Transition?, failed: Datum): Requirement? {
        val pool = kind.require.zip(forms(kind.name)) + rules.lifecycle.machines.values.flatMap { it.transitions }
            .filter { it.kind == kind.name || it == transition }.flatMap { it.require.zip(forms(it.id)) }
        return pool.firstOrNull { it.second == failed }?.first
    }

    private val formCache = HashMap<String, List<Datum>>()
    private fun forms(owner: String): List<Datum> = formCache.getOrPut(owner) {
        kindsFile[owner]?.let { ((it as Datum.Fields).fields["require"] as? Datum.Items)?.items.orEmpty() }
            ?: (listOf(lifecycle.fields["transitions"]) + lifecycle.fields.values.filterIsInstance<Datum.Fields>().map { it.fields["transitions"] })
                .flatMap { (it as? Datum.Items)?.items.orEmpty() }.map { (it as Datum.Fields).fields }
                .firstOrNull { it["id"] == Datum.Text(owner) }?.let { (it["require"] as? Datum.Items)?.items.orEmpty() }.orEmpty()
    }

    /**
     * The control with only [owned] broken: each way of breaking it, until the engine refuses naming it — else one refused
     * with its form under another rule's name, one refused at all, the first.
     * When another machine still moves on the message — a seat, a phase, a thread the requirement is not about — its
     * subject is first put where nothing of the kind leaves it, so that the message is judged on [owned]'s machine alone.
     */
    private fun breakOnly(owned: Owned, control: World): World? {
        val ways = make(owned.requirement, holds = false, control).flatMap { listOfNotNull(it, parked(owned, it)) }
        val judged = ways.map { it to it.judge(rules) }
        return (judged.firstOrNull { (_, decision) -> names(decision, owned) }
            ?: judged.firstOrNull { (_, decision) -> decision.outcome == Outcome.REFUSE && decision.failed == owned.form }
            ?: judged.firstOrNull { (_, decision) -> decision.outcome == Outcome.REFUSE } ?: judged.firstOrNull())?.first
    }

    /** [world] with every machine but [owned]'s that moves on its message put in a state no transition of the kind leaves; null when one cannot be. */
    private fun parked(owned: Owned, world: World): World? {
        val decision = world.judge(rules)
        if (decision.outcome != Outcome.DELIVER) return null
        val own = owned.machine?.name
        return decision.moves.filter { move -> rules.lifecycle.machines.values.first { machine -> machine.transitions.any { it.id == move.rule } }.name != own }
            .fold(world as World?) { at, move ->
                val machine = rules.lifecycle.machines.values.first { machine -> machine.transitions.any { it.id == move.rule } }
                val leaving = machine.transitions.filter { it.kind == world.kind && it.on == Event.MESSAGE }.map { it.from }
                if ("*" in leaving) return null
                val still = machine.states.keys.firstOrNull { state -> state !in leaving && !(state in rules.lifecycle.activeStates && "*active" in leaving) } ?: return null
                at?.let { park(it, machine, move.subject, still) }
            }
    }

    private fun park(world: World, machine: Machine, subject: String, state: String): World = when (machine.name) {
        "stage" -> world.copy(stageState = state)
        "flag_thread" -> world.copy(seeds = world.seeds.filterNot { it is Seed.Thread }).let { clean -> clean.re?.let { clean.seeded(Seed.Thread(it, state)) } ?: clean }
        else -> {
            val record = if (machine.name == "found_item") "found" else machine.name
            val clean = world.copy(seeds = world.seeds.filterNot { it is Seed.Move && it.machine == record && it.subject == subject && it.counted.isEmpty() })
            if (state == machine.states.keys.first()) clean else clean.seeded(Seed.Move(record, subject, machine.states.keys.first(), state))
        }
    }

    // ---- making a requirement hold, or fail

    /** Worlds in which [requirement] holds ([holds]) or fails, changed from [world] only in what it reads. */
    private fun make(requirement: Requirement, holds: Boolean, world: World): List<World> = when (requirement) {
        is Requirement.Fact -> fact(requirement, holds, world)
        is Requirement.Arg -> arg(requirement.name, requirement.comparison, holds, world)
        is Requirement.Message -> message(requirement.field, requirement.comparison, holds, world)
        is Requirement.Reply -> reply(requirement.field, requirement.comparison, holds, world)
        is Requirement.Counter -> counter(requirement.name, requirement.comparison, holds, world)
        is Requirement.Attest -> attest(requirement.name, holds, world)
        is Requirement.Signed -> listOf(world.copy(sender = World.BORA, signed = holds))
        is Requirement.Any -> if (holds) requirement.items.flatMap { make(it, true, world) }
            else requirement.items.fold(listOf(world)) { worlds, item -> worlds.flatMap { make(item, false, it) }.take(WAYS) }
        is Requirement.Not -> make(requirement.item, !holds, world)
        is Requirement.Holds -> rules.engagement.blocks[requirement.block].orEmpty().let { block ->
            if (holds) block.fold(listOf(world)) { worlds, item -> worlds.flatMap { make(item, true, it).take(1) } } else block.flatMap { make(it, false, world) }
        }
        is Requirement.Operation, is Requirement.Always -> emptyList()
    }

    private fun fact(requirement: Requirement.Fact, holds: Boolean, world: World): List<World> {
        val call = requirement.fact
        val comparison = requirement.comparison
        val known = params(call.params, world)
        logFact(call.name, call.params, comparison, holds, known)?.let { return it }
        val prepared = comparison?.value?.let { prepare(it, known, comparison.operator) } ?: known
        val target = comparison?.value?.let { value(it, prepared) }
        if (comparison != null && target == null) return emptyList()
        val key = KEYS.firstNotNullOfOrNull { name -> call.params[name]?.let { value(it, prepared) } }?.let { (it as? Datum.Text)?.value ?: it.json() }
        return values(comparison?.operator, target, holds).map { value ->
            val existing = prepared.facts[call.name]
            val table = if (key == null) value else Datum.Fields(((existing as? Datum.Fields)?.fields ?: emptyMap()) + (key to value))
            prepared.copy(facts = prepared.facts + (call.name to table))
        }
    }

    /** The facts the engine answers from the log (source L), made so by what the log holds. Null: a fact of the table. */
    private fun logFact(name: String, params: Map<String, Value>, comparison: Comparison?, holds: Boolean, world: World): List<World>? {
        fun truth(): Boolean = if (comparison == null) holds else (comparison.value as? Value.Constant)?.literal == Literal.Bool(true) == holds
        return when (name) {
            "bora.present" -> listOf(if (truth()) world.copy(seeds = world.seeds.filterNot { it is Seed.Move && it.machine == "presence" })
                else world.seeded(Seed.Move("presence", "bora", "present", "absent")))
            "stage.state" -> {
                val target = (comparison!!.value as Value.Constant).literal.let { (it as Literal.Text).value }
                val states = rules.lifecycle.stage.states.keys
                when (comparison.operator) {
                    Operator.EQ -> if (holds) listOf(world.copy(stageState = target)) else states.filter { it != target }.map { world.copy(stageState = it) }.take(WAYS)
                    else -> emptyList()
                }
            }
            "queue.names" -> world.without("queue").let { clean ->
                listOf(if (holds) clean.seeded(Seed.Sent("queue", "queue-approval", World.BORA, "teamlead", mapOf("stages" to Datum.Items(listOf(Datum.Text(CASE_STAGE)))), "B", signed = true, stage = null)) else clean)
            }
            "request.open" -> {
                val kind = (params["kind"] as Value.Constant).literal.let { (it as Literal.Text).value }
                val clean = world.without("open-$kind")
                listOf(if (truth()) clean.seeded(Seed.Sent("open-$kind", kind, rules.kinds.getValue(kind).from.first().let(::seatOf), "qa")) else clean)
            }
            "log.count" -> {
                val kind = ((params["kind"] as Value.Constant).literal as Literal.Text).value
                val scope = (params["scope"] as? Value.Constant)?.literal?.let { (it as Literal.Text).value } ?: "stage"
                val where = (params["where"] as? Value.Fields)?.fields.orEmpty()
                val prepared = where.values.fold(world.without("count-$kind")) { at, value -> prepare(value, at) }
                val args = where.mapValues { value(it.value, prepared) ?: Datum.Text("x") }
                val one = prepared.seeded(Seed.Sent("count-$kind", kind, rules.kinds.getValue(kind).from.first().let(::seatOf), "principal", args, stage = if (scope == "office") null else CASE_STAGE))
                val wanted = values(comparison?.operator, comparison?.value?.let { value(it, prepared) }, holds).mapNotNull { (it as? Datum.Number)?.value?.toInt() }
                wanted.map { if (it > 0) one else prepared }.distinct()
            }
            "thread.state" -> {
                val state = ((comparison!!.value as Value.Constant).literal as Literal.Text).value
                val replied = if (world.re == null) replyingTo(world, defaultEarlier(rules.kinds.getValue(world.kind))) else world
                val others = rules.lifecycle.companions.getValue("flag_thread").states.keys.filter { it != state }
                val clean = replied.copy(seeds = replied.seeds.filterNot { it is Seed.Thread })
                if (holds) listOf(clean.seeded(Seed.Thread(replied.re!!, state))) else others.map { if (it == "asked") clean else clean.seeded(Seed.Thread(replied.re!!, it)) }
            }
            "seat.state" -> {
                val seat = params["seat"]?.let { value(it, world) as? Datum.Text }?.value ?: return emptyList()
                val state = ((comparison!!.value as Value.Constant).literal as Literal.Text).value
                val machine = rules.lifecycle.companions.getValue("seat")
                val first = machine.states.keys.first()
                val clean = world.copy(seeds = world.seeds.filterNot { it is Seed.Move && it.machine == "seat" && it.subject == seat })
                val wanted = if (holds) listOf(state) else machine.states.keys.filter { it != state }.take(WAYS)
                wanted.map { if (it == first) clean else clean.seeded(Seed.Move("seat", seat, first, it)) }
            }
            "seat.inbox_replayed" -> {
                val seat = params["seat"]?.let { value(it, world) as? Datum.Text }?.value ?: return emptyList()
                listOf(if (truth()) world.copy(seeds = world.seeds.filterNot { it is Seed.Sent && !it.read }) else world.seeded(Seed.Sent("unread", "nudge", "mailroom", seat, mapOf("rule" to Datum.Text("x"), "failed_guard" to Datum.Text("x")), read = false)))
            }
            "refusals.today" -> {
                val seat = params["seat"]?.let { value(it, world) as? Datum.Text }?.value ?: return emptyList()
                val target = comparison?.value?.let { value(it, world) }
                values(comparison?.operator, target, holds).mapNotNull { (it as? Datum.Number)?.value?.toLong() }.filter { it >= 0 }
                    .map { count -> world.copy(seeds = world.seeds.filterNot { it is Seed.Refusals }).let { if (count > 0) it.seeded(Seed.Refusals(seat, count)) else it } }
            }
            "review.open_blockers", "bora.absent_since", "thread.open", "thread.hops", "phase.state" -> emptyList()
            else -> null
        }
    }

    /** A fact's parameters made known: an arg the kind declares given a value, a fact given one. */
    private fun params(params: Map<String, Value>, world: World): World = params.values.fold(world) { at, param ->
        when (param) {
            is Value.Arg -> if (value(param, at) != null) at else rules.kinds.getValue(at.kind).args[param.path]?.let { at.copy(args = at.args + (param.path to sample(it, param.path))) } ?: at
            is Value.Fact -> prepare(param, at)
            is Value.Fields -> params(param.fields, at)
            is Value.Items -> params(param.items.withIndex().associate { "${it.index}" to it.value }, at)
            else -> at
        }
    }

    /** What a value reads first made known: the log's open blockers, Bora's absence, a fact of the table — a list where `in` compares against it. */
    private fun prepare(value: Value, world: World, operator: Operator? = null): World = when {
        value is Value.Fact && value.fact.name !in LOG && world.facts[value.fact.name] == null && operator == Operator.IN ->
            params(value.fact.params, world).let { at -> at.copy(facts = at.facts + (value.fact.name to Datum.Items(listOf(Datum.Text("${value.fact.name}-1"))))) }
        value is Value.Fact && value.fact.name == "review.open_blockers" && world.seeds.none { it is Seed.Sent && it.key == "verdict" } ->
            world.seeded(Seed.Sent("verdict", "verdict", "reviewer-1", "coder-1", mapOf("verdict" to Datum.Text("FIX FIRST"), "sha" to Datum.Text("head-1"),
                "blockers" to Datum.Items(listOf(Datum.Fields(mapOf("id" to Datum.Text("B1"))))), "follow_ups" to Datum.Items(emptyList()))))
        value is Value.Fact && value.fact.name == "bora.absent_since" && world.seeds.none { it is Seed.Move && it.machine == "presence" } ->
            world.seeded(Seed.Move("presence", "bora", "present", "absent"))
        value is Value.Fact && value.fact.name !in LOG && world.facts[value.fact.name] == null && value(value, world) == null ->
            fact(Requirement.Fact(value.fact, null), true, params(value.fact.params, world)).firstOrNull()?.let { made ->
                // A fact read as a value is given a value of its own: one, or a time, or a name.
                made.copy(facts = made.facts + (value.fact.name to retype(made.facts.getValue(value.fact.name), value.fact.name)))
            } ?: world
        else -> world
    }

    private fun retype(datum: Datum, name: String): Datum = when {
        datum is Datum.Fields -> Datum.Fields(datum.fields.mapValues { retype(it.value, name) })
        name.endsWith("_at") || name.endsWith("since") -> Datum.Text(EARLIER)
        name.contains("rank") || name.contains("count") -> Datum.Number(BigDecimal.ONE)
        name == "pr.head" -> Datum.Text("head-1")
        else -> Datum.Text("$name-value")
    }

    private fun arg(name: String, comparison: Comparison?, holds: Boolean, world: World): List<World> {
        val spec = rules.kinds.getValue(world.kind).args[name] ?: return if (holds) emptyList() else listOf(world.copy(args = world.args - name))
        val prepared = comparison?.value?.let { prepare(it, world, comparison.operator) } ?: world
        val candidates: List<Datum?> = when {
            comparison == null && holds -> listOf(sample(spec, name))
            comparison == null -> listOfNotNull(null.takeIf { !spec.required }, empty(spec)).ifEmpty { if (!spec.required) listOf(null) else emptyList() }
            else -> {
                val target = value(comparison.value, prepared) ?: return emptyList()
                values(comparison.operator, target, holds) + spec.enum?.map(::literal).orEmpty() + listOfNotNull(sample(spec), empty(spec))
            }
        }
        return candidates.filter { it == null || valid(it, spec) }.filter { candidate ->
            comparison == null || candidate == null || holdsCompare(candidate, comparison.operator, value(comparison.value, prepared)!!) == holds
        }.map { value -> prepared.copy(args = if (value == null) prepared.args - name else prepared.args + (name to value)) }.distinct().take(WAYS)
    }

    private fun attest(name: String, holds: Boolean, world: World): List<World> {
        val now = (world.args["attest"] as? Datum.Items)?.items.orEmpty()
        val next = if (holds) (now + Datum.Text(name)).distinct() else now - Datum.Text(name)
        return listOf(world.copy(args = if (next.isEmpty()) world.args - "attest" else world.args + ("attest" to Datum.Items(next))))
    }

    private fun counter(name: String, comparison: Comparison?, holds: Boolean, world: World): List<World> {
        val target = comparison?.value?.let { value(it, world) }
        val scope = rules.lifecycle.counters[name]?.scope ?: Scope.STAGE
        val (machine, subject) = when (scope) {
            Scope.SEAT -> "seat" to ((world.args["seat"] as? Datum.Text)?.value ?: world.sender)
            Scope.EFFORT -> "phase" to CASE_STAGE.substringBefore('/')
            Scope.STAGE -> "stage" to CASE_STAGE
        }
        return values(comparison?.operator, target, holds).mapNotNull { (it as? Datum.Number)?.value?.toLong() }.filter { it >= 0 }.map { count ->
            val clean = world.copy(seeds = world.seeds.filterNot { it is Seed.Move && it.counted.containsKey(name) })
            if (count == 0L) clean else {
                val state = if (machine == "stage") clean.stageState else clean.seeds.filterIsInstance<Seed.Move>().lastOrNull { it.machine == machine && it.subject == subject }?.to
                    ?: rules.lifecycle.machines.values.first { it.name == machine }.states.keys.first()
                clean.seeded(Seed.Move(machine, subject, state, state, mapOf(name to count)))
            }
        }
    }

    private fun message(field: MessageField, comparison: Comparison?, holds: Boolean, world: World): List<World> {
        val target = comparison?.value?.let { value(it, world) } ?: return emptyList()
        return values(comparison.operator, target, holds).mapNotNull { (it as? Datum.Text)?.value }.mapNotNull { value ->
            when (field) {
                MessageField.FROM -> world.copy(sender = value)
                MessageField.TO -> world.copy(to = value)
                else -> null
            }
        }
    }

    private fun reply(field: ReplyField, comparison: Comparison?, holds: Boolean, world: World): List<World> {
        val target = comparison?.value?.let { value(it, world) } ?: return emptyList()
        val base = world.seeds.filterIsInstance<Seed.Sent>().firstOrNull { it.key == world.re } ?: defaultEarlier(rules.kinds.getValue(world.kind))
        val wanted = values(comparison.operator, target, holds).mapNotNull { (it as? Datum.Text)?.value }.flatMap { value ->
            // A kind that breaks it must be a kind: any other of the rules', the replied kinds first.
            when {
                field == ReplyField.KIND && value !in rules.kinds -> rules.kinds.keys.filter { holdsCompare(Datum.Text(it), comparison.operator, target) == holds }
                    .sortedBy { kind -> if (rules.kinds.getValue(kind).replies.isNotEmpty()) 0 else 1 }.take(WAYS)
                // A class is A, B or C.
                field == ReplyField.CLASS && value !in CLASSES -> CLASSES.filter { holdsCompare(Datum.Text(it), comparison.operator, target) == holds }
                else -> listOf(value)
            }
        }
        return wanted.mapNotNull { value ->
            val earlier = when (field) {
                ReplyField.KIND -> rules.kinds[value]?.let { base.copy(kind = value, from = seatOf(it.from.first()), messageClass = if (base.key == "re" && world.re != null) base.messageClass else it.classRule.default.name) }
                ReplyField.CLASS -> base.copy(messageClass = value)
                ReplyField.FROM -> base.copy(from = value)
                ReplyField.TO -> base.copy(to = value)
            }
            earlier?.let { replyingTo(world, it) }
        }
    }

    /** [world] answering [earlier]: the earlier message seeded, the world's `re` naming it. */
    private fun replyingTo(world: World, earlier: Seed.Sent): World {
        val clean = world.copy(seeds = world.seeds.filterNot { it is Seed.Sent && it.key == earlier.key || it is Seed.Thread && it.key == earlier.key })
        val threads = world.seeds.filterIsInstance<Seed.Thread>().filter { it.key == world.re }.map { it.copy(key = earlier.key) }
        return (threads.fold(clean.seeded(earlier)) { at, thread -> at.seeded(thread) }).copy(re = earlier.key)
    }

    /** What a message of [kind] answers when it must answer something: a message of a kind whose `replies` lists it, else any. */
    private fun defaultEarlier(kind: Kind): Seed.Sent {
        val asked = rules.kinds.values.firstOrNull { kind.name in it.replies }
        val asker = asked?.from?.first()?.let(::seatOf) ?: "teamlead"
        return Seed.Sent("re", asked?.name ?: "started", asker, seatOf(kind.from.first()), messageClass = asked?.classRule?.default?.name ?: "A")
    }

    // ---- values

    /** A value as the engine would read it in [world]; null when unknown. */
    private fun value(value: Value, world: World): Datum? = when (value) {
        is Value.Constant -> literal(value.literal)
        is Value.Items -> value.items.map { value(it, world) ?: return null }.let(Datum::Items)
        is Value.Fields -> value.fields.mapValues { value(it.value, world) ?: return null }.let(Datum::Fields)
        is Value.Cap -> rules.engagement.caps[value.name]?.let(::literal)
        is Value.Arg -> value.path.split('.').fold(Datum.Fields(world.args) as Datum?) { at, key -> (at as? Datum.Fields)?.fields?.get(key) }
        is Value.Fact -> when (value.fact.name) {
            "review.open_blockers" -> world.seeds.filterIsInstance<Seed.Sent>().lastOrNull { it.args["blockers"] != null }
                ?.let { sent -> Datum.Items((sent.args.getValue("blockers") as Datum.Items).items.mapNotNull { (it as? Datum.Fields)?.fields?.get("id") }) } ?: Datum.Items(emptyList())
            "bora.absent_since" -> if (world.seeds.any { it is Seed.Move && it.machine == "presence" }) Datum.Text(EARLIER) else null
            else -> world.observe(kp.engine.FactRef(value.fact.name, value.fact.params.mapValues { value(it.value, world) ?: return null }))
        }
        is Value.Message -> when (value.field) {
            MessageField.FROM -> Datum.Text(world.sender)
            MessageField.STAGE -> Datum.Text(CASE_STAGE)
            MessageField.BY -> Datum.Text(if (world.sender == World.BORA) "bora-cli" else "seat:${world.sender}")
            MessageField.TO -> world.to?.let(Datum::Text)
        }
        is Value.Reply -> null
        is Value.Pool -> rules.pool.values[value.file].orEmpty().let { if (value.file == PoolFile.OFFICE) it.getOrNull(rules.pool.offices.keys.indexOf("panda")) else it.singleOrNull() }?.get(value.path)?.let(::literal)
        is Value.Engagement -> rules.engagement.values[value.path]?.let(::literal)
        // Outside a robot, a threshold of the robot file of the kind's own sender (contracts.md §4.1).
        is Value.Robot -> rules.robots.values.firstOrNull { world.sender == it.role || world.sender.startsWith("${it.role}-") }?.thresholds?.get(value.threshold)?.let(::literal)
        is Value.Trigger -> null
    }

    /** Values that make `actual <op> target` hold ([holds]) or fail; with no operator, "is true" or not. */
    private fun values(operator: Operator?, target: Datum?, holds: Boolean): List<Datum> {
        if (operator == null) return if (holds) listOf(Datum.Bool(true)) else listOf(Datum.Bool(false))
        target ?: return emptyList()
        fun others(): List<Datum> = when (target) {
            is Datum.Bool -> listOf(Datum.Bool(!target.value))
            is Datum.Number -> listOf(Datum.Number(target.value + BigDecimal.ONE))
            is Datum.Text -> listOf(shift(target, 1) ?: Datum.Text("${target.value}-not"))
            is Datum.Items -> listOf(Datum.Items(listOf(Datum.Text("B9"))), Datum.Items(emptyList()))
            else -> listOf(Datum.Text("other"))
        }
        return when (operator) {
            Operator.EQ -> if (holds) listOf(target) else others()
            Operator.NE -> if (holds) others() else listOf(target)
            Operator.IN -> {
                val items = (target as? Datum.Items)?.items.orEmpty()
                if (holds) listOfNotNull(items.firstOrNull(), items.firstOrNull()?.let { Datum.Items(listOf(it)) }) else listOf(Datum.Text("not-listed"), Datum.Items(listOf(Datum.Text("not-listed"))))
            }
            Operator.GT -> if (holds) listOfNotNull(shift(target, 1)) else listOf(target)
            Operator.GTE -> if (holds) listOf(target) else listOfNotNull(shift(target, -1))
            Operator.LT -> if (holds) listOfNotNull(shift(target, -1)) else listOf(target)
            Operator.LTE -> if (holds) listOf(target) else listOfNotNull(shift(target, 1))
        }
    }

    /** A number one more or less; a time an hour later or earlier; a duration a second longer or shorter, as seconds. */
    private fun shift(target: Datum, by: Int): Datum? = when (target) {
        is Datum.Number -> Datum.Number(target.value + BigDecimal(by))
        is Datum.Text -> runCatching { Datum.Text(Instant.parse(target.value).plusSeconds(3600L * by).toString()) }.getOrNull()
            ?: kp.rules.duration(target.value)?.let { Datum.Number(BigDecimal(it.inWholeSeconds + by)) }
        else -> null
    }

    private fun holdsCompare(actual: Datum, operator: Operator, target: Datum): Boolean = when (operator) {
        Operator.EQ -> actual == target
        Operator.NE -> actual != target
        Operator.IN -> (target as? Datum.Items)?.items?.let { items -> if (actual is Datum.Items) actual.items.all { it in items } else actual in items } == true
        else -> true
    }

    /** [this] without what was seeded under [key]. */
    private fun World.without(key: String): World = copy(seeds = seeds.filterNot { it is Seed.Sent && it.key == key })

    // ---- args

    private fun requiredArgs(kind: Kind): Map<String, Datum> = kind.args.filterValues { it.required }.mapValues { (name, spec) -> sample(spec, name) }

    /** A value its declaration accepts, not empty — a seat for an arg that names one. */
    private fun sample(spec: ArgSpec, name: String): Datum = when (name) {
        "seat" -> Datum.Text("coder-1")
        "reviewer" -> Datum.Text("reviewer-1")
        else -> sample(spec)
    }

    /** A value its declaration accepts, not empty. */
    private fun sample(spec: ArgSpec): Datum = spec.enum?.firstOrNull()?.let(::literal) ?: when (spec.type) {
        ArgType.ARRAY -> Datum.Items(listOf(spec.items?.let(::sample) ?: Datum.Text("x")))
        ArgType.OBJECT -> Datum.Fields(spec.requiredFields.associateWith { Datum.Text("x") }.ifEmpty { mapOf("x" to Datum.Text("x")) })
        ArgType.BOOLEAN -> Datum.Bool(true)
        ArgType.INTEGER, ArgType.NUMBER -> Datum.Number(BigDecimal.ONE)
        else -> Datum.Text("x")
    }

    /** An empty value its declaration accepts, when there is one. */
    private fun empty(spec: ArgSpec): Datum? = when {
        spec.enum != null -> spec.enum!!.map(::literal).firstOrNull { it == Datum.Text("") }
        spec.type == ArgType.ARRAY -> Datum.Items(emptyList())
        spec.type == ArgType.OBJECT && spec.requiredFields.isEmpty() -> Datum.Fields(emptyMap())
        spec.type == ArgType.BOOLEAN -> Datum.Bool(false)
        spec.type == ArgType.STRING || spec.type == null -> Datum.Text("")
        else -> null
    }

    private fun valid(value: Datum, spec: ArgSpec): Boolean {
        if (spec.enum != null && spec.enum!!.map(::literal).none { it == value }) return false
        val typed = when (spec.type) {
            null -> true
            ArgType.STRING -> value is Datum.Text
            ArgType.ARRAY -> value is Datum.Items && (spec.items == null || value.items.all { valid(it, spec.items!!) })
            ArgType.OBJECT -> value is Datum.Fields && spec.requiredFields.all { it in value.fields }
            ArgType.BOOLEAN -> value is Datum.Bool
            ArgType.INTEGER -> value is Datum.Number && value.value.stripTrailingZeros().scale() <= 0
            ArgType.NUMBER -> value is Datum.Number
        }
        return typed
    }

    // ---- who sends

    private fun senderOf(kind: Kind): String = seatOf(kind.from.first())

    /** A sender of [role]: Bora, a party, a robot's role, or the first seat of a role. */
    private fun seatOf(role: String): String = when (role) {
        World.BORA, "mailroom", "deputy", "pool", "qa", "retro", "teamlead", "principal", "planner", "gardener" -> role
        else -> "$role-1"
    }

    private fun literal(literal: Literal): Datum = when (literal) {
        is Literal.Text -> Datum.Text(literal.value)
        is Literal.Number -> Datum.Number(literal.value)
        is Literal.Bool -> Datum.Bool(literal.value)
        Literal.Null -> Datum.Null
    }

    private companion object {
        const val SEARCH = 40
        const val WAYS = 6
        val KEYS = listOf("of", "seat", "id", "dimension", "subscription", "pointer")
        val CLASSES = listOf("A", "B", "C")
        val LOG = setOf("review.open_blockers", "bora.absent_since", "stage.state", "bora.present", "queue.names", "request.open", "log.count", "thread.state",
            "thread.open", "thread.hops", "seat.state", "seat.inbox_replayed", "refusals.today", "phase.state")
    }
}
