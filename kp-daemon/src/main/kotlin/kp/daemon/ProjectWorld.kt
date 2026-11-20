package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.Checkouts
import kp.facts.Git
import kp.facts.Observation
import kp.facts.ProjectFacts
import kp.facts.StageState
import kp.facts.WhatTheLogKnows
import kp.mailroom.FactSource
import java.nio.file.Path

/**
 * The service's world (contracts.md §4.4, Amendment 32; S3.1 T7): `kp-facts` over the office's project checkout and its
 * main checkouts (§9: `project`, `repos`), and what the log knows, from the office's fold. A parameter the rules leave
 * out is taken from the message's stage — the stage, its phase, its effort — and `office` is the daemon's own. A fact
 * that is unknown is answered null, and what was missing goes to the journal. It hands values over and compares none;
 * a fact `kp-facts` does not hold is not its to answer.
 */
class ProjectWorld private constructor(
    private val office: String,
    private val facts: (WhatTheLogKnows) -> ProjectFacts,
    private val say: (String) -> Unit,
    /** What it reads, as the daemon says at start: the project checkout, its efforts' folders, the main checkouts. */
    val reads: String,
) : FactSource {
    @Volatile private var fold: Office? = null
    private val log = object : WhatTheLogKnows {
        private fun folded(): Office = fold ?: error("the fold is not read yet")
        override fun state(stage: String): StageState? = folded().state.machine(STAGE, stage)?.let { StageState(it.state, it.since) }
        override fun stages(): Map<String, StageState> = folded().state.machines[STAGE].orEmpty().mapValues { (_, machine) -> StageState(machine.state, machine.since) }
        override fun activeStates(): Set<String> = folded().rules.ruleSet.lifecycle.activeStates.toSet()
    }
    private val project = facts(log)

    /** Reads [office]'s fold from now on: until then, a fact that reads the log is unknown. */
    fun reading(office: Office) {
        fold = office
    }

    override fun observe(fact: FactRef, stage: String?): Datum? {
        val known = ProjectFacts.ANSWERED[fact.name]
        if (known == null && fact.name !in ProjectFacts.NOT_READ_YET) return null
        val about = "$fact for ${stage ?: "no stage"}"
        if (known?.readsTheLog == true && fold == null) return unknown(about, "the daemon has not folded its log yet")
        val params = fact.params.mapValues { (_, value) -> plain(value) }.toMutableMap()
        known?.params?.filterNot { it in params }?.forEach { name -> left(name, stage)?.let { params[name] = it } }
        val answer = try {
            project.observe(fact.name, params)
        } catch (error: Exception) {
            return unknown(about, error.toString())
        }
        return when (answer) {
            is Observation.Value -> Datum.of(answer.value)
            is Observation.Unknown -> unknown(about, answer.missing)
        }
    }

    private fun unknown(about: String, missing: String): Datum? {
        say("facts: $about is unknown — $missing")
        return null
    }

    /** A parameter the rules left out, from the message's [stage] (Amendment 32); null when there is none to take it from. */
    private fun left(name: String, stage: String?): String? = when (name) {
        "office" -> office
        "stage" -> stage
        "phase" -> stage?.substringBeforeLast('/')
        "effort" -> stage?.substringBefore('/')
        else -> null
    }

    companion object {
        private const val STAGE = "stage"

        /**
         * The world of [office] — its entry in [rules]' `pool/offices.yaml` — on the project checkout [project] the daemon
         * runs on, with `git` looked up on [path]; [say] is the journal.
         */
        fun of(rules: InForceRules, office: String, project: Path, path: String? = System.getenv("PATH"), say: (String) -> Unit = Journal::say): ProjectWorld {
            val entry = requireNotNull(rules.ruleSet.pool.offices[office]) { "pool/offices.yaml names no office '$office'" }
            val repos = entry.repos.toSortedMap().mapValues { (_, checkout) -> home(checkout) }
            val checkouts = Checkouts(office, project, entry.project.efforts, repos)
            val reads = "facts: the project checkout $project (efforts ${entry.project.efforts.entries.joinToString(", ") { "${it.key}: ${it.value}" }}); " +
                "main checkouts ${repos.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" }}"
            return ProjectWorld(office, { log -> ProjectFacts(checkouts, log, Git(path)) }, say, reads)
        }

        /** `~` at the start of [checkout] is the office user's home (contracts.md §9). */
        private fun home(checkout: String): Path = Path.of(checkout.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))

        /** A parameter's value as `kp-facts` takes it: a text, or a list of texts. */
        private fun plain(value: Datum): Any = when (value) {
            is Datum.Text -> value.value
            is Datum.Items -> value.items.map { (it as? Datum.Text)?.value ?: it.json() }
            is Datum.Number -> value.value.toPlainString()
            else -> value.json()
        }
    }
}
