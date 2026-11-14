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
    private val rulesFiles = listOf(LIFECYCLE, KINDS, ENGAGEMENT, VOCABULARY)

    /** [files] maps a path relative to the repository (`rules/…`, `robots/…`, `pool/…`, `agents/<role>/AGENT.md`) to its text. */
    fun load(files: Map<String, String>): Loaded {
        val report = Report()
        files.keys.filterNot { it in rulesFiles }.sorted().forEach { report.add(it, null, "not a file of the rule set") }
        fun parsed(path: String): Node? = files[path]?.let { YamlText.parse(path, it, report) } ?: null.also { if (path !in files) report.add(path, null, "missing") }
        val lifecycle = parsed(LIFECYCLE)?.let { readLifecycle(Doc(LIFECYCLE, report), it) }
        val kinds = parsed(KINDS)?.let { readKinds(Doc(KINDS, report), it) }
        val engagement = parsed(ENGAGEMENT)?.let { readEngagement(Doc(ENGAGEMENT, report), it) }
        val vocabulary = parsed(VOCABULARY)?.let { readVocabulary(Doc(VOCABULARY, report), it) }
        if (lifecycle == null || kinds == null || engagement == null || vocabulary == null) return Loaded.Invalid(report.problems)
        RulesCheck(report, lifecycle, kinds, engagement, vocabulary).run()
        val problems = report.problems
        return if (problems.isEmpty()) Loaded.Valid(RuleSet(lifecycle, kinds, engagement, vocabulary)) else Loaded.Invalid(problems)
    }
}
