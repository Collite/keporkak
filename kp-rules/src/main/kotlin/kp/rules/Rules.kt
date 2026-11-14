package kp.rules

/** One problem in the rule set: the file it is in, the rule's id when it has one, and what is wrong. */
data class Problem(val file: String, val id: String?, val message: String) {
    override fun toString(): String = listOfNotNull(file, id, message).joinToString(": ")
}

/** What [Rules.load] returns: a typed rule set, or every problem found. Never both. */
sealed interface Loaded {
    val problems: List<Problem>
    data class Valid(val ruleSet: RuleSet) : Loaded { override val problems: List<Problem> get() = emptyList() }
    data class Invalid(override val problems: List<Problem>) : Loaded
}

/** kp-rules' one entry point (architecture.md §2): the texts of the files in, a rule set or its problems out. It reads no file. */
object Rules {
    private val required = listOf(LIFECYCLE, KINDS, ENGAGEMENT, VOCABULARY, ROSTER, OFFICES, BUDGETS)
    private val robotFile = Regex("robots/[a-z0-9-]+\\.yaml")
    private val personaFile = Regex("agents/[a-z0-9-]+/AGENT\\.md")

    /**
     * [files] maps a path relative to the repository to its text: the four `rules/` files of contracts.md §4, every
     * `robots/<name>.yaml` (§8), the three `pool/` files (§9), and each `agents/<role>/AGENT.md` whose modes a robot may wake.
     */
    fun load(files: Map<String, String>): Loaded {
        val report = Report()
        files.keys.filterNot { it in required || robotFile.matches(it) || personaFile.matches(it) }.sorted()
            .forEach { report.add(it, null, "not a file of the rule set") }
        required.filterNot { it in files }.forEach { report.add(it, null, "missing") }
        fun parsed(path: String): Node? = files[path]?.let { YamlText.parse(path, it, report) }

        val lifecycle = parsed(LIFECYCLE)?.let { readLifecycle(Doc(LIFECYCLE, report), it) }
        val kinds = parsed(KINDS)?.let { readKinds(Doc(KINDS, report), it) }
        val engagement = parsed(ENGAGEMENT)?.let { readEngagement(Doc(ENGAGEMENT, report), it) }
        val vocabulary = parsed(VOCABULARY)?.let { readVocabulary(Doc(VOCABULARY, report), it) }
        val robots = files.keys.filter { robotFile.matches(it) }.sorted().mapNotNull { path ->
            parsed(path)?.let { readRobot(Doc(path, report), it) }?.let { path to it }
        }.toMap()
        val personaModes = files.filterKeys { personaFile.matches(it) }.toSortedMap().mapNotNull { (path, text) ->
            YamlText.frontmatter(path, text, report)?.let { readPersona(Doc(path, report), it) }
        }.toMap()
        val rosterNode = parsed(ROSTER)
        val roster = rosterNode?.let { readRoster(Doc(ROSTER, report), it) }
        val officesNode = parsed(OFFICES)
        val offices = officesNode?.let { readOffices(Doc(OFFICES, report), it, roster?.vendors?.keys.orEmpty()) }
        val budgetsNode = parsed(BUDGETS)
        val budgets = budgetsNode?.let { readBudgets(Doc(BUDGETS, report), it, roster?.vendors?.keys.orEmpty(), offices?.keys.orEmpty()) }
        if (lifecycle == null || kinds == null || engagement == null || vocabulary == null || roster == null || offices == null || budgets == null) {
            return Loaded.Invalid(report.problems)
        }

        val rules = RulesCheck(report, lifecycle, kinds, engagement, vocabulary, robots, personaModes).apply { run() }
        RobotsCheck(report, rules, kinds, engagement, vocabulary, robots, personaModes).run()
        val officeValues = (officesNode as Node.Mapping).entries["offices"].let { it as? Node.Mapping }?.entries?.values?.map { flatten(it) }.orEmpty()
        val values = mapOf(PoolFile.ROSTER to listOf(flatten(rosterNode)), PoolFile.OFFICE to officeValues, PoolFile.BUDGET to listOf(flatten(budgetsNode)))
        checkPoolValues(report, uses(lifecycle, kinds, engagement, robots), values)
        rosterRoles(report, roster, engagement)

        val problems = report.problems
        return if (problems.isNotEmpty()) Loaded.Invalid(problems)
        else Loaded.Valid(RuleSet(lifecycle, kinds, engagement, vocabulary, robots, Pool(roster, offices, budgets, values), personaModes))
    }

    /** A role the roster reserves a model for, or puts on a model outright, is a role of the engagement. */
    private fun rosterRoles(report: Report, roster: Roster, engagement: Engagement) {
        roster.reserved.forEach { (model, roles) -> roles.filterNot { it in engagement.roles }.forEach { report.add(ROSTER, null, "reserved model '$model' names unknown role '$it'") } }
        roster.roleModels.keys.filterNot { it in engagement.roles }.forEach { report.add(ROSTER, null, "role_models names unknown role '$it'") }
    }
}
