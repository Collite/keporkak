package kp.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kp.daemon.seats.Roots
import kp.ops.App
import kp.ops.GitHubApps
import kp.ops.GitHubRest
import kp.ops.GitMerge
import kp.ops.Issue
import kp.ops.IssueFile
import kp.ops.Minted
import kp.ops.Performed
import kp.ops.PrClose
import kp.ops.StageOnGitHub
import kp.ops.Token
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * `just apps merge` (contracts.md §12, Amendment 37): the live proof of the operations on GitHub, on the toy repository,
 * with no agent. It reads which rules hold the default branch, and whom; then, on a pull request into the default branch,
 * it shows what must be refused — a merge with the coder's App; a merge as `kp-merger` on a head other than the one named;
 * and, where the repository's rules hold `kp-merger` to them, a merge before the checks are green — and closes that pull
 * request unmerged; on a pull request between two branches it makes itself it shows a conflict, then a merge with its
 * sha; it files an issue and closes it; it deletes its branches. Each step is performed by the daemon's own performers
 * (`kp-ops`) where one exists. The default branch is never moved by it: a merge into it that GitHub lets through is
 * `stop SC-10`, and the proof stops there. It prints each step's outcome and never a token, a key or a claim.
 */
object AppsMerge {
    /** The toy repository, in the organization of the block `github:`. */
    const val TOY = "kp-toy"

    /** Runs the proof on [rules]' office [office] against GitHub at [api], printing through [print]; 0 when every step is as §12 gives it. */
    fun run(rules: InForceRules, office: String, api: URI = GitHubApps.GITHUB, clock: () -> Instant = Instant::now, print: (String) -> Unit): Int {
        val pool = rules.ruleSet.pool
        val block = pool.github ?: return 1.also { print("pool/offices.yaml has no block github:") }
        val entry = pool.offices[office] ?: return 1.also { print("pool/offices.yaml names no office '$office'") }
        val keys = Roots.of(entry.roots).secrets.resolve("apps")
        val coderSeat = block.seats[CODER] ?: return 1.also { print("github.seats gives the role $CODER no App") }
        fun app(identity: String) = block.apps[identity]?.let { App(identity, it.app, it.installation) }
        val merger = app(MERGER) ?: return 1.also { print("github.apps holds no App $MERGER") }
        val coder = app(coderSeat.app) ?: return 1.also { print("github.apps holds no App ${coderSeat.app}") }
        val proof = Proof(GitHubApps(keys, api), GitHubRest(api), "${block.organization}/$TOY", merger, coder, coderSeat.permissions, api, print)
        print("just apps merge on ${proof.repository}, as the office $office reaches it with the keys in $keys (run ${RUN.format(clock())}):")
        return try {
            proof.run("kp-apps-merge-${RUN.format(clock())}")
        } catch (stopped: Stopped) {
            print("  stopped: ${stopped.message}")
            1
        }
    }

    private class Stopped(message: String) : Exception(message, null, false, false)

