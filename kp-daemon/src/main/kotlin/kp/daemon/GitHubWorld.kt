package kp.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.GitHubFacts
import kp.facts.StageState
import kp.facts.WhatTheLogKnows
import kp.mailroom.FactSource
import kp.model.Record
import kp.ops.GitHubApps
import java.net.URI
import java.nio.file.Path

/**
 * GitHub's facts for the service (contracts.md §4.4, Amendment 33; S3.2): `kp-facts` reads the stage's pull request and
 * the check runs of a sha with a token `kp-ops` mints as `kp-merger`, narrowed to the stage's repository and to reading.
 * The block `github:` and the secrets folder are those of the rules in force. An unknown's reason goes to the journal,
 * never a token. [api] is GitHub's REST root.
 */
class GitHubWorld private constructor(
    private val office: String,
    private val project: Path,
    private val api: URI,
    private val say: (String) -> Unit,
) : FactSource {
    @Volatile private var fold: Office? = null

    /** Reads [office]'s fold and its rules in force from now on: until then, every GitHub fact is unknown. */
    fun reading(office: Office) {
        fold = office
    }

    override fun observe(fact: FactRef, stage: String?): Datum? = null

    companion object {
        /** The GitHub world of [office], on the project checkout [project] the daemon runs on; [say] is the journal. */
        fun of(office: String, project: Path, api: URI = GitHubApps.GITHUB, say: (String) -> Unit = Journal::say): GitHubWorld = GitHubWorld(office, project, api, say)
    }
}

/**
 * The service's world (S3.1, S3.2): the project's and git's facts ([ProjectWorld]) and GitHub's ([GitHubWorld]), each
 * fact asked of the one that holds it.
 */
class ServiceWorld(val project: ProjectWorld, val github: GitHubWorld) : FactSource {
    fun reading(office: Office) {
        project.reading(office)
        github.reading(office)
    }

    override fun observe(fact: FactRef, stage: String?): Datum? =
        if (fact.name in GitHubFacts.ANSWERED) github.observe(fact, stage) else project.observe(fact, stage)
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
internal fun Office.observedHead(message: String): String? {
    val judged = counted().filterIsInstance<Record>().lastOrNull { record ->
        record.json["id"]?.jsonPrimitive?.contentOrNull == message && record.json["event"]?.jsonPrimitive?.contentOrNull in JUDGED
    } ?: return null
    return ((judged.json["observed"] as? JsonObject)?.get(HEAD) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** When [stage]'s last message of one of [kinds] was delivered, as the fold dates it; null when none was. */
internal fun Office.lastDelivered(stage: String, kinds: Set<String>): String? = state.delivered.values.lastOrNull { it.stage == stage && it.kind in kinds }?.at

private const val HEAD = "pr.head"
private val JUDGED = setOf("delivered", "refused")
