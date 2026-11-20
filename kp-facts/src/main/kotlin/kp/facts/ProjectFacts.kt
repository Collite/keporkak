package kp.facts

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path

/**
 * What an observer answers (contracts.md §4.4, Amendment 32): a value with its evidence — the file and its lines, or the
 * ref or commit read — or unknown, with what was missing. An observer compares nothing with a cap: the rules do.
 */
sealed interface Observation {
    val evidence: String

    /** [value] is a [Boolean], an [Int], a [String] or a [List] of strings — a set, in its order. */
    data class Value(val value: Any, override val evidence: String) : Observation

    data class Unknown(val missing: String) : Observation {
        override val evidence: String get() = missing
    }
}

/**
 * What the log knows, as the daemon hands it over from its fold (Amendment 32): a stage's state or that it has none, the
 * time of its exit, the stages in an active state. `kp-facts` depends on `kp-model` alone, so this is all it reads of the
 * log.
 */
interface WhatTheLogKnows {
    /** [stage]'s state in the log; null when the log holds no record of it — a stage carried by hand. */
    fun state(stage: String): String?

    /** When [stage] exited, as the log dates it (RFC 3339); null when it has not. */
    fun exitedAt(stage: String): String?

    /** The stages in an active state. */
    fun active(): Set<String>
}

/**
 * Where the facts are read (contracts.md §9): the office [office]'s project checkout, each effort's folder relative to it,
 * and its main checkouts by repository. The project checkout is not a main checkout: the daemon and the seats write there.
 */
data class Checkouts(val office: String, val project: Path, val efforts: Map<String, String>, val repos: Map<String, Path>)

/** A fact `kp-facts` answers: its name, its parameters as the rules name them, and whether it reads the log as well. */
data class Fact(val name: String, val params: List<String>, val readsTheLog: Boolean = false)

/**
 * The observers of the project and git (contracts.md §4.4, Amendment 32). They read the working tree as it stands and the
 * checkouts' history through [git]; they write nothing, fetch nothing and reach no network.
 */
class ProjectFacts(private val where: Checkouts, private val log: WhatTheLogKnows, private val git: Git) {
    /**
     * The one entry: [fact] with its [params] — each a text, or a list of texts — answered with a value and its evidence,
     * or unknown with what was missing. A parameter the fact does not take is not read. Nothing is thrown for what the
     * world lacks: a file that is not there, a shape the amendment does not give, a git command that fails.
     */
    fun observe(fact: String, params: Map<String, Any> = emptyMap()): Observation {
        NOT_READ_YET[fact]?.let { return Observation.Unknown(it) }
        val read = READERS[fact] ?: return Observation.Unknown("kp-facts does not read '$fact'")
        return try {
            Reading(where, log, git, Asked(params)).read()
        } catch (missing: Missing) {
            Observation.Unknown(missing.message)
        } catch (error: IOException) {
            Observation.Unknown(error.toString())
        } catch (error: UncheckedIOException) {
            Observation.Unknown(error.cause.toString())
        }
    }

