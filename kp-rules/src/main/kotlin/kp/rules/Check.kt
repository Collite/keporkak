package kp.rules

internal const val LIFECYCLE = "rules/stage-lifecycle.yaml"
internal const val KINDS = "rules/message-kinds.yaml"
internal const val ENGAGEMENT = "rules/engagement.yaml"
internal const val VOCABULARY = "rules/vocabulary.yaml"

/** Where a name appears, for the problem that reports it. */
internal data class Owner(val file: String, val id: String?)

/** Every name a requirement, value or effect uses, by what it names. */
internal class Names {
    val facts = mutableListOf<String>()
    val operations = mutableListOf<String>()
    val kinds = mutableListOf<String>()
    val caps = mutableListOf<String>()
    val counters = mutableListOf<String>()
    /** Counters a requirement reads (a `count:` effect only moves one). */
    val counterReads = mutableListOf<String>()
    val attestations = mutableListOf<String>()
    val args = mutableListOf<String>()
    val emits = mutableListOf<String>()
    val pools = mutableListOf<Value.Pool>()
    val thresholds = mutableListOf<String>()
    val modes = mutableListOf<String>()
    val engagementPaths = mutableListOf<String>()
    val destinations = mutableListOf<String>()
    /** (machine, state) for every `{fact: <machine>.state}` compared with a literal state. */
    val states = mutableListOf<Pair<String, String>>()
    val blocks = mutableListOf<String>()
    /** Every requirement of the rule, and every one inside an `any` or a `not` — what a repeated block is looked for in. */
    val parts = mutableListOf<Requirement>()

    fun requirement(requirement: Requirement) {
        parts += requirement
        when (requirement) {
            is Requirement.Fact -> {
                call(requirement.fact)
                requirement.comparison?.let { comparison ->
                    value(comparison.value)
                    if (requirement.fact.name.endsWith(".state") && comparison.operator in setOf(Operator.EQ, Operator.NE, Operator.IN)) {
                        val machine = requirement.fact.name.removeSuffix(".state")
                        literals(comparison.value).forEach { states += machine to it }
                    }
                }
            }
            is Requirement.Arg -> { args += requirement.name; requirement.comparison?.let { value(it.value) } }
            is Requirement.Message -> requirement.comparison?.let { value(it.value) }
            is Requirement.Reply -> requirement.comparison?.let { value(it.value) }
            is Requirement.Counter -> { counters += requirement.name; counterReads += requirement.name; requirement.comparison?.let { value(it.value) } }
            is Requirement.Attest -> attestations += requirement.name
            is Requirement.Holds -> blocks += requirement.block
            is Requirement.Operation -> operations += requirement.operation
            is Requirement.Any -> requirement.items.forEach(::requirement)
            is Requirement.Not -> requirement(requirement.item)
            is Requirement.Signed, is Requirement.Always -> Unit
        }
    }

    fun call(fact: FactCall) {
        facts += fact.name
        fact.params.forEach { (name, param) ->
            if (name == "kind" || name == "since") text(param)?.let { kinds += it }
            if (name == "since") ((param as? Value.Fields)?.fields?.get("wake"))?.let(::text)?.let { modes += it }
            value(param)
        }
    }

    private fun text(value: Value): String? = ((value as? Value.Constant)?.literal as? Literal.Text)?.value

    private fun literals(value: Value): List<String> = when (value) {
        is Value.Constant -> listOfNotNull(text(value))
        is Value.Items -> value.items.mapNotNull(::text)
        else -> emptyList()
    }

    fun value(value: Value) {
        when (value) {
            is Value.Cap -> caps += value.name
            is Value.Arg -> args += value.path
            is Value.Fact -> call(value.fact)
            is Value.Items -> value.items.forEach(::value)
            is Value.Fields -> value.fields.values.forEach(::value)
            is Value.Pool -> pools += value
            is Value.Robot -> thresholds += value.threshold
            is Value.Engagement -> engagementPaths += value.path
            else -> Unit
        }
    }

    fun action(action: Action) {
        if (action is Action.Send) { text(action.to)?.let { destinations += it }; value(action.to); action.args.values.forEach(::value) }
    }

    fun effect(effect: Effect) {
        effect.condition?.let(::requirement)
        when (effect) {
            is Effect.Emit -> {
                kinds += effect.kind; emits += effect.kind; text(effect.to)?.let { destinations += it }
                value(effect.to); effect.args.values.forEach(::value)
            }
            is Effect.Do -> { operations += effect.operation; effect.with.values.forEach(::value) }
            is Effect.Count -> counters += effect.counter
            is Effect.Report, is Effect.Transition, is Effect.Wait -> Unit
        }
    }
}

