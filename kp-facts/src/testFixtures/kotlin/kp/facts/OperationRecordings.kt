package kp.facts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.RecordedGitHub.Companion.recordedItem

/*
 * What S3.9 recorded on Panda from `Collite/kp-toy` (T1), as the Apps `kp-merger` and `kp-coder`, every token taken out:
 * pull request #3 between two branches of the recording's own — open, then merged; #4 into the default branch — the
 * coder's merge and a merge on another head refused there, then closed unmerged; #5 — a conflict; issue #6. Each answer
 * is in `github/kp-toy/` as GitHub gave it: `pulls-open.json`, `pulls-merged.json`, `pulls-closed.json` — the list of
 * pull requests whose head is a branch; `merge-200.json`, `merge-409.json`, `merge-405-conflict.json`,
 * `merge-405-rule.json`, `merge-403.json` — what `PUT …/pulls/<n>/merge` answered; `pull-closed.json` — `PATCH
 * …/pulls/4`; `issue-201.json`, `issue-403.json` — `POST …/issues`.
 */

/** The head branch of pull request #3, made by the recording. */
const val MERGED_BRANCH = "kp-proof-s3.9-rec-head"
/** The head of pull request #3, as GitHub shows it. */
const val MERGED_HEAD = "83a09916860abd43cc175ba39c2f8d9afe2b6d46"
/** The merge commit GitHub made of pull request #3. */
const val MERGE_COMMIT = "0c6b33afb43dd4addb3147ad73edf4c53087a17a"
/** The head branch of pull request #4, into the default branch, closed unmerged. */
const val CLOSED_BRANCH = "kp-proof-s3.9-rec-main"
/**
 * A head a pull request's branch moved to after a judgement observed the one before it: a sha in GitHub's shape that is
 * no commit of the toy's — the specs change a recording's `head.sha` to it.
 */
const val MOVED_HEAD = "5b0e4c1f9d3a7e2b8c6f0a4d1e9b7c3a5f2d8e60"

/** Pull request #3 as recorded while open, with [changes]. */
fun openPull(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("pulls-open.json", "0", *changes)

/** Pull request #3 as recorded once merged, with [changes]. */
fun mergedPull(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("pulls-merged.json", "0", *changes)

/** Pull request #4 as recorded once closed unmerged, with [changes]. */
fun closedPull(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("pulls-closed.json", "0", *changes)

/** Issue #6 as GitHub answered its filing, with [changes]. */
fun filedIssue(vararg changes: Pair<String, Any?>): String = recordedItem("issue-201.json", "", *changes).toString()

/**
 * `PUT [path]` answers as GitHub merges a pull request: on the condition that the `sha` its body names is the head
 * [head] gives at that moment — merged, `merge-200.json`; on any other sha, or none, `merge-409.json`, "Head branch was
 * modified" (GitHub's REST documentation for `sha`; both answers recorded from the toy, S3.9 T1). A merge asked on the
 * head GitHub lists now is merged by it; one asked on a head that has moved since is not.
 */
fun RecordedGitHub.mergesOnItsHead(path: String, head: () -> String) = onRequest("PUT", path) { request ->
    val sha = runCatching { (Json.parseToJsonElement(request.body) as? JsonObject)?.get("sha") as? JsonPrimitive }.getOrNull()?.takeIf { it.isString }?.content
    if (sha == head()) Answer(200, recorded("merge-200.json")) else Answer(409, recorded("merge-409.json"))
}

/** GitHub lists [pulls] as the pull requests of `Collite/kp-toy` whose head is [branch]. */
fun RecordedGitHub.pullsOf(branch: String, vararg pulls: JsonObject) =
    on("/repos/Collite/kp-toy/pulls", "head" to "Collite:$branch", "state" to "all", answer = Answer(200, RecordedGitHub.jsonList(*pulls)))
