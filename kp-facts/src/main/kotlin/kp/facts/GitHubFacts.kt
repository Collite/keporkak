package kp.facts

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * GitHub as `kp-facts` reads it (contracts.md §9, the block `github:`; §4.4, Amendment 33): the [organization] a stage's
 * repository is in, and the root of GitHub's REST [api].
 */
data class GitHub(val organization: String, val api: URI = URI.create("https://api.github.com/"))

/**
 * What `kp-facts` is handed to read GitHub with (Amendment 33): `kp-ops` mints, `kp-facts` is handed a token and reads.
 * The daemon asks for each observation afresh: nothing is kept from one judgement for the next.
 */
interface GitHubAccess {
    /** The `Authorization` value of a token narrowed to [repository] — `<organization>/<repo>` — and to reading; or why there is none. */
    fun token(repository: String): Handed

    /** The name GitHub shows for the App that `github.seats` gives the reviewer's role (§9); or why it is not known. */
    fun reviewer(): Handed
}

/** What [GitHubAccess] hands over: a text, or why there is none. */
sealed interface Handed {
    /** The text handed over. It is not shown: [toString] hides it, as a token's must be. */
    class Over(val text: String) : Handed {
        override fun toString(): String = "Handed.Over(…)"
    }

    data class Refused(val reason: String) : Handed
}

/**
 * The observers of GitHub (contracts.md §4.4, Amendment 33): a stage's pull request — the one whose head is the branch its
 * list names, in the repository its list's title names — and the check runs of a sha. They read with the token they are
 * handed and write nothing; a list GitHub gives in pages is read to its end; a request that fails makes the fact unknown,
 * with the reason, and nothing is tried again or kept. Amendment 32's reading of the list, of a parameter left out and of
 * unknown holds here too.
 */
class GitHubFacts(
    private val where: Checkouts,
    private val log: WhatTheLogKnows,
    private val github: GitHub,
    private val access: GitHubAccess,
    private val http: HttpClient = client(),
) {
    /**
     * `kp-facts`' entry for GitHub's facts: [fact] with its [params] — each a text, or a list of texts — answered with a
     * value and its evidence, or unknown with the reason. Nothing is thrown for what GitHub or the world lacks.
     */
    fun observe(fact: String, params: Map<String, Any> = emptyMap()): Observation {
        if (fact !in ANSWERED) return Observation.Unknown("kp-facts does not read '$fact' from GitHub")
        return Observation.Unknown("kp-facts reads no fact from GitHub yet")
    }

    companion object {
        /** The facts answered from GitHub, as Amendment 33 reads them; the parameters are those of `rules/vocabulary.yaml`, and the stage `ci.state`'s sha is in. */
        val ANSWERED: Map<String, Fact> = listOf(
            Fact("pr.ready", listOf("stage")),
            Fact("pr.closed", listOf("stage")),
            Fact("pr.merged", listOf("stage")),
            Fact("pr.head", listOf("stage")),
            Fact("pr.commits_since", listOf("stage", "message"), readsTheLog = true),
            Fact("pr.review_on", listOf("stage", "sha")),
            Fact("pr.blocker_responses", listOf("stage", "ids"), readsTheLog = true),
            Fact("pr.evidence_present", listOf("stage")),
            Fact("ci.state", listOf("sha", "stage")),
        ).associateBy { it.name }

        /** How long one request may take: a judgement that reads GitHub holds the mailroom's lock meanwhile (Amendment 33). */
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        fun client(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    }
}
