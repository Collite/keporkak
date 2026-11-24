package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.fold
import kp.facts.GitHubFacts
import kp.facts.MadeWorktree
import kp.facts.Observation
import kp.facts.ProjectFacts
import kp.facts.StageState
import kp.facts.WhatTheLogKnows
import kp.log.LogReader
import kp.log.counted
import kp.model.OfficeId
import kp.ops.GitHubApps
import java.net.URI
import java.nio.file.Path

/**
 * `just facts <stage>` (contracts.md §12, Amendment 34; S3.8 T5): every fact of the project and of git for a stage, its
 * phase and its effort — each with its value and evidence, or unknown and what is missing — read by the daemon's own
 * world, [ProjectWorld], on the office's project checkout and main checkouts, with what the office's log knows; and
 * GitHub's nine facts as [GitHubWorld] reads them, at [api] — for a stage with no `worktree.create` record each unknown
 * with that reason, GitHub asked nothing (S3.11; Amendments 40, 43). It
 * writes nothing — no line to the log, none to the journal, no file or ref of a checkout — and needs no running daemon:
 * it reads the log as any reader does, without the writer's lock, and folds it as a start would before it carries a
 * judgement again — a judgement the log ends in, unfinished, counts for nothing yet.
 */
object StageFacts {
    /** What `just facts` prints for [stage], on [office]'s checkouts under the rules [rules], its project checkout being [project]. */
    fun lines(rules: InForceRules, office: String, project: Path, stage: String, path: String? = System.getenv("PATH"), api: URI = GitHubApps.GITHUB): List<String> {
        val world = ProjectWorld.of(rules, office, project, path) { }
        val github = GitHubWorld.of(office, project, api) { }
        val read = LogReader(project, OfficeId(office)).read()
        val state = fold(read.lines.map { it.entry })
        val counted = read.lines.counted().map { it.entry }
        val log = object : WhatTheLogKnows {
            override fun state(stage: String): StageState? = state.machine(STAGE, stage)?.let { StageState(it.state, it.since) }
            override fun stages(): Map<String, StageState> = state.machines[STAGE].orEmpty().mapValues { (_, machine) -> StageState(machine.state, machine.since) }
            override fun activeStates(): Set<String> = rules.ruleSet.lifecycle.activeStates.toSet()
            override fun observedHead(message: String): String? = observedHead(counted, message)
            override fun lastDelivered(stage: String, kinds: Set<String>): String? = state.lastDelivered(stage, kinds)
            override fun worktree(stage: String): MadeWorktree? = worktreeOf(counted, stage)
        }
        world.reading(log)
        github.reading({ rules }, log)
        val known = state.machine(STAGE, stage)
        val facts = (ProjectFacts.ANSWERED.keys + ProjectFacts.NOT_READ_YET.keys).map { name -> world.observation(FactRef(name, emptyMap()), stage)!! } +
            // GitHub's, as the service reads them — asked of GitHub only for a stage whose worktree.create stands (S3.11).
            GitHubFacts.ANSWERED.keys.map { name -> FactRef(name, mapOf(STAGE to Datum.Text(stage))) to github.observation(FactRef(name, emptyMap()), stage)!! }
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
