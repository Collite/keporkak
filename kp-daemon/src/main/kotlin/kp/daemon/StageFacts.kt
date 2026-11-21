package kp.daemon

import kp.engine.FactRef
import kp.engine.fold
import kp.facts.Observation
import kp.facts.ProjectFacts
import kp.log.LogReader
import kp.model.OfficeId
import java.nio.file.Path

/**
 * `just facts <stage>` (contracts.md §12, Amendment 34; S3.8 T5): every fact of the project and of git for a stage, its
 * phase and its effort — each with its value and evidence, or unknown and what is missing — read by the daemon's own
 * world, [ProjectWorld], on the office's project checkout and main checkouts, with what the office's log knows. It
 * writes nothing — no line to the log, none to the journal, no file or ref of a checkout — and needs no running daemon:
 * it reads the log as any reader does, without the writer's lock, and folds it as a start would before it carries a
 * judgement again — a judgement the log ends in, unfinished, counts for nothing yet.
 */
object StageFacts {
    /** What `just facts` prints for [stage], on [office]'s checkouts under the rules [rules], its project checkout being [project]. */
    fun lines(rules: InForceRules, office: String, project: Path, stage: String, path: String? = System.getenv("PATH")): List<String> {
        val world = ProjectWorld.of(rules, office, project, path) { }
        val read = LogReader(project, OfficeId(office)).read()
        val state = fold(read.lines.map { it.entry })
        world.reading({ state }, { rules.ruleSet })
        val known = state.machine(STAGE, stage)
        val facts = (ProjectFacts.ANSWERED.keys + ProjectFacts.NOT_READ_YET.keys).map { name -> world.observation(FactRef(name, emptyMap()), stage)!! }
        return buildList {
            add("facts of $stage on office $office · rules ${rules.hash.take(HASH)}")
            add(world.reads.removePrefix("facts: "))
            read.problems.forEach { add("the log has a problem: $it") }
            add("the log: " + (known?.let { "$stage is ${it.state} since ${it.since} — the log's word" } ?: "no record of $stage — the project's word") +
                " · ${read.lines.size} lines, at seq ${state.seq}")
            facts.forEach { (asked, observation) ->
                add(when (observation) {
                    is Observation.Value -> "$asked = ${shown(observation.value)} — ${observation.evidence}"
                    is Observation.Unknown -> "$asked unknown — ${observation.missing}"
                })
            }
        }
    }

    /** A value as `just facts` prints it: a text quoted, a set in brackets. */
    private fun shown(value: Any): String = when (value) {
        is List<*> -> value.joinToString(", ", "[", "]")
        is String -> "\"$value\""
        else -> value.toString()
    }

    private const val STAGE = "stage"
    private const val HASH = 12
}
