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

class RuleSet internal constructor(val kinds: Map<String, Any>)

/** kp-rules' one entry point (architecture.md §2): the texts of the files in, a rule set or its problems out. */
object Rules {
    /** [files] maps a path relative to the repository (`rules/…`, `robots/…`, `pool/…`, `agents/<role>/AGENT.md`) to its text. */
    fun load(files: Map<String, String>): Loaded = Loaded.Invalid(listOf(Problem("-", null, "not implemented")))
}
