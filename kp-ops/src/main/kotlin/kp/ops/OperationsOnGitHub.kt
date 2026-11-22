package kp.ops

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kp.ops.Rest.Companion.text
import java.net.URI
import java.net.http.HttpClient

/**
 * What a performer answers (architecture.md §2, `kp-ops`): `ok`, with what the operation gives back — each value a text
 * or a list of numbers — or `failed`, with the reason. A performer retries nothing by itself.
 */
sealed interface Performed {
    data class Ok(val result: Map<String, Any> = emptyMap()) : Performed
    data class Failed(val reason: String) : Performed
}

/**
 * Where an operation on GitHub acts for [stage] (contracts.md §4.4, Amendment 33; §7, Amendment 37): its [repository] —
 * `<organization>/<repo>`, the `<repo>` of its list's title — and its [branch], the one its list's first line names: the
 * stage's pull request is the one whose head it is.
 */
data class StageOnGitHub(val stage: String, val repository: String, val branch: String)

/**
 * `git.merge` (contracts.md §7, Amendment 37): as `kp-merger`, with a token minted for the stage's repository and for
 * [PERMISSIONS] alone, it asks GitHub once to merge the stage's pull request with a merge commit, on the condition that
 * its head is still the head the `merge` message's judgement observed. `ok` carries the merge commit's sha;
 * `failed(stale-ci)` — the head is no longer that sha; `failed(conflict)` — the pull request cannot be merged as it
 * stands; `failed(refused)` — anything else, with GitHub's reason. The reason begins with the failure's name.
 */
class GitMerge(private val apps: GitHubApps, private val merger: App, api: URI = GitHubApps.GITHUB, http: HttpClient = GitHubApps.client()) {
    private val rest = Rest(api, http)

    /**
     * Merges [where]'s pull request on [head] — the `pr.head` the judgement of [cause] observed; null when it observed
     * none, and then GitHub is asked nothing. The pull request is the stage's (Amendment 33): one that is not open is not
     * asked to merge — GitHub would call it unmergeable, which is no conflict.
     */
    fun perform(where: StageOnGitHub, cause: String, head: String?): Performed {
        if (head == null) return refused("the judgement of $cause observed no pr.head: there is no head to merge on")
        val token = when (val minted = apps.mint(merger, listOf(where.repository.substringAfter('/')), PERMISSIONS)) {
            is Minted.Ok -> minted.token
            is Minted.Failed -> return refused(minted.reason)
        }
        val pull = when (val looked = rest.stagePull(where, token)) {
            is Looked.Found -> looked.pull
            is Looked.None -> return refused(looked.why)
            is Looked.Failed -> return refused(looked.reason)
        }
        if (pull.state != Rest.OPEN) return refused("${pull.name} is ${if (pull.merged) "merged" else "closed"}: only an open pull request is merged")
        val path = "/repos/${where.repository}/pulls/${pull.number}/merge"
        val body = buildJsonObject {
            put("sha", head)
            put("merge_method", "merge")
        }
        val reply = when (val sent = rest.send("PUT", path, token, body)) {
            is Rest.Sent.Answered -> sent.reply
            is Rest.Sent.NotReached -> return refused(sent.reason)
        }
        return when {
            reply.status == Rest.OK -> (reply.json as? JsonObject)?.takeIf { (it["merged"] as? JsonPrimitive)?.booleanOrNull == true }?.text("sha")
                ?.let { Performed.Ok(mapOf(SHA to it)) } ?: refused("GitHub's answer to PUT $path names no merge commit")
            reply.status == HEAD_MOVED -> Performed.Failed("$STALE_CI: ${rest.refused("PUT", path, reply)}")
            reply.status == NOT_ALLOWED && reply.message in UNMERGEABLE -> Performed.Failed("$CONFLICT: ${rest.refused("PUT", path, reply)}")
            else -> refused(rest.refused("PUT", path, reply))
        }
    }

    private fun refused(reason: String) = Performed.Failed("$REFUSED: $reason")

    companion object {
        /** What merging asks of a token: GitHub's `X-Accepted-GitHub-Permissions` for the merge and for the list of pull requests (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("contents" to "write", "pull_requests" to "read")
        /** The three failures of a merge (Amendment 37), each the first word of a reason. */
        const val STALE_CI = "stale-ci"
        const val CONFLICT = "conflict"
        const val REFUSED = "refused"
        private const val SHA = "sha"
        /** GitHub's answer when the head is not the sha the merge names (409, recorded from the toy). */
        private const val HEAD_MOVED = 409
        /** GitHub's answer when a merge cannot be performed (405): a conflict, or a rule of the repository. */
        private const val NOT_ALLOWED = 405
        /**
         * GitHub's messages that say the pull request cannot be merged as it stands: the first recorded from the toy, the
         * second what GitHub answers a pull request whose mergeability says no for another cause. Any other 405 — a rule
         * of the repository among them ("Repository rule violations found …") — is `refused`.
         */
        private val UNMERGEABLE = setOf("Pull Request has merge conflicts", "Pull Request is not mergeable")
    }
}

/**
 * `pr.close` (contracts.md §7, Amendment 37): as `kp-merger`, with a token minted for the stage's repository and for
 * [PERMISSIONS] alone, it closes the stage's pull request. One already closed, and none at all, are `ok`: there was
 * nothing to close, and the result says so. A merged one is `failed`.
 */
class PrClose(private val apps: GitHubApps, private val merger: App, api: URI = GitHubApps.GITHUB, http: HttpClient = GitHubApps.client()) {
    private val rest = Rest(api, http)

