package kp.daemon

import kp.daemon.seats.Roots
import kp.daemon.seats.Seats
import kp.engine.Datum
import kp.facts.Checkouts
import kp.facts.GitHub
import kp.facts.GitHubAccess
import kp.facts.GitHubFacts
import kp.facts.Handed
import kp.facts.Located
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.ops.App
import kp.ops.GitHubApps
import kp.ops.GitMerge
import kp.ops.Issue
import kp.ops.IssueFile
import kp.ops.Performed
import kp.ops.PrClose
import kp.ops.StageOnGitHub
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * The service's operations on GitHub (contracts.md §7, Amendment 37; S3.9): `git.merge`, `pr.close` and `issue.file`,
 * each performed once by `kp-ops` as `kp-merger`, on a thread of its own — the mailroom judges the result when it comes,
 * and holds no lock meanwhile. The stage is the request's `with.stage`, else its cause's; its repository and branch are
 * read from its list as the facts read them (Amendment 33); the block `github:`, the office's checkouts and its secrets
 * folder are those of the rules in force. A merge's head is the `pr.head` the judgement of its `merge` observed, read from
 * the log ([observedHead]) and never from GitHub. A request with no result at a start is the mailroom's to end
 * `failed: interrupted`: nothing here is performed again. Every other operation is [other]'s. [say] is the journal: it
 * hears each failure's reason, never a token — no token reaches this class.
 */
class GitHubOperations private constructor(
    private val office: String,
    private val project: Path,
    private val api: URI,
    private val other: Operations,
    private val say: (String) -> Unit,
) : Operations {
    @Volatile private var reading: Office? = null
    private val work = Executors.newCachedThreadPool { Thread(it, "kp-github-operations").apply { isDaemon = true } }

    /** Reads [office]'s fold, its log and its rules in force from now on: until then, an operation on GitHub fails. */
    fun reading(office: Office) {
        reading = office
    }

    /**
     * What an operation works on, its lane (Amendment 36): one on GitHub works on no checkout of the office's, and runs on
     * its own; the rest, as [other] says.
     */
    override fun on(request: OperationRequest): String? = if (request.operation in PERFORMED) null else other.on(request)

    /** What an interrupted operation left: one on GitHub leaves nothing on the office's disk; the rest, as [other] says. */
    override fun left(request: OperationRequest): String? = if (request.operation in PERFORMED) null else other.left(request)

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
        if (request.operation !in PERFORMED) return other.perform(request, done)
        work.execute {
            val performed = try {
                performed(request)
            } catch (error: Exception) {
                failed(request, "${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}")
            }
            if (performed is Performed.Failed) say("github: ${request.operation} for ${request.cause} failed — ${performed.reason}")
            done(performed.result())
        }
    }

    private fun performed(request: OperationRequest): Performed {
        val office = reading ?: return failed(request, "the daemon has not read its log yet")
        val pool = office.rules.ruleSet.pool
        val block = pool.github ?: return failed(request, "pool/offices.yaml has no block github:")
        val entry = pool.offices[this.office] ?: return failed(request, "pool/offices.yaml names no office '${this.office}'")
        val ids = block.apps[MERGER] ?: return failed(request, "github.apps holds no App $MERGER")
        val stage = (request.with.fields[STAGE] as? Datum.Text)?.value ?: office.state.delivered[request.cause]?.stage
            ?: return failed(request, "neither the request nor its cause ${request.cause} names a stage")
        val checkouts = Checkouts(this.office, project, entry.project.efforts, entry.repos.toSortedMap().mapValues { (_, checkout) -> Seats.home(checkout) })
        val where = when (val located = GitHubFacts(checkouts, OfficeLog().also { it.office = office }, GitHub(block.organization, api), NO_ACCESS).located(stage)) {
            is Located.At -> StageOnGitHub(stage, located.repository, located.branch)
            is Located.Unknown -> return failed(request, "where $stage acts on GitHub is unknown: ${located.missing}")
        }
        val apps = GitHubApps(Roots.of(entry.roots).secrets.resolve("apps"), api)
        val merger = App(MERGER, ids.app, ids.installation)
        return when (request.operation) {
            GIT_MERGE -> GitMerge(apps, merger, api).perform(where, request.cause, office.observedHead(request.cause))
            PR_CLOSE -> PrClose(apps, merger, api).perform(where)
            else -> IssueFile(apps, merger, api).perform(where, request.cause, issues(request) ?: return failed(request, "its follow_ups are not texts, nor is its found"))
        }
    }

    /** The issues [request] asks for (Amendment 37): one per item of `follow_ups`, or one for a `found`; null when one is no text. */
    private fun issues(request: OperationRequest): List<Issue>? {
        val with = request.with.fields
        (with[FOUND] as? Datum.Text)?.let { return listOf(Issue.found(it.value)) }
        val items = (with[FOLLOW_UPS] as? Datum.Items)?.items ?: return if (with[FOLLOW_UPS] == null) emptyList() else null
        return items.map { (it as? Datum.Text)?.value?.let(Issue::followUp) ?: return null }
    }

    /** A failure before GitHub is asked: for a merge, `refused` — a merge's failures are three (Amendment 37). */
    private fun failed(request: OperationRequest, reason: String) =
        Performed.Failed(if (request.operation == GIT_MERGE) "${GitMerge.REFUSED}: $reason" else reason)

    companion object {
        /** The identity the daemon acts on GitHub as (Amendment 37). */
        const val MERGER = "kp-merger"
        private const val GIT_MERGE = "git.merge"
        private const val PR_CLOSE = "pr.close"
        private const val ISSUE_FILE = "issue.file"
        private val PERFORMED = setOf(GIT_MERGE, PR_CLOSE, ISSUE_FILE)
        private const val STAGE = "stage"
        private const val FOUND = "found"
        private const val FOLLOW_UPS = "follow_ups"

        /** Where a stage acts is read from its list alone: nothing is minted for it. */
        private val NO_ACCESS = object : GitHubAccess {
            override fun token(repository: String) = Handed.Withheld("an operation's place is read from its list, not from GitHub")
            override fun reviewer() = Handed.Withheld("an operation's place is read from its list, not from GitHub")
        }

        /** The operations on GitHub of [office], on the project checkout [project]; [other] performs the rest; [say] is the journal. */
        fun of(office: String, project: Path, other: Operations, api: URI = GitHubApps.GITHUB, say: (String) -> Unit = Journal::say): GitHubOperations =
            GitHubOperations(office, project, api, other, say)

        /** A performer's answer as the mailroom records it: a text, or a list of numbers, per field of the result. */
        private fun Performed.result(): OperationResult = when (this) {
            is Performed.Failed -> OperationResult.Failed(reason)
            is Performed.Ok -> OperationResult.Ok(Datum.Fields(result.mapValues { (_, value) ->
                when (value) {
                    is List<*> -> Datum.Items(value.map { Datum.Number(BigDecimal(it.toString())) })
                    else -> Datum.Text(value.toString())
                }
            }))
        }
    }
}