    private class Proof(
        val apps: GitHubApps,
        val rest: GitHubRest,
        val repository: String,
        val merger: App,
        val coder: App,
        val coderPermissions: Map<String, String>,
        val api: URI,
        val print: (String) -> Unit,
    ) {
        private var notAsGiven = 0
        private val made = ArrayList<String>()
        private val pulls = ArrayList<Int>()
        private var issue: Int? = null

        fun run(prefix: String): Int {
            val reading = mint(merger, GitHubApps.METADATA)
            val pushing = mint(coder, coderPermissions)
            val default = text(ask("GET", "/repos/$repository", reading), "default_branch")
            val before = sha(pushing, default)
            print("  the default branch $default is at $before")
            val (coderHeld, mergerHeld) = rules(reading, pushing, default)
            if (!coderHeld) {
                print("SC-10: no rule of $default holds ${coder.identity} to `update` — its merge would not be refused; nothing was made")
                return 1
            }
            try {
                prove(prefix, default, pushing, mergerHeld)
            } finally {
                tidy(pushing, default, before)
            }
            print(if (notAsGiven == 0) "apps merge: every step as §12 gives it" else "apps merge: steps not as §12 gives them: $notAsGiven")
            return if (notAsGiven == 0) 0 else 1
        }

        /** Which rules hold [default], and whom: whether the coder's App is held to `update`, and `kp-merger` to green checks. */
        private fun rules(reading: Token, pushing: Token, default: String): Pair<Boolean, Boolean> {
            val rules = ask("GET", "/repos/$repository/rules/branches/$default", reading).json as? JsonArray ?: throw Stopped("GitHub's rules of $default are no list")
            val byRuleset = rules.mapNotNull { it as? JsonObject }.groupBy({ (it["ruleset_id"] as? JsonPrimitive)?.longOrNull }, { (it["type"] as? JsonPrimitive)?.content })
            var coderHeld = false
            var mergerHeld = false
            for ((id, types) in byRuleset) {
                val asMerger = ask("GET", "/repos/$repository/rulesets/$id", reading).json as? JsonObject
                val asCoder = ask("GET", "/repos/$repository/rulesets/$id", pushing).json as? JsonObject
                val name = (asMerger?.get("name") as? JsonPrimitive)?.content
                val mergerBypass = (asMerger?.get(BYPASS) as? JsonPrimitive)?.content
                val coderBypass = (asCoder?.get(BYPASS) as? JsonPrimitive)?.content
                print("  ruleset $id '$name' holds $default to ${types.filterNotNull().joinToString(", ")} — ${merger.identity} may bypass it: $mergerBypass; ${coder.identity}: $coderBypass")
                if ("update" in types && coderBypass == "never") coderHeld = true
                if ("required_status_checks" in types && mergerBypass == "never") mergerHeld = true
            }
            print("  the repository's rules hold ${merger.identity} to green checks: ${if (mergerHeld) "yes — a second ruleset; its step is run" else "no — no second ruleset; its step is not run"}")
            return coderHeld to mergerHeld
        }

        private fun prove(prefix: String, default: String, pushing: Token, mergerHeld: Boolean) {
            val base = branch(pushing, "$prefix-base", default)
            val head = branch(pushing, "$prefix-head", default)
            val main = branch(pushing, "$prefix-main", default)
            commit(pushing, base, "kp-apps-merge/merged.txt", "as the base has it\n")
            val conflicting = commit(pushing, head, "kp-apps-merge/merged.txt", "as the head first had it\n")
            val stale = commit(pushing, main, "kp-apps-merge/never.txt", "one\n")
            val named = commit(pushing, main, "kp-apps-merge/never.txt", "two\n")
            val into = pull(pushing, main, default, "just apps merge: into the default branch — never merged")
            val between = pull(pushing, head, base, "just apps merge: between two branches of its own")
            print("  made: the branches $base, $head, $main; the pull requests #$into ($main into $default) and #$between ($head into $base)")

            val asCoder = GitMerge(apps, coder, api).perform(at(main), "just-apps-merge", named)
            says("the coder's merge of #$into is refused", (asCoder as? Performed.Failed)?.reason?.startsWith("${GitMerge.REFUSED}: GitHub answered") == true, asCoder)
            if (asCoder is Performed.Ok) throw Stopped("SC-10: GitHub merged #$into into $default with ${coder.identity}'s token")
            val elsewhere = GitMerge(apps, merger, api).perform(at(main), "just-apps-merge", stale)
            says("a merge of #$into as ${merger.identity} on another head than its own is refused", (elsewhere as? Performed.Failed)?.reason?.startsWith("${GitMerge.STALE_CI}:") == true, elsewhere)
            if (elsewhere is Performed.Ok) throw Stopped("SC-10: GitHub merged #$into into $default on a head other than the one named")
            if (mergerHeld) beforeGreen(pushing, main, into, named)
            val closed = PrClose(apps, merger, api).perform(at(main))
            says("#$into is closed unmerged", closed == Performed.Ok(mapOf("closed" to "$repository#$into")), closed)

            mergeable(pushing, between, false)
            val conflict = GitMerge(apps, merger, api).perform(at(head), "just-apps-merge", conflicting)
            says("a merge of #$between, whose head conflicts with its base, is a conflict", (conflict as? Performed.Failed)?.reason?.startsWith("${GitMerge.CONFLICT}:") == true, conflict)
            val resolved = commit(pushing, head, "kp-apps-merge/merged.txt", "as the base has it\n")
            mergeable(pushing, between, true)
            val merged = GitMerge(apps, merger, api).perform(at(head), "just-apps-merge", resolved)
            says("#$between, its head now as its base has it, is merged with a merge commit", merged is Performed.Ok, merged)

            val filed = IssueFile(apps, merger, api).perform(at(head), "just-apps-merge",
                listOf(Issue("just apps merge: one issue, filed and closed", "Filed by `just apps merge` (KP/P3/S3.9), and closed by the same run.")))
            issue = ((filed as? Performed.Ok)?.result?.get("numbers") as? List<*>)?.singleOrNull() as? Int
            says("an issue is filed", issue != null, filed)
        }

        /** Where the repository's rules hold `kp-merger` to green checks: a merge of [into] before the checks of [head] are green is refused. */
        private fun beforeGreen(pushing: Token, main: String, into: Int, head: String) {
            val runs = (ask("GET", "/repos/$repository/commits/$head/check-runs", pushing).json as? JsonObject)?.get("check_runs") as? JsonArray ?: JsonArray(emptyList())
            val green = runs.isNotEmpty() && runs.all { ((it as? JsonObject)?.get("conclusion") as? JsonPrimitive)?.content in setOf("success", "neutral", "skipped") }
            if (green) return says("a merge of #$into before its checks are green is refused", false, "not run: the checks of ${head.take(SHORT)} are green already")
            val early = GitMerge(apps, merger, api).perform(at(main), "just-apps-merge", head)
            says("a merge of #$into before its checks are green is refused", early is Performed.Failed, early)
            if (early is Performed.Ok) throw Stopped("SC-10: GitHub merged #$into into its default branch before its checks were green")
        }

        /** Leaves the toy as it was: the issue closed, the pull requests closed, the branches deleted — and says so, with the default branch's sha. */
        private fun tidy(pushing: Token, default: String, before: String) {
            issue?.let { number ->
                val closing = mint(merger, mapOf("issues" to "write"))
                val reply = ask("PATCH", "/repos/$repository/issues/$number", closing, buildJsonObject { put("state", "closed"); put("state_reason", "not_planned") })
                says("issue #$number is closed", reply.status == GitHubRest.OK && text(reply, "state") == "closed", "GitHub answered ${reply.status}")
            }
            for (number in pulls) {
                val state = text(ask("GET", "/repos/$repository/pulls/$number", pushing), "state")
                if (state == "open") ask("PATCH", "/repos/$repository/pulls/$number", pushing, buildJsonObject { put("state", "closed") })
            }
            for (branch in made) {
                val reply = ask("DELETE", "/repos/$repository/git/refs/heads/$branch", pushing)
                says("the branch $branch is deleted", reply.status == NO_CONTENT, "GitHub answered ${reply.status}")
            }
            val after = sha(pushing, default)
            says("the default branch $default is where it was", after == before, "at $after")
            val open = pulls.filter { text(ask("GET", "/repos/$repository/pulls/$it", pushing), "state") != "closed" }
            says("no pull request the proof made is open", open.isEmpty(), if (open.isEmpty()) "#${pulls.joinToString(", #")} closed" else "open: #${open.joinToString(", #")}")
        }

        private fun says(what: String, asGiven: Boolean, outcome: Any) {
            if (!asGiven) notAsGiven++
            val said = when (outcome) {
                is Performed.Ok -> "ok ${outcome.result}"
                is Performed.Failed -> "failed: ${outcome.reason}"
                else -> outcome.toString()
            }
            print("  ${if (asGiven) "yes" else "NO "}  $what — $said")
        }

        private fun at(branch: String) = StageOnGitHub("just apps merge", repository, branch)

        private fun mint(app: App, permissions: Map<String, String>): Token = when (val minted = apps.mint(app, listOf(repository.substringAfter('/')), permissions)) {
            is Minted.Ok -> minted.token
            is Minted.Failed -> throw Stopped("no token for ${app.identity}: ${minted.reason}")
        }

        private fun ask(method: String, path: String, token: Token, body: JsonObject? = null): GitHubRest.Reply = when (val sent = rest.send(method, path, token, body)) {
            is GitHubRest.Sent.Answered -> sent.reply
            is GitHubRest.Sent.NotReached -> throw Stopped(sent.reason)
        }

        /** [reply]'s field [name], or the proof stops with GitHub's answer. */
        private fun text(reply: GitHubRest.Reply, name: String): String =
            ((reply.json as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Stopped("GitHub's answer has no $name: ${reply.status} ${reply.message.orEmpty()}")

        private fun sha(token: Token, branch: String): String {
            val reply = ask("GET", "/repos/$repository/git/ref/heads/$branch", token)
            return ((reply.json as? JsonObject)?.get("object") as? JsonObject)?.let { (it["sha"] as? JsonPrimitive)?.content } ?: throw Stopped("GitHub shows no sha of $branch: ${reply.status}")
        }

        private fun branch(token: Token, name: String, from: String): String {
            val reply = ask("POST", "/repos/$repository/git/refs", token, buildJsonObject { put("ref", "refs/heads/$name"); put("sha", sha(token, from)) })
            if (reply.status != GitHubRest.CREATED) throw Stopped(rest.refused("POST", "/repos/$repository/git/refs", reply))
            made += name
            return name
        }

        /** Writes [text] to [path] on [branch] in one commit; its sha. */
        private fun commit(token: Token, branch: String, path: String, text: String): String {
            val at = "/repos/$repository/contents/$path"
            val existing = ask("GET", "$at?ref=$branch", token).let { reply -> if (reply.status == GitHubRest.OK) text(reply, "sha") else null }
            val reply = ask("PUT", at, token, buildJsonObject {
                put("message", "just apps merge: $path on $branch")
                put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
                put("branch", branch)
                existing?.let { put("sha", it) }
            })
            if (reply.status !in setOf(GitHubRest.OK, GitHubRest.CREATED)) throw Stopped(rest.refused("PUT", at, reply))
            return ((reply.json as? JsonObject)?.get("commit") as? JsonObject)?.let { (it["sha"] as? JsonPrimitive)?.content } ?: throw Stopped("GitHub's answer to PUT $at names no commit")
        }

        private fun pull(token: Token, head: String, base: String, title: String): Int {
            val reply = ask("POST", "/repos/$repository/pulls", token, buildJsonObject {
                put("title", title)
                put("head", head)
                put("base", base)
                put("body", "Made by `just apps merge` (KP/P3/S3.9): closed or merged, and its branches deleted, by the same run.")
            })
            val number = ((reply.json as? JsonObject)?.get("number") as? JsonPrimitive)?.intOrNull ?: throw Stopped(rest.refused("POST", "/repos/$repository/pulls", reply))
            pulls += number
            return number
        }

        /** Waits, at most a minute, until GitHub says whether [number] can be merged, and that it is [expected]. */
        private fun mergeable(token: Token, number: Int, expected: Boolean) {
            repeat(MERGEABLE_ASKS) {
                val said = ((ask("GET", "/repos/$repository/pulls/$number", token).json as? JsonObject)?.get("mergeable") as? JsonPrimitive)?.booleanOrNull
                if (said == expected) return
                Thread.sleep(MERGEABLE_EVERY)
            }
            throw Stopped("GitHub did not say within a minute that #$number is ${if (expected) "mergeable" else "not mergeable"}")
        }
    }

    private const val MERGER = "kp-merger"
    private const val CODER = "coder"
    private const val BYPASS = "current_user_can_bypass"
    private const val NO_CONTENT = 204
    private const val SHORT = 7
    private const val MERGEABLE_ASKS = 30
    private const val MERGEABLE_EVERY = 2000L
    private val RUN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
}
