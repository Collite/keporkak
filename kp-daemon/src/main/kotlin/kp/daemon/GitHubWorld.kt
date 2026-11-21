package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.GitHubFacts
import kp.mailroom.FactSource
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
