package kp.daemon

import kp.daemon.seats.Roots
import kp.ops.App
import kp.ops.Asked
import kp.ops.GitHubApps
import kp.ops.Minted
import java.net.URI

/**
 * `just apps` (contracts.md §12, Amendment 33): asks GitHub, on the office, about each App of §9's block `github:` — the
 * name GitHub shows, and the repositories its installation reaches, read with a token narrowed to reading the
 * repositories' metadata — and, for each role of `seats:`, whether its permissions can be minted. It prints no token, no
 * key and no claim, changes nothing on GitHub, and spends none of the seats' tokens.
 */
object Apps {
    /** Asks GitHub at [api] about the Apps of [rules]' block for [office], printing each line through [print]; 0 when every answer is yes. */
    fun run(rules: InForceRules, office: String, api: URI = GitHubApps.GITHUB, print: (String) -> Unit): Int {
        val pool = rules.ruleSet.pool
        val block = pool.github ?: return 1.also { print("pool/offices.yaml has no block github:") }
        val entry = pool.offices[office] ?: return 1.also { print("pool/offices.yaml names no office '$office'") }
        val keys = Roots.of(entry.roots).secrets.resolve("apps")
        val apps = GitHubApps(keys, api)
        var failed = 0
        print("GitHub's Apps of ${block.organization}, as the office $office reaches them with the keys in $keys:")
        for ((identity, ids) in block.apps) {
            val app = App(identity, ids.app, ids.installation)
            val name = when (val asked = apps.name(app)) {
                is Asked.Ok -> asked.value
                is Asked.Failed -> "(not read: ${asked.reason})".also { failed++ }
            }
            val reached = when (val minted = apps.mint(app, null, GitHubApps.METADATA)) {
                is Minted.Ok -> when (val repositories = apps.repositories(minted.token)) {
                    is Asked.Ok -> repositories.value.joinToString(", ").ifEmpty { "no repository" }
                    is Asked.Failed -> "(not read: ${repositories.reason})".also { failed++ }
                }
                is Minted.Failed -> "(not read: ${minted.reason})".also { failed++ }
            }
            print("  $identity — GitHub shows $name — app ${ids.app}, installation ${ids.installation} — reaches $reached")
        }
        print("Each role's permissions (github.seats), minted for every repository its App reaches:")
        for ((role, seat) in block.seats) {
            val ids = block.apps[seat.app]
            val permissions = seat.permissions.toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}" }
            val answer = when (val minted = ids?.let { apps.mint(App(seat.app, it.app, it.installation), null, seat.permissions) }) {
                null -> "cannot be minted: github.apps holds no App ${seat.app}".also { failed++ }
                is Minted.Ok -> "can be minted"
                is Minted.Failed -> "cannot be minted: ${minted.reason}".also { failed++ }
            }
            print("  $role as ${seat.app} — $permissions — $answer")
        }
        print(if (failed == 0) "apps: every answer is yes" else "apps: answers that are no: $failed")
        return if (failed == 0) 0 else 1
    }
}
