package kp.daemon

import kp.ops.GitHubApps
import java.net.URI

/**
 * `just apps` (contracts.md §12, Amendment 33): asks GitHub, on the office, about each App of §9's block `github:` — the
 * name GitHub shows, the repositories its installation reaches — and, for each role of `seats:`, whether its permissions
 * can be minted. It prints no token, no key and no claim, and changes nothing on GitHub.
 */
object Apps {
    /** Asks GitHub at [api] about the Apps of [rules]' block for [office], printing each line through [print]; 0 when every answer is yes. */
    fun run(rules: InForceRules, office: String, api: URI = GitHubApps.GITHUB, print: (String) -> Unit): Int = 1
}
