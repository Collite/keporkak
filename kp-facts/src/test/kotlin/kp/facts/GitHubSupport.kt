package kp.facts

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The toy's stage `TOY/P0/S0.1` on an office's checkouts ([Office]) — its list's first line as the toy's, naming the
 * repository `kp-toy` and the branch of pull request #1 — in front of a local GitHub that answers with what was recorded
 * from `Collite/kp-toy` ([RecordedGitHub]). `kp-facts` is handed [token] for the repository it asks for, and [reviewer]
 * as the name GitHub shows for the reviewer's App.
 */
class Toy(folder: File) : AutoCloseable {
    val office = Office(folder)
    val log: KnownLog get() = office.log
    val github = RecordedGitHub().start()
    /** The repositories a token was asked for, in order. */
    val asked = mutableListOf<String>()
    var token: Handed = Handed.Over("Bearer $TEST_TOKEN")
    var reviewer: Handed = Handed.Over("kp-reviewer")

    init {
        office.project.write("toy/tasks-p0-s0.1-skeleton.md", "# TOY-kp-toy-P0 · S0.1 — The skeleton            (PR: $TOY_BRANCH)\nrolls: none · live: none · touches: none\n")
    }

    fun facts(): GitHubFacts = GitHubFacts(office.checkouts, office.log, GitHub("Collite", github.api), object : GitHubAccess {
        override fun token(repository: String): Handed = token.also { asked += repository }
        override fun reviewer(): Handed = reviewer
    })

    /** [fact] for the toy's stage, with [params] beside. */
    fun observe(fact: String, vararg params: Pair<String, Any>): Observation = facts().observe(fact, mapOf("stage" to TOY_STAGE) + params)

    fun pullRequests(vararg pulls: JsonObject) = github.toyPullRequests(*pulls)

    fun checkRuns(sha: String, vararg pages: String) = github.toyCheckRuns(sha, *pages)

    fun reviews(vararg reviews: JsonObject) = github.toyReviews(*reviews)

    fun comments(vararg comments: JsonObject) = github.toyComments(*comments)

    fun compare(base: String, head: String, answer: Answer) = github.toyCompare(base, head, answer)

    override fun close() = github.close()
}
