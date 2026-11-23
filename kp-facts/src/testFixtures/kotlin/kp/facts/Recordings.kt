package kp.facts

import kotlinx.serialization.json.JsonObject
import kp.facts.RecordedGitHub.Companion.recordedItem

/** The toy's stage whose pull request was recorded (S3.2): `Collite/kp-toy`'s merged #1. */
const val TOY_STAGE = "TOY/P0/S0.1"
const val TOY_BRANCH = "feat/toy-kp-toy-p0-s0.1-skeleton"
/**
 * The head of `Collite/kp-toy`'s pull request #1, as GitHub shows it, and the two commits before it. Each recording under
 * `github/kp-toy/` is GitHub's answer as it gave it, but `compare.json`'s files, which have no `patch` — the toy's code,
 * which no fact reads (S3.2) — and hold both of the comparison's commits since S3.10 (Collite/keporkak#57).
 */
const val TOY_HEAD = "028e9e2dece9c94f65f3c0da144e6f2ce3c9f013"
const val ONE_BACK = "14024311a28d6027148612808100e30556e48dc4"
const val TWO_BACK = "5d1a7961f450b02339f8a464e544b3ef53f55d9f"
/** The token the specs hand `kp-facts`: it reads with it and shows it nowhere. */
const val TEST_TOKEN = "ghs_TestTokenTestTokenTestTokenTestToken"

/** Pull request #1 as recorded — merged — with [changes]. */
fun pull(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("pulls.json", "0", *changes)

/** A check run as recorded — `check`, completed, success — with [changes]. */
fun run(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("check-runs.json", "check_runs.0", *changes)

/** A review as recorded — the author's, commented on the head — with [changes]. */
fun review(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("reviews.json", "1", *changes)

/** The reviewer's App's review on [sha], [state], submitted [at]: the recorded review, by `kp-reviewer[bot]`. */
fun byTheReviewer(sha: String, state: String, at: String): JsonObject =
    review("user.login" to "kp-reviewer[bot]", "user.type" to "Bot", "commit_id" to sha, "state" to state, "submitted_at" to at)

/** A comment as recorded — the author's — with [changes]. */
fun comment(vararg changes: Pair<String, Any?>): JsonObject = recordedItem("comments.json", "0", *changes)

/** GitHub lists [pulls] as the pull requests of `Collite/kp-toy` whose head is the toy's branch. */
fun RecordedGitHub.toyPullRequests(vararg pulls: JsonObject) =
    on("/repos/Collite/kp-toy/pulls", "head" to "Collite:$TOY_BRANCH", "state" to "all", answer = Answer(200, RecordedGitHub.jsonList(*pulls)))

/** GitHub answers the check runs of [sha] in `Collite/kp-toy` with [pages], one after the other. */
fun RecordedGitHub.toyCheckRuns(sha: String, vararg pages: String) = onPages("/repos/Collite/kp-toy/commits/$sha/check-runs", *pages)

/** GitHub lists [reviews] as those of `Collite/kp-toy`'s pull request #1. */
fun RecordedGitHub.toyReviews(vararg reviews: JsonObject) = on("/repos/Collite/kp-toy/pulls/1/reviews", answer = Answer(200, RecordedGitHub.jsonList(*reviews)))

/** GitHub lists [comments] as those on `Collite/kp-toy`'s pull request #1. */
fun RecordedGitHub.toyComments(vararg comments: JsonObject) = on("/repos/Collite/kp-toy/issues/1/comments", answer = Answer(200, RecordedGitHub.jsonList(*comments)))

/** GitHub compares [base] with [head] in `Collite/kp-toy` with [answer]. */
fun RecordedGitHub.toyCompare(base: String, head: String, answer: Answer) = on("/repos/Collite/kp-toy/compare/$base...$head", answer = answer)
