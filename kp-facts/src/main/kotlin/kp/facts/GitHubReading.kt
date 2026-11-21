package kp.facts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One observation of GitHub (Amendment 33): the stage's repository and pull request, read with the token handed over
 * for it. What it reads is read once, here, and kept nowhere after.
 */
internal class GitHubReading(private val reading: Reading, private val github: GitHub, private val access: GitHubAccess, private val http: HttpClient) {
    private val asked: Asked get() = reading.asked
    private val stage: StageId by lazy { reading.stage() }
    private val heading: Heading by lazy { reading.heading(stage) }

    /** `<organization>/<repo>`: the repository of the list's title (Amendment 32), in the organization of §9's block. */
    private val repository: String by lazy {
        val repo = heading.repo()
        if (!NAME.matches(repo) || !NAME.matches(github.organization)) throw Missing("'${github.organization}/$repo' is no repository name GitHub gives")
        "${github.organization}/$repo"
    }

    private val authorization: String by lazy {
        when (val handed = access.token(repository)) {
            is Handed.Over -> handed.text
            is Handed.Withheld -> throw Missing("no token to read $repository: ${handed.reason}")
        }
    }

    /** The stage's pull request — the one whose head is the list's branch: the open one, else the one made last — or null when there is none. */
    private val pull: PullRequest? by lazy {
        val branch = heading.branch()
        val path = "repos/$repository/pulls"
        val all = pages(path, mapOf("head" to "${github.organization}:$branch", "state" to "all")).map { PullRequest.of(it, path) }
        val open = all.filter { it.state == "open" }
        when {
            open.size > 1 -> throw Ambiguous("$repository holds ${open.size} open pull requests whose head is $branch: ${open.joinToString(", ") { "#${it.number}" }}")
            open.size == 1 -> open.single()
            else -> all.maxWithOrNull(compareBy<PullRequest> { it.created }.thenBy { it.number })
        }
    }

    private fun none(): String = "$repository holds no pull request whose head is ${heading.branch()}"

    private fun pullRequest(): PullRequest = pull ?: throw Missing(none())

    fun ready(): Observation = pull?.let { Observation.Value(it.state == "open" && !it.draft, it.evidence(repository)) } ?: Observation.Value(false, none())

    fun closed(): Observation = pull?.let { Observation.Value(it.state == "closed" && it.mergedAt == null, it.evidence(repository)) } ?: Observation.Value(false, none())

    fun merged(): Observation = pull?.let { Observation.Value(it.mergedAt != null, it.evidence(repository)) } ?: Observation.Value(false, none())

    fun head(): Observation = pullRequest().let { Observation.Value(it.head, it.evidence(repository)) }

    /**
     * `ci.state` of a sha, from its check runs: none — there is none; pending — one is not completed; red — all are
     * completed and one ended otherwise than success, neutral or skipped; green — all are completed and each ended so.
     */
    fun ciState(): Observation {
        val sha = sha("sha")
        val path = "repos/$repository/commits/$sha/check-runs"
        val runs = pages(path, emptyMap(), "check_runs").map { run ->
            Run(run.text("name") ?: "a check run", run.text("status") ?: throw Missing("a check run of $sha in GitHub's answer to GET $path has no status"), run.text("conclusion"))
        }
        val value = when {
            runs.isEmpty() -> "none"
            runs.any { it.status != "completed" } -> "pending"
            runs.any { it.conclusion !in PASSING } -> "red"
            else -> "green"
        }
        val listed = runs.joinToString("; ") { "${it.name}: ${it.status}${it.conclusion?.let { c -> " $c" }.orEmpty()}" }
        return Observation.Value(value, "$repository ${sha.take(SHORT)}: ${runs.size} check ${if (runs.size == 1) "run" else "runs"}${if (runs.isEmpty()) "" else " — $listed"}")
    }

    /**
     * `pr.review_on` a sha: the state of the last review submitted on that commit by the App that §9 gives the reviewer's
     * role, under the name GitHub shows for it; none when it left none there. A review by anyone else is not read.
     */
    fun reviewOn(): Observation {
        val sha = sha("sha")
        val pull = pullRequest()
        val name = when (val handed = access.reviewer()) {
            is Handed.Over -> handed.text
            is Handed.Withheld -> throw Missing("the name GitHub shows for the reviewer's App is not known: ${handed.reason}")
        }
        val login = "$name[bot]"
        val path = "repos/$repository/pulls/${pull.number}/reviews"
        val reviews = pages(path, emptyMap()).filter { review ->
            (review["user"] as? JsonObject)?.text("login") == login && review.text("commit_id") == sha && review.text("submitted_at") != null
        }
        val last = reviews.maxByOrNull { Instant.parse(it.text("submitted_at")) }
            ?: return Observation.Value("none", "$repository#${pull.number}: no review by $login on ${sha.take(SHORT)}")
        val state = last.text("state")
        val value = REVIEW_STATES[state]
            ?: throw Missing("$repository#${pull.number}: $login's last review on ${sha.take(SHORT)} is '$state', a state Amendment 33 does not give")
        return Observation.Value(value, "$repository#${pull.number}: $login's last review on ${sha.take(SHORT)}, submitted ${last.text("submitted_at")}, of ${reviews.size}")
    }

    /**
     * `pr.commits_since` a message: the commits the head now holds that the head its judgement observed did not — GitHub's
     * comparison of the two, `ahead_by`. A message whose judgement observed no head, or a head GitHub no longer knows: unknown.
     */
    fun commitsSince(): Observation {
        val message = asked.text("message")
        val then = reading.log.observedHead(message) ?: throw Missing("the judgement of $message observed no pr.head")
        if (!SHA.matches(then)) throw Missing("the judgement of $message observed '$then', which is no sha")
        val pull = pullRequest()
        val path = "repos/$repository/compare/$then...${pull.head}"
        val compared = try {
            single(path, emptyMap())
        } catch (missing: Missing) {
            throw Missing("GitHub does not compare the head $message observed, ${then.take(SHORT)}, with ${pull.head.take(SHORT)} — ${missing.message}")
        }
        val ahead = (compared["ahead_by"] as? JsonPrimitive)?.intOrNull ?: throw Missing("GitHub's answer to GET $path holds no ahead_by")
        return Observation.Value(ahead, "$repository ${then.take(SHORT)}...${pull.head.take(SHORT)}: ahead by $ahead (observed by the judgement of $message)")
    }

    /**
     * `pr.blocker_responses` for ids: those for which a comment on the pull request, written by its author after the stage's
     * last `verdict` or `re-verdict` was delivered, holds a line that begins with the id and is followed by something that
     * is neither a letter nor a digit.
     */
    fun blockerResponses(): Observation {
        val ids = asked.texts("ids")
        val since = reading.log.lastDelivered(stage.id, VERDICTS) ?: throw Missing("no verdict or re-verdict of ${stage.id} is delivered on the log")
        val after = try {
            Instant.parse(since)
        } catch (_: DateTimeParseException) {
            throw Missing("the log dates ${stage.id}'s last verdict '$since', which is no time")
        }
        val pull = pullRequest()
        val path = "repos/$repository/issues/${pull.number}/comments"
        val comments = pages(path, emptyMap()).filter { comment ->
            (comment["user"] as? JsonObject)?.text("login") == pull.author &&
                comment.text("created_at")?.let { Instant.parse(it).isAfter(after) } == true
        }
        val lines = comments.flatMap { (it.text("body") ?: "").lines() }
        val answered = ids.filter { id -> lines.any { it.length > id.length && it.startsWith(id) && !it[id.length].isLetterOrDigit() } }
        return Observation.Value(answered, "$repository#${pull.number}: ${comments.size} ${if (comments.size == 1) "comment" else "comments"} by ${pull.author} after $since")
    }

    /** `pr.evidence_present`: the description holds the six headings of the hand-off (EV-1), and text under `## Verified`. */
    fun evidencePresent(): Observation {
        val pull = pullRequest()
        val lines = pull.body.lines()
        val missing = HEADINGS.filter { heading -> lines.none { it.startsWith(heading) } }
        val verified = lines.indexOfFirst { it.startsWith(VERIFIED) }
        val under = if (verified < 0) emptyList() else lines.drop(verified + 1).takeWhile { !it.startsWith("## ") }
        val present = missing.isEmpty() && under.any { it.isNotBlank() }
        val evidence = when {
            missing.isNotEmpty() -> "the description of $repository#${pull.number} has no ${missing.joinToString(", ")}"
            !present -> "the description of $repository#${pull.number} has no text under $VERIFIED"
            else -> "the description of $repository#${pull.number}: the six headings, and ${under.count { it.isNotBlank() }} lines under $VERIFIED"
        }
        return Observation.Value(present, evidence)
    }

    private fun sha(name: String): String = asked.text(name).also { if (!SHA.matches(it)) throw Missing("the parameter '$name' is '$it', which is no sha") }

    /** One answer of GitHub: the JSON object at [path] with [query]. */
    private fun single(path: String, query: Map<String, String>): JsonObject =
        get(url(path, query), path).json as? JsonObject ?: throw Missing("GitHub's answer to GET $path is no JSON object")

    /**
     * Every item of the list at [path] — the answer itself, or its field [field] — on every page GitHub gives: the next
     * page is the `Link` header's `rel="next"`, and it is followed only on GitHub's own [GitHub.api].
     */
    private fun pages(path: String, query: Map<String, String>, field: String? = null): List<JsonObject> {
        val items = ArrayList<JsonObject>()
        var next: URI? = url(path, query + ("per_page" to PER_PAGE))
        while (next != null) {
            val page = get(next, path)
            val list = (if (field == null) page.json else (page.json as? JsonObject)?.get(field)) as? JsonArray
                ?: throw Missing("GitHub's answer to GET $path is no list${field?.let { " in '$it'" }.orEmpty()}")
            items += list.map { it as? JsonObject ?: throw Missing("GitHub's answer to GET $path lists what is no object") }
            next = page.next?.let { link ->
                val target = URI.create(link)
                if (!target.toString().startsWith(github.api.toString())) throw Missing("GitHub's next page of GET $path is not under ${github.api}")
                target
            }
        }
        return items
    }

    private class Page(val json: JsonElement, val next: String?)

    /** One request, made once; a failure is what was missing — GitHub's reason, never the token. */
    private fun get(url: URI, path: String): Page {
        val request = HttpRequest.newBuilder(url).timeout(GitHubFacts.TIMEOUT).GET()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", authorization)
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: IOException) {
            throw Missing("GitHub did not answer GET $path: ${error.javaClass.simpleName}${error.message?.let { ": $it" }.orEmpty()}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Missing("interrupted while asking GitHub GET $path")
        }
        val json = try {
            Json.parseToJsonElement(response.body())
        } catch (_: IllegalArgumentException) {
            null
        }
        if (response.statusCode() !in 200..299) throw Missing(refused(response, path, json))
        return Page(json ?: throw Missing("GitHub's answer to GET $path is not JSON"), next(response.headers().firstValue("link").orElse(null)))
    }

    /** GitHub's reason: the status and its `message`; a limit says so, with when it lifts. */
    private fun refused(response: HttpResponse<String>, path: String, json: JsonElement?): String {
        val message = (json as? JsonObject)?.text("message")
        val said = "GitHub answered ${response.statusCode()} to GET $path" + (message?.let { ": $it" } ?: "")
        val headers = response.headers()
        val remaining = headers.firstValue("x-ratelimit-remaining").orElse(null)
        val retry = headers.firstValue("retry-after").orElse(null)
        val limited = response.statusCode() == TOO_MANY || (response.statusCode() == FORBIDDEN && (remaining == "0" || retry != null))
        if (!limited) return said
        val reset = headers.firstValue("x-ratelimit-reset").orElse(null)?.toLongOrNull()?.let { Instant.ofEpochSecond(it).toString() }
        val lifts = listOfNotNull(remaining?.let { "x-ratelimit-remaining $it" }, reset?.let { "resets $it" }, retry?.let { "retry-after ${it}s" }).joinToString(", ")
        return "GitHub limits: $said${if (lifts.isEmpty()) "" else " ($lifts)"}"
    }

    private fun url(path: String, query: Map<String, String>): URI {
        val encoded = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return URI.create(github.api.toString().removeSuffix("/") + "/" + path + if (encoded.isEmpty()) "" else "?$encoded")
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

    /** A stage's pull request as GitHub lists it: the fields Amendment 33 reads. */
    private class PullRequest(
        val number: Int,
        val state: String,
        val draft: Boolean,
        val mergedAt: String?,
        val head: String,
        val author: String,
        val body: String,
        val created: String,
    ) {
        fun evidence(repository: String): String =
            "$repository#$number: $state${if (draft) ", a draft" else ""}${mergedAt?.let { ", merged $it" } ?: ""}, head ${head.take(SHORT)}"

        companion object {
            fun of(json: JsonObject, path: String): PullRequest {
                fun lacks(field: String): Nothing = throw Missing("a pull request in GitHub's answer to GET $path has no $field")
                return PullRequest(
                    number = (json["number"] as? JsonPrimitive)?.intOrNull ?: lacks("number"),
                    state = json.text("state") ?: lacks("state"),
                    draft = (json["draft"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull() ?: lacks("draft"),
                    mergedAt = json["merged_at"].let { if (it == null) lacks("merged_at") else (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content },
                    head = ((json["head"] as? JsonObject)?.text("sha"))?.takeIf { SHA.matches(it) } ?: lacks("head.sha"),
                    author = ((json["user"] as? JsonObject)?.text("login")) ?: lacks("user.login"),
                    body = json.text("body") ?: "",
                    created = json.text("created_at") ?: lacks("created_at"),
                )
            }
        }
    }

    private class Run(val name: String, val status: String, val conclusion: String?)

    companion object {
        /** The REST API's version every request names: GitHub's default, which it still serves. */
        const val API_VERSION = "2022-11-28"
        private const val PER_PAGE = "100"
        private const val SHORT = 7
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private val NAME = Regex("[A-Za-z0-9._-]+")
        private val SHA = Regex("[0-9a-fA-F]{7,40}")
        private val PASSING = setOf("success", "neutral", "skipped")
        private val REVIEW_STATES = mapOf("APPROVED" to "approved", "CHANGES_REQUESTED" to "changes_requested", "COMMENTED" to "commented")
        private val VERDICTS = setOf("verdict", "re-verdict")
        private const val VERIFIED = "## Verified"
        private val HEADINGS = listOf("## Stage", "## What landed", "## DONE line", VERIFIED, "## Not in this stage", "## Found while doing")
        private val LINK_NEXT = Regex("<([^>]+)>;\\s*rel=\"next\"")

        private fun next(link: String?): String? = link?.let { LINK_NEXT.find(it)?.groupValues?.get(1) }

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