/** The checks of the four rules files against each other (what `check-rules.py` checks, in its words). */
internal class RulesCheck(
    private val report: Report,
    private val lifecycle: Lifecycle,
    private val kinds: Map<String, Kind>,
    private val engagement: Engagement,
    private val vocabulary: Vocabulary,
    private val robots: Map<String, Robot>,
    private val personaModes: Map<String, Set<String>>,
) {
    private val reservedStates = setOf("*", "*active", "*previous")
    private val transitionIds = lifecycle.machines.values.flatMap { machine -> machine.transitions.map { it.id } }.toSet()

    fun run() {
        transitions()
        kindsAgainstRules()
        engagementRequirements()
        firesCrossChecked()
        senderRoles()
        statesAndCycles()
        declaredEffects()
    }

    private fun problem(owner: Owner, message: String) = report.add(owner.file, owner.id, message)

    /** The thresholds a `{robot: …}` may name for a rule sent by [roles]: those of these roles' own robot files. */
    fun thresholdsOf(roles: Collection<String>): Set<String> = robots.values.filter { it.role in roles }.flatMap { it.thresholds.keys }.toSet()

    /**
     * Every name resolves: facts, operations, kinds (also in a `since:`), caps, counters, attestations, states, an
     * `{engagement: …}` path, the destination of an emit or a send, `{robot: …}` against [thresholds] only, and a
     * `since: {wake: …}` against [modes] — the robot's own persona's ([ownModes]) or, outside a robot, any persona's.
     */
    fun resolve(owner: Owner, names: Names, thresholds: Set<String>, modes: Set<String> = personaModes.values.flatten().toSet(), ownModes: Boolean = false) {
        names.engagementPaths.filterNot { path -> engagement.values.keys.any { it == path || it.startsWith("$path.") } }
            .forEach { problem(owner, "unknown engagement value '$it'") }
        names.destinations.filterNot { it in engagement.roles || it in PARTIES }.forEach { problem(owner, "unknown destination '$it'") }
        names.thresholds.filterNot { it in thresholds }.forEach { problem(owner, "unknown robot threshold '$it'") }
        names.modes.filterNot { it in modes }.forEach { problem(owner, if (ownModes) "unknown own persona mode '$it'" else "unknown persona mode '$it'") }
        for ((machine, state) in names.states) {
            val states = lifecycle.machines[machine]?.states ?: continue
            if (state !in states) problem(owner, "unknown state '$state' in $machine")
        }
        names.facts.filterNot { it in vocabulary.facts }.forEach { problem(owner, "unknown fact '$it'") }
        names.operations.filterNot { it in vocabulary.operations }.forEach { problem(owner, "unknown operation '$it'") }
        names.kinds.filterNot { it in kinds }.forEach { problem(owner, "unknown kind '$it'") }
        names.caps.filterNot { it in engagement.caps }.forEach { problem(owner, "unknown cap '$it'") }
        names.counters.filterNot { it in lifecycle.counters }.forEach { problem(owner, "unknown counter '$it'") }
        names.attestations.filterNot { it in vocabulary.attestations }.forEach { problem(owner, "unknown attestation '$it'") }
        names.blocks.filterNot { it in engagement.blocks }.forEach { problem(owner, "unknown engagement block '$it'") }
    }

    private fun transitions() {
        val seen = mutableSetOf<String>()
        for (machine in lifecycle.machines.values) for (transition in machine.transitions) {
            val owner = Owner(LIFECYCLE, transition.id)
            if (!seen.add(transition.id)) problem(owner, "duplicate transition id")
            if (transition.on != Event.FACT && transition.kind !in kinds) problem(owner, "unknown kind '${transition.kind}'")
            val names = Names().apply { transition.require.forEach(::requirement); transition.effects.forEach(::effect) }
            resolve(owner, names, thresholdsOf(kinds[transition.kind]?.from.orEmpty()))
            names.emits.filterNot { it in engagement.mailroomDeclaredEffects }.forEach { problem(owner, "undeclared mailroom emit '$it'") }
            if (transition.on != Event.OP_RESULT && transition.require.any { it is Requirement.Operation }) problem(owner, "operation result outside op-result trigger")
            for (state in listOf(transition.from, transition.to)) {
                if (state !in machine.states && state !in reservedStates) problem(owner, "unknown state '$state' in ${machine.name}")
            }
            val declared = kinds[transition.kind]?.args ?: continue
            names.args.distinct().filterNot { it in declared }.forEach { problem(owner, "undeclared argument '$it'") }
        }
    }

    private fun kindsAgainstRules() {
        for (kind in kinds.values) {
            val owner = Owner(KINDS, kind.name)
            val names = Names().apply {
                kind.require.forEach(::requirement)
                kind.classRule.cases.forEach { requirement(it.condition) }
                kind.expiry.forEach(::effect)
                kinds += kind.replies
            }
            resolve(owner, names, thresholdsOf(kind.from))
            names.args.distinct().filterNot { it in kind.args }.forEach { problem(owner, "undeclared argument '$it'") }
            for (effect in kind.expiry) {
                if (effect is Effect.Transition && effect.id !in transitionIds) problem(owner, "expiry names unknown transition '${effect.id}'")
                if (effect is Effect.Emit && effect.kind !in engagement.mailroomDeclaredEffects) problem(owner, "undeclared expiry emit '${effect.kind}'")
                if (effect is Effect.Wait && !readsCounterAgainstCap(effect.condition)) problem(owner, "{wait: again} has no if that reads a counter against a cap")
            }
            val toDaemon = (kind.to as? Destination.Parties)?.names == listOf("daemon")
            val bound = lifecycle.machines.values.any { machine -> machine.transitions.any { it.kind == kind.name && it.effects.any { effect -> effect is Effect.Do } } }
            if (toDaemon && !bound) problem(owner, "daemon request has no operation binding")
        }
    }

    private fun engagementRequirements() {
        engagement.secondKey?.let { key -> resolve(Owner(ENGAGEMENT, "second_key"), Names().apply { key.require.forEach(::requirement) }, emptySet()) }
        engagement.stopGate?.let { gate ->
            val owner = Owner(ENGAGEMENT, "stop_gate")
            resolve(owner, Names().apply { gate.require.forEach(::requirement); value(gate.maxConsecutiveBlocks) }, emptySet())
            if (gate.require.isNotEmpty()) problem(owner, "requirements belong to S2.2 (Amendment 2)")
        }
    }

    /** A wait goes on only under a cap (contracts.md §4.2): its `if` is a counter compared with `{cap: …}`. */
    private fun readsCounterAgainstCap(condition: Requirement?): Boolean =
        condition is Requirement.Counter && condition.comparison?.value is Value.Cap

    /** A kind whose `fires` names a stage arrow `a→b` has that arrow in the lifecycle. */
    private fun firesCrossChecked() {
        val stage = lifecycle.stage
        val pairs = stage.transitions.map { it.from to it.to }.toSet()
        for (kind in kinds.values) {
            var source: String? = null
            for (token in (kind.fires ?: "-").split("|").map { it.trim() }) {
                val target: String
                if ("→" in token) token.split("→", limit = 2).map { it.trim() }.let { source = it[0]; target = it[1] } else target = token
                val from = source
                if (from.isNullOrEmpty() || target.isEmpty() || ":" in token || " " in target) continue
                if (from.startsWith("*") || target.startsWith("*")) continue
                if (from !in stage.states || target !in stage.states) continue
                if (from to target !in pairs) problem(Owner(KINDS, null), "kind '${kind.name}' fires $from→$target which the lifecycle does not have")
            }
        }
    }

    private fun senderRoles() {
        for (kind in kinds.values) for (role in kind.from) {
            if (role !in engagement.roles && role != "mailroom") problem(Owner(KINDS, null), "kind '${kind.name}' from unknown role '$role'")
        }
        engagement.graduation.never.filterNot { it in kinds }.forEach { problem(Owner(ENGAGEMENT, null), "graduation.never names unknown kind '$it'") }
    }

    /** Every non-terminal state has an exit, every active state is reachable, and no cycle runs without a cap or a Bora gate. */
    private fun statesAndCycles() {
        val stage = lifecycle.stage
        val owner = Owner(LIFECYCLE, null)
        for (state in stage.states.values.filter { !it.terminal && !it.overlay }) {
            if (stage.transitions.none { it.from == state.name }) problem(owner, "state '${state.name}' has no exit")
        }
        val reached = mutableSetOf("planned")
        val frontier = ArrayDeque(listOf("planned"))
        while (frontier.isNotEmpty()) {
            val state = frontier.removeLast()
            for (transition in stage.transitions) {
                if (transition.from == state && !transition.to.startsWith("*") && reached.add(transition.to)) frontier.addLast(transition.to)
            }
        }
        lifecycle.activeStates.filterNot { it in reached }.forEach { problem(owner, "active state '$it' unreachable from planned") }

        // Remove every edge capped per stage and every Bora-gate state; what remains must be acyclic.
        val capped = stage.transitions.filter { transition ->
            val kind = kinds[transition.kind]
            kind != null && kind.window == Window.STAGE && (kind.maxFires ?: 0) > 0
        }.map { it.from to it.to }.toSet()
        val gates = stage.states.values.filter { it.boraGate }.map { it.name }.toSet()
        val graph = mutableMapOf<String, MutableSet<String>>()
        for (transition in stage.transitions) {
            val edge = transition.from to transition.to
            if (edge.first.startsWith("*") || edge.second.startsWith("*") || edge in capped || edge.first in gates || edge.second in gates) continue
            graph.getOrPut(edge.first) { sortedSetOf() } += edge.second
        }
        val colour = mutableMapOf<String, Int>()
        fun visit(state: String, path: List<String>) {
            colour[state] = 1
            for (next in graph[state].orEmpty().sorted()) {
                when (colour[next] ?: 0) {
                    1 -> problem(owner, "uncapped cycle without a Bora gate: " + (path.subList(path.indexOf(next), path.size) + next).joinToString(" → "))
                    0 -> visit(next, path + next)
                }
            }
            colour[state] = 2
        }
        for (state in stage.states.keys.sorted()) if ((colour[state] ?: 0) == 0) visit(state, listOf(state))
    }

    /** P-10: the mailroom sends only its declared effects, and the daemon sends nothing. */
    private fun declaredEffects() {
        for (kind in kinds.values) {
            if ("mailroom" in kind.from && kind.name !in engagement.mailroomDeclaredEffects) {
                problem(Owner(KINDS, null), "kind '${kind.name}' is sent by the mailroom but is not a declared effect (P-10)")
            }
            if ("daemon" in kind.from) problem(Owner(KINDS, null), "kind '${kind.name}' is sent by the daemon — the daemon decides nothing (P-10)")
        }
        engagement.mailroomDeclaredEffects.filterNot { it in kinds }.forEach { problem(Owner(ENGAGEMENT, null), "declared effect '$it' is not a kind") }
    }
}

