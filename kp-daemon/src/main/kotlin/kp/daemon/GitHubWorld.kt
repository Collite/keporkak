package kp.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.daemon.seats.Roots
import kp.daemon.seats.Seats
import kp.facts.Checkouts
import kp.facts.GitHub
import kp.facts.GitHubAccess
import kp.facts.GitHubFacts
import kp.facts.Handed
import kp.facts.Observation
import kp.facts.StageState
import kp.facts.WhatTheLogKnows
import kp.mailroom.FactSource
import kp.model.Envelope
import kp.model.Record
import kp.ops.App
import kp.ops.Asked
import kp.ops.GitHubApps
import kp.ops.Minted
import java.net.URI
import java.nio.file.Path

/**
 * GitHub's facts for the service (contracts.md §4.4, Amendment 33; S3.2): `kp-facts` reads the stage's pull request and
 * the check runs of a sha with a token `kp-ops` mints as `kp-merger`, narrowed to the stage's repository and to reading
 * (`contents`, `pull_requests`, `checks`: read), and with the name GitHub shows for the reviewer's App. The block
 * `github:`, the office's checkouts and its secrets folder are those of the rules in force, read at each ask; nothing is
 * kept from one ask for the next. A parameter the rules leave out is the message's stage's; the rules write `ci.state`'s
 * sha as `of:`. An unknown fact is answered null and its reason goes to the journal — never a token. [api] is GitHub's
 * REST root.
 */
class GitHubWorld private constructor(
    private val office: String,
    private val project: Path,
    private val api: URI,
    private val say: (String) -> Unit,
) : FactSource {
    @Volatile private var rules: () -> InForceRules? = { null }
    @Volatile private var log: WhatTheLogKnows = OfficeLog()

    /** Reads [office]'s fold and its rules in force from now on: until then, every GitHub fact is unknown. */
    fun reading(office: Office) = reading({ office.rules }, OfficeLog().also { it.office = office })

    /** Reads with the rules [rules] hands over and what [log] knows, from now on: the daemon's office's, or a reading of its log. */
    fun reading(rules: () -> InForceRules?, log: WhatTheLogKnows) {
        this.rules = rules
        this.log = log
    }

    override fun observe(fact: FactRef, stage: String?): Datum? = when (val answer = observation(fact, stage)) {
        null -> null
        is Observation.Value -> Datum.of(answer.value)
        is Observation.Unknown -> null.also { say("facts: $fact for ${stage ?: "no stage"} is unknown — ${answer.missing}") }
    }

    /** A view's ask — `kp inbox`: the same answer, and no journal line (Amendment 34). */
    val view: FactSource = FactSource { fact, stage -> (observation(fact, stage) as? Observation.Value)?.let { Datum.of(it.value) } }

    /** [fact]'s answer for a judgement about [stage], with its evidence: a value, or unknown and why; null when the fact is not GitHub's. */
    fun observation(fact: FactRef, stage: String?): Observation? {
        val known = GitHubFacts.ANSWERED[fact.name] ?: return null
        val rules = rules()?.ruleSet?.pool ?: return Observation.Unknown("the daemon has not read its rules yet")
        val block = rules.github ?: return Observation.Unknown("pool/offices.yaml has no block github:")
        val entry = rules.offices[office] ?: return Observation.Unknown("pool/offices.yaml names no office '$office'")
        val apps = GitHubApps(Roots.of(entry.roots).secrets.resolve("apps"), api)
        val access = Access(block, apps)
        val checkouts = Checkouts(office, project, entry.project.efforts, entry.repos.toSortedMap().mapValues { (_, checkout) -> Seats.home(checkout) })
        val params = fact.params.mapValues { (_, value) -> plain(value) }.toMutableMap()
        if (fact.name == CI_STATE && SHA !in params) params.remove(OF)?.let { params[SHA] = it }
        known.params.filterNot { it in params }.forEach { name -> if (name == STAGE && stage != null) params[STAGE] = stage }
        return try {
            GitHubFacts(checkouts, log, GitHub(block.organization, api), access).observe(fact.name, params)
        } catch (error: Exception) {
            Observation.Unknown(error.toString())
        }
    }

    /** What `kp-facts` is handed: a token minted as `kp-merger` for one repository and reading; the reviewer's App's name. */
    private class Access(private val block: kp.rules.GitHub, private val apps: GitHubApps) : GitHubAccess {
        override fun token(repository: String): Handed {
            val merger = block.apps[MERGER] ?: return Handed.Withheld("github.apps holds no App $MERGER")
            return when (val minted = apps.mint(App(MERGER, merger.app, merger.installation), listOf(repository.substringAfter('/')), GitHubApps.READING)) {
                is Minted.Ok -> Handed.Over(minted.token.authorization())
                is Minted.Failed -> Handed.Withheld(minted.reason)
            }
        }

        override fun reviewer(): Handed {
            val seat = block.seats[REVIEWER] ?: return Handed.Withheld("github.seats gives the role $REVIEWER no App")
            val ids = block.apps[seat.app] ?: return Handed.Withheld("github.apps holds no App ${seat.app}")
            return when (val name = apps.name(App(seat.app, ids.app, ids.installation))) {
                is Asked.Ok -> Handed.Over(name.value)
                is Asked.Failed -> Handed.Withheld(name.reason)
            }
        }
    }

    companion object {
        /** The identity the daemon reads GitHub as (Amendment 33). */
        const val MERGER = "kp-merger"
        private const val REVIEWER = "reviewer"
        private const val CI_STATE = "ci.state"
        private const val SHA = "sha"
        private const val OF = "of"
        private const val STAGE = "stage"

        /** The GitHub world of [office], on the project checkout [project] the daemon runs on; [say] is the journal. */
        fun of(office: String, project: Path, api: URI = GitHubApps.GITHUB, say: (String) -> Unit = Journal::say): GitHubWorld = GitHubWorld(office, project, api, say)

        /** A parameter's value as `kp-facts` takes it: a text, or a list of texts. */
        private fun plain(value: Datum): Any = when (value) {
            is Datum.Text -> value.value
            is Datum.Items -> value.items.map { (it as? Datum.Text)?.value ?: it.json() }
            is Datum.Number -> value.value.toPlainString()
            else -> value.json()
        }
    }
}

