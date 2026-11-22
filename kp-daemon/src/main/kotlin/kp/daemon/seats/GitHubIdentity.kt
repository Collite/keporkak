package kp.daemon.seats

import kp.daemon.InForceRules
import kp.mailroom.OperationResult
import kp.ops.GitHubApps
import kp.seatapi.Binding
import kp.seatapi.GitHubTokens
import kp.seatapi.TokenAnswer
import java.net.URI
import java.nio.file.Path

/** A seat's GitHub identity (contracts.md §4.4, Amendment 35) — S3.7 T1: not built yet. */
class GitHubIdentity(
    private val office: String,
    private val project: Path,
    private val rules: () -> InForceRules?,
    private val keys: Path,
    private val api: URI = GitHubApps.GITHUB,
    private val say: (String) -> Unit,
    private val hide: (seat: String, secret: String) -> Unit,
) : GitHubTokens {
    override fun ask(binding: Binding): TokenAnswer = TokenAnswer.Failed("not built yet")

    fun mint(binding: Binding): OperationResult = OperationResult.Failed("not built yet")
}
