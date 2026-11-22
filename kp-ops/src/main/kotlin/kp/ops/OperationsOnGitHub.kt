package kp.ops

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
    /** Merges [where]'s pull request on [head] — the `pr.head` the judgement of [cause] observed; null when it observed none. */
    fun perform(where: StageOnGitHub, cause: String, head: String?): Performed = Performed.Failed("no performer for git.merge yet")

    companion object {
        /** What merging asks of a token: GitHub's `X-Accepted-GitHub-Permissions` for the merge and for the list of pull requests (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("contents" to "write", "pull_requests" to "read")
    }
}

/**
 * `pr.close` (contracts.md §7, Amendment 37): as `kp-merger`, with a token minted for the stage's repository and for
 * [PERMISSIONS] alone, it closes the stage's pull request. One already closed, and none at all, are `ok`: there was
 * nothing to close, and the result says so. A merged one is `failed`.
 */
class PrClose(private val apps: GitHubApps, private val merger: App, api: URI = GitHubApps.GITHUB, http: HttpClient = GitHubApps.client()) {
    fun perform(where: StageOnGitHub): Performed = Performed.Failed("no performer for pr.close yet")

    companion object {
        /** What closing asks of a token: GitHub's `X-Accepted-GitHub-Permissions` for updating a pull request (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("pull_requests" to "write")
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
    fun perform(where: StageOnGitHub, cause: String, issues: List<Issue>): Performed = Performed.Failed("no performer for issue.file yet")

    companion object {
        /** What filing asks of a token: creating an issue, and reading the stage's pull request its last line names (S3.9 T1). */
        val PERMISSIONS: Map<String, String> = mapOf("issues" to "write", "pull_requests" to "read")
    }
}