/**
 * The service's world (S3.1, S3.2): the project's and git's facts ([ProjectWorld]) and GitHub's ([GitHubWorld]), each
 * fact asked of the one that holds it.
 */
class ServiceWorld(val project: ProjectWorld, val github: GitHubWorld) : FactSource {
    /** Reads [office]'s fold from now on — handed over as the office is made, before its start carries a judgement again (S3.8). */
    fun reading(office: Office) {
        project.reading(office)
        github.reading(office)
    }

    override fun observe(fact: FactRef, stage: String?): Datum? =
        if (fact.name in GitHubFacts.ANSWERED) github.observe(fact, stage) else project.observe(fact, stage)

    /** A view's ask — `kp inbox`: each world's view, writing no journal line (Amendment 34). */
    val view: FactSource = FactSource { fact, stage -> if (fact.name in GitHubFacts.ANSWERED) github.view.observe(fact, stage) else project.view.observe(fact, stage) }
}

/**
 * What the log knows, as the daemon hands it to `kp-facts` (contracts.md §4.4, Amendments 32 and 33): from [office]'s
 * fold, a stage's state and since when, the stages, and the states the rules call active; and GitHub's two questions
 * ([observedHead], [lastDelivered]). It hands values over and compares none.
 */
class OfficeLog : WhatTheLogKnows {
    /** The office whose fold and log are read; until it is set, nothing is. */
    @Volatile var office: Office? = null

    private fun folded(): Office = office ?: error("the fold is not read yet")

    override fun state(stage: String): StageState? = folded().state.machine(STAGE, stage)?.let { StageState(it.state, it.since) }

    override fun stages(): Map<String, StageState> = folded().state.machines[STAGE].orEmpty().mapValues { (_, machine) -> StageState(machine.state, machine.since) }

    override fun activeStates(): Set<String> = folded().rules.ruleSet.lifecycle.activeStates.toSet()

    override fun observedHead(message: String): String? = folded().observedHead(message)

    override fun lastDelivered(stage: String, kinds: Set<String>): String? = folded().lastDelivered(stage, kinds)

    private companion object {
        const val STAGE = "stage"
    }
}

/**
 * The `pr.head` the judgement of [message] observed: the `observed` of the record that judged it — `delivered` or
 * `refused` — read back from the log, which the fold does not keep (contracts.md §2.2); null when it observed none.
 */
internal fun Office.observedHead(message: String): String? = observedHead(counted(), message)

/** The `pr.head` the judgement of [message] observed, from the log's lines as they count, [counted]; null when it observed none. */
internal fun observedHead(counted: List<Envelope>, message: String): String? {
    val judged = counted.filterIsInstance<Record>().lastOrNull { record ->
        record.json["id"]?.jsonPrimitive?.contentOrNull == message && record.json["event"]?.jsonPrimitive?.contentOrNull in JUDGED
    } ?: return null
    return ((judged.json["observed"] as? JsonObject)?.get(HEAD) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** When [stage]'s last message of one of [kinds] was delivered, as the fold dates it; null when none was. */
internal fun Office.lastDelivered(stage: String, kinds: Set<String>): String? = state.lastDelivered(stage, kinds)

/** When [stage]'s last message of one of [kinds] was delivered, as this fold dates it; null when none was. */
internal fun State.lastDelivered(stage: String, kinds: Set<String>): String? = delivered.values.lastOrNull { it.stage == stage && it.kind in kinds }?.at

private const val HEAD = "pr.head"
private val JUDGED = setOf("delivered", "refused")
