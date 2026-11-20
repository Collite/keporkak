package kp.facts

import java.nio.file.Path

/** What an observer answers (contracts.md §4.4, Amendment 32): a value with its evidence, or unknown with what was missing. */
sealed interface Observation {
    val evidence: String

    /** [value] is a [Boolean], an [Int], a [String] or a [List] of strings — a set, in its order. */
    data class Value(val value: Any, override val evidence: String) : Observation

    data class Unknown(val missing: String) : Observation {
        override val evidence: String get() = missing
    }
}

/** What the log knows, as the daemon hands it over from its fold (Amendment 32). */
interface WhatTheLogKnows {
    /** [stage]'s state in the log; null when the log holds no record of it. */
    fun state(stage: String): String?

    /** When [stage] exited, as the log dates it; null when it has not. */
    fun exitedAt(stage: String): String?

    /** The stages in an active state. */
    fun active(): Set<String>
}

/** Where the facts are read (contracts.md §9): the office's project checkout, each effort's folder in it, its main checkouts. */
data class Checkouts(val office: String, val project: Path, val efforts: Map<String, String>, val repos: Map<String, Path>)

/** The office's `git`, looked up on [path] — a `PATH`; null or empty: there is none. */
class Git(val path: String?)

/** The observers of the project and git (contracts.md §4.4, Amendment 32). They read; they write nothing and fetch nothing. */
class ProjectFacts(private val where: Checkouts, private val log: WhatTheLogKnows, private val git: Git) {
    /** [fact] with its [params] — each a text, or a list of texts. */
    fun observe(fact: String, params: Map<String, Any> = emptyMap()): Observation = Observation.Unknown("not read yet")

    companion object {
        /** The facts answered, each with its parameters. */
        val ANSWERED: Map<String, List<String>> = emptyMap()

        /** The facts left unknown, each with the amendment's reason. */
        val NOT_READ_YET: Map<String, String> = emptyMap()
    }
}