/** Every rule of the set with the names it uses: transitions, kinds, the engagement's requirements, robot rules. */
internal fun uses(lifecycle: Lifecycle, kinds: Map<String, Kind>, engagement: Engagement, robots: Map<String, Robot>): List<Pair<Owner, Names>> {
    val transitions = lifecycle.machines.values.flatMap { it.transitions }.map { transition ->
        Owner(LIFECYCLE, transition.id) to Names().apply { transition.require.forEach(::requirement); transition.effects.forEach(::effect) }
    }
    val kindUses = kinds.values.map { kind ->
        Owner(KINDS, kind.name) to Names().apply {
            kind.require.forEach(::requirement); kind.classRule.cases.forEach { requirement(it.condition) }; kind.expiry.forEach(::effect)
        }
    }
    val engagementUses = listOfNotNull(
        engagement.secondKey?.let { key -> Owner(ENGAGEMENT, "second_key") to Names().apply { key.require.forEach(::requirement) } },
        engagement.stopGate?.let { gate -> Owner(ENGAGEMENT, "stop_gate") to Names().apply { gate.require.forEach(::requirement); value(gate.maxConsecutiveBlocks) } },
    )
    val robotUses = robots.flatMap { (path, robot) ->
        listOf(Owner(path, null) to Names().apply { requirement(robot.activeWhen); robot.queue?.let { requirement(it.nextWhen) } }) +
            robot.rules.filterNot { it.forPersona }.map { rule ->
                Owner(path, rule.id) to Names().apply { rule.condition.forEach(::requirement); rule.then.forEach(::action) }
            }
    }
    return transitions + kindUses + engagementUses + robotUses
}

