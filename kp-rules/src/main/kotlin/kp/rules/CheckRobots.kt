package kp.rules

/** The robots against the rules (contracts.md §8), and every pool value the rules use against the pool files (§9). */
internal class RobotsCheck(
    private val report: Report,
    private val rules: RulesCheck,
    private val kinds: Map<String, Kind>,
    private val engagement: Engagement,
    private val vocabulary: Vocabulary,
    private val robots: Map<String, Robot>,
    private val personaModes: Map<String, Set<String>>,
) {
    private val classes = engagement.classes.keys.ifEmpty { MessageClass.entries.toSet() }

    fun run() {
        val ids = mutableSetOf<String>()
        for ((path, robot) in robots) {
            val file = Owner(path, robot.file)
            if (robot.role !in engagement.roles) {
                report.add(path, null, "unknown role '${robot.role}'")
                continue
            }
            val may = kinds.values.filter { robot.role in it.from }.map { it.name }.toSet()
            resolve(file, robot, Names().apply { requirement(robot.activeWhen) })
            for (intercept in robot.intercepts) when (intercept) {
                is Intercept.Kind -> if (intercept.name !in kinds) report.add(path, robot.file, "unknown intercepted kind '${intercept.name}'")
                is Intercept.ClassTo -> if (intercept.messageClass !in classes || intercept.to !in engagement.roles) report.add(path, robot.file, "invalid class intercept")
            }
            robot.queue?.let { queue ->
                queue.order.filterNot { it in kinds }.forEach { report.add(path, robot.file, "unknown queued kind '$it'") }
                resolve(file, robot, Names().apply { requirement(queue.nextWhen) })
            }
            for (rule in robot.rules) {
                val owner = Owner(path, rule.id)
                if (!ids.add(rule.id)) report.add(path, rule.id, "duplicate robot rule id")
                if (rule.forPersona) continue
                trigger(owner, rule.on!!)
                resolve(owner, robot, Names().apply { rule.condition.forEach(::requirement); rule.then.forEach(::action) })
                for (action in rule.then) when (action) {
                    is Action.Send -> send(owner, robot.role, may, action)
                    is Action.Wake -> if (action.mode !in personaModes[robot.role].orEmpty()) report.add(path, rule.id, "unknown own persona mode '${action.mode}'")
                }
            }
        }
    }

    /** A robot's names resolve like any rule's; `{robot: …}` and a wake's mode against its own file and persona only. */
    private fun resolve(owner: Owner, robot: Robot, names: Names) =
        rules.resolve(owner, names, robot.thresholds.keys, personaModes[robot.role].orEmpty(), ownModes = true)

    private fun trigger(owner: Owner, trigger: RobotTrigger) {
        when (trigger) {
            is RobotTrigger.Fact -> if (trigger.name !in vocabulary.facts) report.add(owner.file, owner.id, "unknown fact '${trigger.name}'")
            is RobotTrigger.Operation -> if (trigger.operation !in vocabulary.operations) report.add(owner.file, owner.id, "unknown operation '${trigger.operation}'")
            is RobotTrigger.Delivered -> triggerKind(owner, trigger.kind)
            is RobotTrigger.Arrives -> triggerKind(owner, trigger.kind)
            is RobotTrigger.Expired -> triggerKind(owner, trigger.kind)
            is RobotTrigger.Presence -> Unit
        }
    }

    private fun triggerKind(owner: Owner, kind: String) {
        if (kind !in kinds) report.add(owner.file, owner.id, "unknown trigger kind '$kind'")
    }

    /** A robot sends only its role's kinds, with the kind's declared arguments and every required one. */
    private fun send(owner: Owner, role: String, may: Set<String>, send: Action.Send) {
        val kind = kinds[send.kind]
        when {
            kind == null -> report.add(owner.file, owner.id, "unknown send kind '${send.kind}'")
            send.kind !in may -> report.add(owner.file, owner.id, "role '$role' may not send '${send.kind}'")
            else -> {
                send.args.keys.filterNot { it in kind.args }.forEach { report.add(owner.file, owner.id, "unknown send argument '$it'") }
                kind.args.filter { it.value.required && it.key !in send.args }.forEach { report.add(owner.file, owner.id, "missing send argument '${it.key}'") }
            }
        }
    }
}

/** Every `{roster: …}`, `{budget: …}` and `{office: …}` the rules use names a value of its pool file (of every office, for `office`). */
internal fun checkPoolValues(report: Report, uses: List<Pair<Owner, Names>>, values: Map<PoolFile, List<Map<String, Literal>>>) {
    for ((owner, names) in uses) for (use in names.pools) {
        val sources = values[use.file].orEmpty()
        val resolves = sources.isNotEmpty() && sources.all { source -> source.keys.any { it == use.path || it.startsWith(use.path + ".") } }
        if (!resolves) report.add(owner.file, owner.id, "{${use.file.key}: ${use.path}} does not resolve in pool/")
    }
}