    /** Closes [where]'s pull request: `ok` names it, or says why there was nothing to close. */
    fun perform(where: StageOnGitHub): Performed {
        val token = when (val minted = apps.mint(merger, listOf(where.repository.substringAfter('/')), PERMISSIONS)) {
            is Minted.Ok -> minted.token
            is Minted.Failed -> return Performed.Failed(minted.reason)
        }
        val pull = when (val looked = rest.stagePull(where, token)) {
            is Looked.Found -> looked.pull
            is Looked.None -> return Performed.Ok(mapOf(CLOSED to "$NOTHING: ${looked.why}"))
            is Looked.Failed -> return Performed.Failed(looked.reason)
        }
        if (pull.merged) return Performed.Failed("${pull.name} is merged: a merged pull request is not closed")
        if (pull.state != Rest.OPEN) return Performed.Ok(mapOf(CLOSED to "$NOTHING: ${pull.name} was closed already"))
        val path = "/repos/${where.repository}/pulls/${pull.number}"
        val reply = when (val sent = rest.send("PATCH", path, token, buildJsonObject { put("state", "closed") })) {
            is Rest.Sent.Answered -> sent.reply
            is Rest.Sent.NotReached -> return Performed.Failed(sent.reason)
        }
        val state = (reply.json as? JsonObject)?.text("state")
        return when {
            reply.status != Rest.OK -> Performed.Failed(rest.refused("PATCH", path, reply))
            state != "closed" -> Performed.Failed("GitHub's answer to PATCH $path leaves ${pull.name} ${state ?: "in no state"}")
            else -> Performed.Ok(mapOf(CLOSED to pull.name))
        }
    }

    companion object {
        /** What closing asks of a token: GitHub's `X-Accepted-GitHub-Permissions` for updating a pull request (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("pull_requests" to "write")
        private const val CLOSED = "closed"
        private const val NOTHING = "nothing to close"
    }
}

/** One issue to file: its [title] and its [body], before the line that names where it came from. */
data class Issue(val title: String, val body: String) {
    companion object {
        /** An item of `follow_ups` (Amendment 37): its first line is the title, the rest the body. */
        fun followUp(item: String): Issue = Issue(item.substringBefore('\n').trim(), item.substringAfter('\n', "").trim())

        /** A `found`: its text is the title. */
        fun found(text: String): Issue = Issue(text.trim(), "")
    }
}

/**
 * `issue.file` (contracts.md §7, Amendment 37): as `kp-merger`, with a token minted for the stage's repository and for
 * [PERMISSIONS] alone, it files each issue in the stage's repository, in order; each body ends with a line that names the
 * stage, the message that asked and the pull request. `ok` carries the issues' numbers, in order; if GitHub refuses one,
 * the operation is `failed`, names the numbers already filed, and files no further one.
 */
class IssueFile(private val apps: GitHubApps, private val merger: App, api: URI = GitHubApps.GITHUB, http: HttpClient = GitHubApps.client()) {
    private val rest = Rest(api, http)

    /**
     * Files [issues] in [where]'s repository, in order, for the message [cause]: with none, GitHub is asked nothing. The
     * pull request the last line names is the stage's — `none` when the stage has none; a list GitHub could not give
     * files nothing.
     */
    fun perform(where: StageOnGitHub, cause: String, issues: List<Issue>): Performed {
        if (issues.isEmpty()) return Performed.Ok(mapOf(NUMBERS to emptyList<Int>()))
        val token = when (val minted = apps.mint(merger, listOf(where.repository.substringAfter('/')), PERMISSIONS)) {
            is Minted.Ok -> minted.token
            is Minted.Failed -> return Performed.Failed(minted.reason)
        }
        val pull = when (val looked = rest.stagePull(where, token)) {
            is Looked.Found -> looked.pull.name
            is Looked.None -> "none"
            is Looked.Failed -> return Performed.Failed("no issue was filed: ${looked.reason}")
        }
        val last = "Filed for ${where.stage} · asked by $cause · pull request $pull"
        val path = "/repos/${where.repository}/issues"
        val filed = ArrayList<Int>()
        for ((index, issue) in issues.withIndex()) {
            val body = buildJsonObject {
                put("title", issue.title)
                put("body", if (issue.body.isEmpty()) last else "${issue.body}\n\n$last")
            }
            val number = when (val sent = rest.send("POST", path, token, body)) {
                is Rest.Sent.NotReached -> null to sent.reason
                is Rest.Sent.Answered -> sent.reply.let { reply ->
                    val number = ((reply.json as? JsonObject)?.get("number") as? JsonPrimitive)?.intOrNull
                    when {
                        reply.status != Rest.CREATED -> null to rest.refused("POST", path, reply)
                        number == null -> null to "GitHub's answer to POST $path names no issue number"
                        else -> number to null
                    }
                }
            }
            val (issued, why) = number
            if (issued == null) {
                val before = if (filed.isEmpty()) "none" else filed.joinToString(", ") { "#$it" }
                return Performed.Failed("issue ${index + 1} of ${issues.size} was not filed, nor any after it: $why — filed: $before")
            }
            filed += issued
        }
        return Performed.Ok(mapOf(NUMBERS to filed.toList()))
    }

    companion object {
        /** What filing asks of a token: creating an issue, and reading the stage's pull request its last line names (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("issues" to "write", "pull_requests" to "read")
        private const val NUMBERS = "numbers"
    }
}