    companion object {
        /** The facts answered, as Amendment 32 reads them; the parameters are those of `rules/vocabulary.yaml`. */
        val ANSWERED: Map<String, Fact> = listOf(
            Fact("plan.predecessors_exited", listOf("stage"), readsTheLog = true),
            Fact("plan.stage_has_list", listOf("stage")),
            Fact("plan.last_stage_exited", listOf("phase"), readsTheLog = true),
            Fact("plan.in_current_wave", listOf("stage"), readsTheLog = true),
            Fact("plan.wave_of", listOf("stage"), readsTheLog = true),
            Fact("plan.chart_fresh", listOf("phase"), readsTheLog = true),
            Fact("list.fence_complete", listOf("stage")),
            Fact("list.stop_conditions", listOf("stage")),
            Fact("list.tasks_open", listOf("stage")),
            Fact("list.tasks_total", listOf("stage")),
            Fact("list.task_exists", listOf("stage", "id")),
            Fact("list.done_item_exists", listOf("stage", "id")),
            Fact("list.found_untriaged", listOf("stage")),
            Fact("list.lines", listOf("stage")),
            Fact("stage.rolls", listOf("stage")),
            Fact("stage.live", listOf("stage")),
            Fact("stage.touches", listOf("stage")),
            Fact("stage.cut_at", listOf("stage")),
            Fact("status.entry_present", listOf("stage")),
            Fact("status.entries", listOf("effort")),
            Fact("register.fresh", emptyList()),
            Fact("project.clean_and_pushed", emptyList()),
            Fact("project.proof_files", listOf("stage")),
            Fact("git.branch_exists", listOf("repo", "stage")),
            Fact("git.stray_changes", listOf("office")),
        ).associateBy { it.name }

        /** The facts Amendment 32 leaves unknown for now, each with its reason. */
        val NOT_READ_YET: Map<String, String> = mapOf(
            "list.sections_changed" to "list.sections_changed needs the list as it stood at an earlier delivery, and the project checkout is " +
                "committed only at a stage's exit: how the daemon keeps it is said with a later list of P3 (contracts.md §4.4, Amendment 32)",
            "git.pin_files_touched" to "git.pin_files_touched waits for the ruling on pin stages (contracts.md §4.4, Amendment 32)",
        )

        /** Each answered fact's reader. */
        private val READERS: Map<String, Reading.() -> Observation> = mapOf()
    }
}

/** What was missing for a fact to be read: it is then unknown, and this is its evidence. */
internal class Missing(override val message: String) : Exception(message, null, false, false)

/** A fact's parameters as they were given: a text, or a list of texts. */
internal class Asked(private val params: Map<String, Any>) {
    fun text(name: String): String = optionalText(name) ?: throw Missing("no parameter '$name'")

    fun optionalText(name: String): String? = when (val value = params[name]) {
        null -> null
        is String -> value
        else -> throw Missing("the parameter '$name' is not a text")
    }

    /** One text or a list of them. */
    fun texts(name: String): List<String> = when (val value = params[name]) {
        null -> throw Missing("no parameter '$name'")
        is String -> listOf(value)
        is List<*> -> value.map { it as? String ?: throw Missing("the parameter '$name' holds what is not a text") }
        else -> throw Missing("the parameter '$name' is not a text or a list of texts")
    }
}

/** A stage `<EFFORT>/P<n>/S<n.m>` (contracts.md §1): its effort, the `n` its phase and its id share, and its `m`. */
internal data class StageId(val effort: String, val n: String, val m: String) {
    /** The stage as its plan writes it: `S<n.m>`. */
    val local: String get() = "S$n.$m"
    val id: String get() = "$effort/P$n/$local"
    val phase: PhaseId get() = PhaseId(effort, n)

    companion object {
        private val FORM = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+)\\.([0-9]+)$")

        fun of(text: String): StageId {
            val (effort, phase, n, m) = FORM.matchEntire(text)?.destructured ?: throw Missing("'$text' is no stage id <EFFORT>/P<n>/S<n.m>")
            if (phase.toInt() != n.toInt()) throw Missing("'$text' names the phase P$phase for a stage S$n.$m")
            return StageId(effort, n, m)
        }
    }
}

/** A phase `<EFFORT>/P<n>`. */
internal data class PhaseId(val effort: String, val n: String) {
    val id: String get() = "$effort/P$n"

    companion object {
        private val FORM = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)$")

        fun of(text: String): PhaseId {
            val (effort, n) = FORM.matchEntire(text)?.destructured ?: throw Missing("'$text' is no phase id <EFFORT>/P<n>")
            return PhaseId(effort, n)
        }
    }
}

/** One observation: what it reads, and what it has read so far. */
internal class Reading(val where: Checkouts, val log: WhatTheLogKnows, val git: Git, val asked: Asked) {
    fun stage(): StageId = StageId.of(asked.text("stage"))
}
