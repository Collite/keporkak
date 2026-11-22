package kp.daemon.seats

import kp.daemon.InForceRules
import kp.facts.Checkouts
import kp.facts.GitHub
import kp.facts.GitHubFacts
import kp.facts.Observation
import kp.mailroom.OperationResult
import kp.ops.App
import kp.ops.GitHubApps
import kp.ops.Minted
import kp.ops.Token
import kp.seatapi.Binding
import kp.seatapi.GitHubTokens
import kp.seatapi.TokenAnswer
import java.net.URI
import java.nio.file.Path

/**
 * A seat's GitHub identity (contracts.md §4.4, Amendment 35): a token minted when the seat asks, for the App §9's
 * `github.seats` gives its role, with that role's permissions, narrowed to the repository of its stage — the `<repo>` of
 * its list's title in [project] (Amendment 33) — or, for a seat with no stage, to the repositories of the office's
 * `repos:`. The block `github:` and the office's entry are those of the rules in force, read at each ask; the Apps' keys
 * are in [keys], the office's `<roots.secrets>/apps`; [api] is GitHub's REST root. Nothing is kept from one ask for the
 * next.
 *
 * An ask ([ask]) is answered with the token, which [hide] is told before it is handed over — the seat's transcript hides
 * it from then on — and leaves one journal line through [say]: the seat, the App, the repositories; never a token.
 */
class GitHubIdentity(
    private val office: String,
    private val project: Path,
    private val rules: () -> InForceRules?,
    private val keys: Path,
    private val api: URI = GitHubApps.GITHUB,
    private val say: (String) -> Unit,
    private val hide: (seat: String, secret: String) -> Unit,
) : GitHubTokens {
    override fun ask(binding: Binding): TokenAnswer {
        val who = "github-token: ${binding.seat} (${binding.role})"
        return when (val minted = minted(binding)) {
            is Mint.NoEntry -> TokenAnswer.Refused(minted.reason).also { say("$who refused: ${minted.reason}") }
            is Mint.Failed -> TokenAnswer.Failed(minted.reason).also { say("$who${minted.asked} failed: ${minted.reason}") }
            is Mint.Ok -> {
                val token = minted.token.authorization().removePrefix(BEARER)
                hide(binding.seat, token)
                say("$who as ${minted.app} for ${minted.repositories.joinToString(", ")}: handed, until ${minted.token.expiresAt}")
                TokenAnswer.Handed(token, minted.token.expiresAt)
            }
        }
    }

    fun mint(binding: Binding): OperationResult = OperationResult.Failed("not built yet")

    /** What one mint for a seat came to: no entry for its role; failed, with the reason; or a token, with what it was asked for. */
    private sealed interface Mint {
        data class NoEntry(val reason: String) : Mint

        /** [asked] names the App and the repositories when the mint got that far. */
        data class Failed(val reason: String, val asked: String = "") : Mint

        class Ok(val app: String, val repositories: List<String>, val permissions: Map<String, String>, val token: Token) : Mint
    }

    private fun minted(binding: Binding): Mint {
        val pool = rules()?.ruleSet?.pool ?: return Mint.Failed("the daemon has not read its rules yet")
        val block = pool.github ?: return Mint.NoEntry("pool/offices.yaml has no block github:")
        val seat = block.seats[binding.role] ?: return Mint.NoEntry("github.seats gives the role ${binding.role} no App")
        val ids = block.apps[seat.app] ?: return Mint.Failed("github.apps holds no App ${seat.app}", " as ${seat.app}")
        val entry = pool.offices[office] ?: return Mint.Failed("pool/offices.yaml names no office '$office'", " as ${seat.app}")
        val names = when (val stage = binding.stage) {
            null -> entry.repos.keys.sorted()
            else -> {
                val checkouts = Checkouts(office, project, entry.project.efforts, entry.repos.toSortedMap().mapValues { (_, checkout) -> Seats.home(checkout) })
                when (val repository = GitHubFacts.repository(checkouts, GitHub(block.organization, api), stage)) {
                    is Observation.Value -> listOf((repository.value as String).substringAfter('/'))
                    is Observation.Unknown -> return Mint.Failed("the repository of $stage is unknown: ${repository.missing}", " as ${seat.app}")
                }
            }
        }
        val repositories = names.map { "${block.organization}/$it" }
        return when (val minted = GitHubApps(keys, api).mint(App(seat.app, ids.app, ids.installation), names, seat.permissions)) {
            is Minted.Ok -> Mint.Ok(seat.app, repositories, seat.permissions, minted.token)
            is Minted.Failed -> Mint.Failed(minted.reason, " as ${seat.app} for ${repositories.joinToString(", ")}")
        }
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}
