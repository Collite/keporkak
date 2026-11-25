package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kp.engine.Datum
import kp.facts.Answer
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_BRANCH
import kp.facts.TOY_STAGE
import kp.facts.pull
import kp.facts.toyPullRequests
import kp.ops.App
import kp.ops.GitHubApps
import kp.ops.Performed
import kp.ops.PrClose
import kp.ops.StageOnGitHub

/**
 * KP/P3/S3.11 T3 (S3.9's Found line; contracts.md §4.4, Amendment 33): `kp-facts` and `kp-ops` may not depend on each
 * other (architecture.md §2), so each chooses a stage's pull request by its own copy of Amendment 33's rule — the open one,
 * else the one made last; two open are no answer. This spec hands both the same recorded answers of GitHub's and holds
 * that they choose alike: `kp-facts` through `pr.head` as the daemon reads it, `kp-ops` through `pr.close`, which names
 * the pull request it chose — or why it chose none.
 */
class ThePullRequestChosenSpec : FreeSpec({
    mapOf(
        "none" to listOf(),
        "one open, beside a closed one" to listOf(pull("number" to 3, "state" to "closed", "created_at" to "2026-11-20T09:00:00Z"), open(5, "2026-11-21T09:00:00Z")),
        "two open" to listOf(open(5, "2026-11-21T09:00:00Z"), open(6, "2026-11-22T09:00:00Z")),
        "only closed ones" to listOf(pull("number" to 3, "state" to "closed", "created_at" to "2026-11-20T09:00:00Z"),
            pull("number" to 4, "state" to "closed", "created_at" to "2026-11-21T09:00:00Z")),
    ).forEach { (case, pulls) ->
        "kp-facts and kp-ops choose the same pull request on the same answers: $case" {
            GitHubOffice().use { office ->
                office.github.toyPullRequests(*pulls.toTypedArray())
                listOf(3, 4, 5, 6).forEach { office.github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/$it", Answer(200, recorded("pull-closed.json"))) }

                val facts = office.world.github.observation(kp.engine.FactRef("pr.head", emptyMap<String, Datum>()), TOY_STAGE)!!.evidence
                val ops = PrClose(GitHubApps(office.secrets.resolve("apps"), office.github.api), App("kp-merger", 5849229, 184045472), office.github.api)
                    .perform(StageOnGitHub(TOY_STAGE, "Collite/kp-toy", TOY_BRANCH))

                chosen(facts) shouldBe chosen(ops)
            }
        }
    }
})

/** An open pull request of the toy's branch, numbered [number], made [at]. */
private fun open(number: Int, at: String): JsonObject = pull("number" to number, "state" to "open", "closed_at" to null, "merged_at" to null, "created_at" to at)

/**
 * The pull request a chooser chose, as its words name it — `Collite/kp-toy#n` — or what it said when it chose none: that
 * there is none, or that two are open. [said] is `kp-facts`' evidence or reason, or `kp-ops`' result.
 */
private fun chosen(said: Any): String {
    val text = when (said) {
        is Performed.Ok -> said.result.values.single().toString()
        is Performed.Failed -> said.reason
        else -> said.toString()
    }
    return Regex("holds [0-9]+ open pull requests whose head is \\S+: #[0-9]+(, #[0-9]+)*").find(text)?.value
        ?: Regex("holds no pull request whose head is \\S+").find(text)?.value
        ?: Regex("Collite/kp-toy#[0-9]+").find(text)?.value
        ?: "nothing chosen: $text"
}