/** A rule that needs a block's requirements refers to the block with `{holds: …}`; it never repeats them (contracts.md §4.1). */
internal fun checkBlocksNotRepeated(report: Report, engagement: Engagement, uses: List<Pair<Owner, Names>>) {
    for ((owner, names) in uses) for ((block, requirements) in engagement.blocks) {
        if (requirements.isEmpty() || owner == Owner(ENGAGEMENT, block)) continue
        if (names.parts.containsAll(requirements)) report.add(owner.file, owner.id, "repeats the requirements of '$block' — refer to the block with {holds: $block}")
    }
}

/**
 * One block never names another — itself included — so that no evaluation runs in a circle (contracts.md §4.1,
 * Amendment 9). The engine never follows one either.
 */
internal fun checkBlocksNameNoBlock(report: Report, engagement: Engagement) {
    fun named(requirement: Requirement): List<String> = when (requirement) {
        is Requirement.Holds -> listOf(requirement.block)
        is Requirement.Any -> requirement.items.flatMap(::named)
        is Requirement.Not -> named(requirement.item)
        else -> emptyList()
    }
    for ((block, requirements) in engagement.blocks) {
        requirements.flatMap(::named).toSortedSet().forEach { report.add(ENGAGEMENT, block, "names the block '$it' — a block never names a block") }
    }
}

/** A counter is declared to be read: one that nothing reads is a problem (issue #5). */
internal fun checkCountersRead(report: Report, lifecycle: Lifecycle, uses: List<Pair<Owner, Names>>) {
    val read = uses.flatMap { it.second.counterReads }.toSet()
    lifecycle.counters.keys.filterNot { it in read }.forEach { report.add(LIFECYCLE, null, "counter '$it' is declared but nothing reads it") }
}
