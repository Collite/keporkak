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
 * that is unknown is answered null. What was missing goes to the journal when a judgement asked; a view — `kp inbox` —
 * asks through [view] and writes none (Amendment 34). It hands values over and compares none; a fact `kp-facts` does
 * not hold is not its to answer.
 */
class ProjectWorld private constructor(
    private val office: String,
    private val facts: (WhatTheLogKnows) -> ProjectFacts,
    private val say: (String) -> Unit,
    /** What it reads, as the daemon says at start: the project checkout, its efforts' folders, the main checkouts. */
    val reads: String,
) : FactSource {
    /** What the log knows — the office's fold, or a reading of its log — once it has one. */
    @Volatile private var fold: WhatTheLogKnows? = null
    private val log = object : WhatTheLogKnows {
        private fun folded(): WhatTheLogKnows = fold ?: error("the fold is not read yet")
        override fun state(stage: String): StageState? = folded().state(stage)
        override fun stages(): Map<String, StageState> = folded().stages()
        override fun activeStates(): Set<String> = folded().activeStates()
        override fun observedHead(message: String): String? = folded().observedHead(message)
        override fun lastDelivered(stage: String, kinds: Set<String>): String? = folded().lastDelivered(stage, kinds)
    }
    private val project = facts(log)

    /**
     * Reads [office]'s fold from now on: until then, a fact that reads the log is unknown. The daemon hands it over as
     * the office is made, before its start carries any judgement again ([Office.start], Amendment 34).
     */
    fun reading(office: Office) = reading(OfficeLog().also { it.office = office })

    /** Reads what [log] knows from now on: the daemon's office's, or a reading of its log (`just facts`). */
    fun reading(log: WhatTheLogKnows) {
        fold = log
    }

    /** A judgement's ask: what is unknown goes to the journal, each time. */
    override fun observe(fact: FactRef, stage: String?): Datum? = answer(fact, stage, journal = true)

    /** A view's ask — `kp inbox`'s three lines of a stage list: the same answer, and no journal line (Amendment 34). */
    val view: FactSource = FactSource { fact, stage -> answer(fact, stage, journal = false) }

    /**
     * [fact] for [stage] as `kp-facts` answers it, with its evidence, and the fact with the parameters it was asked with —
     * what `just facts` prints; null when `kp-facts` does not hold it. It writes no journal line.
     */
    fun observation(fact: FactRef, stage: String?): Pair<FactRef, Observation>? {
        val known = ProjectFacts.ANSWERED[fact.name]
        if (known == null && fact.name !in ProjectFacts.NOT_READ_YET) return null
        val asked = FactRef(fact.name, fact.params + known?.params.orEmpty().filterNot { it in fact.params }.mapNotNull { name -> left(name, stage)?.let { name to Datum.Text(it) } })
        if (known?.readsTheLog == true && fold == null) return asked to Observation.Unknown("the daemon has not folded its log yet")
        val answer = try {
            project.observe(fact.name, asked.params.mapValues { (_, value) -> plain(value) })
        } catch (error: Exception) {
            Observation.Unknown(error.toString())
        }
        return asked to answer
    }

    private fun answer(fact: FactRef, stage: String?, journal: Boolean): Datum? = when (val answer = observation(fact, stage)?.second) {
        null -> null
        is Observation.Value -> Datum.of(answer.value)
        is Observation.Unknown -> {
            if (journal) say("facts: $fact for ${stage ?: "no stage"} is unknown — ${answer.missing}")
            null
        }
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
