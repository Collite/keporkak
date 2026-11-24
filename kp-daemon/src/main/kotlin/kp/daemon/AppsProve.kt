@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon

import kp.daemon.seats.GitHubIdentity
import kp.daemon.seats.Roots
import kp.daemon.seats.SeatEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kp.driver.Outcome
import kp.driver.home.ConfigHomes
import kp.driver.home.PermissionDefaults
import kp.driver.home.Places
import kp.log.LogWriter
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import kp.model.SeatId
import kp.ops.App
import kp.ops.Asked
import kp.ops.GitHubApps
import kp.seatapi.Binding
import kp.seatapi.Tokens
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * `just apps prove` (contracts.md §12, Amendment 35): the live proof of a seat's GitHub identity, on the toy repository
 * and with no agent. A listener of the daemon's own — the seat API in front of a mailroom on a throwaway project, whose
 * one list names the toy — answers the asks of two homes the builder makes, one for the coder's persona and one for the
 * reviewer's, and their `git` and `gh` run as processes in each seat's environment. From the coder's home it pushes a
 * branch, is refused a push to the default branch, and opens a pull request; from the reviewer's it reviews that pull
 * request; the coder's approval of its own is refused; then it closes the pull request and deletes the branch — also
 * when a step before went otherwise. Before them, the office's `gh` run by its full path in the coder's environment finds
 * no login (Amendment 43). A step GitHub must refuse passes only on GitHub's own refusal, in GitHub's words; failing for
 * another reason it says so and the proof is not green. It prints each step's outcome, and never a token: every
 * installation token is hidden in whatever it prints by its prefix, as a seat's transcript hides it. What GitHub lets through that it must refuse is `SC-10` (S3.7): the proof says so and goes on to
 * remove what it made. Its throwaway folder, under the checkout's `build/apps-prove/`, is removed at its end.
 */
object AppsProve {
    /** Proves on [office], with [rules] and the keporkak checkout [root]; prints through [print]; 0 when every step is as §12 gives it. */
    fun run(root: Path, rules: InForceRules, office: String, api: URI = GitHubApps.GITHUB, print: (String) -> Unit): Int {
        val pool = rules.ruleSet.pool
        val block = pool.github ?: return 1.also { print("pool/offices.yaml has no block github:") }
        val entry = pool.offices[office] ?: return 1.also { print("pool/offices.yaml names no office '$office'") }
        val effort = entry.project.efforts[EFFORT] ?: return 1.also { print("pool/offices.yaml gives office '$office' no folder for the effort $EFFORT") }
        val keys = Roots.of(entry.roots).secrets.resolve("apps")
        val coderApp = block.seats[CODER]?.app ?: return 1.also { print("github.seats gives the role $CODER no App") }
        val reviewerApp = block.seats[REVIEWER]?.app ?: return 1.also { print("github.seats gives the role $REVIEWER no App") }
        val apps = GitHubApps(keys, api)
        fun shown(identity: String): String? = block.apps[identity]?.let { (apps.name(App(identity, it.app, it.installation)) as? Asked.Ok)?.value }
        val coderName = shown(coderApp) ?: return 1.also { print("the name GitHub shows for $coderApp cannot be read") }
        val reviewerName = shown(reviewerApp) ?: return 1.also { print("the name GitHub shows for $reviewerApp cannot be read") }

        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val branch = "kp-apps-prove-$time"
        val folder = Files.createTempDirectory(root.resolve("build/apps-prove").createDirectories(), "$time-")
        val project = folder.resolve("project").createDirectories()
        val list = project.resolve("$effort/tasks-p0-s0.1-apps-prove.md").also { it.parent.createDirectories() }
        list.writeText("# $EFFORT-$TOY-P0 · S0.1 — just apps prove            (PR: $branch)\n")
        fun say(text: String) = print(INSTALLATION_TOKEN.replace(text, HIDDEN))
        val tokens = Tokens()
        placed(project, office, branch, folder.resolve("work/$TOY"))
        val mailroom = Office.start(office, project, root, FactSource { _, _ -> null }, Operations { _, done -> done(OperationResult.Failed("apps prove performs nothing")) },
            NoDeliveries, serviceClock)
        val identity = GitHubIdentity(office, { mailroom.rules }, OfficeLog().also { it.office = mailroom }, keys, api, say = {})
        val listener = mailroom.seatApi(tokens, "127.0.0.1:0", identity)
        try {
            val url = "http://127.0.0.1:${listener.address.port}"
            val work = folder.resolve("work").createDirectories()
            val defaults = (PermissionDefaults.of(rules.ruleSet.engagement.permissions) as? Outcome.Ok)?.value ?: return 1.also { say("engagement.yaml:permissions cannot be read") }
            fun seat(name: String, role: String, app: String): Seat? {
                val places = Places(work, folder.resolve("scratch/$name").createDirectories(), project, list, listOf(work), "127.0.0.1:${listener.address.port}")
                val home = when (val built = ConfigHomes(root, folder.resolve("seats")).build(SeatId(name), role, PROFILE, places, defaults)) {
                    is Outcome.Ok -> built.value
                    is Outcome.Failed -> return null.also { say("the home of $name cannot be built: ${built.reason}") }
                }
                val session = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                tokens.register(session, Binding(name, role, office, STAGE, "none", "none"))
                val environment = SeatEnvironment.of(BASE, home.path, app) + mapOf("KP_HOOK_URL" to url, "KP_SESSION_TOKEN" to session)
                return Seat(name, environment)
            }
            val coder = seat("coder-apps-prove", CODER, coderApp) ?: return 1
            val reviewer = seat("reviewer-apps-prove", REVIEWER, reviewerApp) ?: return 1
            return Proof(block.organization, branch, time, work, coderName, reviewerName, coder, reviewer, ::say).run()
        } finally {
            listener.close()
            mailroom.close()
            folder.deleteRecursively()
        }
    }

    /**
     * The proof's stage has its place as a stage's worktree gives it (contracts.md §4.4, Amendment 40): its throwaway log
     * starts with a `worktree.create` of [STAGE] that ended `ok`, naming the toy and [branch] — what the seats' tokens are
     * narrowed by. [worktree] is the coder's clone.
     */
    private fun placed(project: Path, office: String, branch: String, worktree: Path) {
        val at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        LogWriter.open(project, OfficeId(office)).use { log ->
            val judgement = log.judgement()
            listOf(
                """{"v":1,"at":"$at","event":"op","op":"worktree.create","phase":"requested","cause":"apps-prove","with":{"stage":"$STAGE"}}""",
                """{"v":1,"at":"$at","event":"op","op":"worktree.create","phase":"ok","cause":"apps-prove","result":{"repository":"$TOY","branch":"$branch","worktree":"$worktree"}}""",
            ).forEach { judgement.add(Json.parseToJsonElement(it).jsonObject, StageId(STAGE)) }
            judgement.close(at)
        }
    }

    /** A seat of the proof: its name, and its environment — its home's programs first on its path, its session token. */
    private class Seat(val name: String, val environment: Map<String, String>)

    /** What a program did: its exit code, its standard output, its standard error. */
    private class Ran(val exit: Int, val out: String, val err: String) {
        /** What it said, on one line: GitHub's reason, or git's, as it gave it. */
        fun said(): String = (err.lines() + out.lines()).map { it.removePrefix("remote: ").trim() }.filter { it.isNotEmpty() && !it.startsWith("To ") }
            .joinToString(" · ").take(SAID)
    }

    private class Proof(
        organization: String,
        private val branch: String,
        private val time: String,
        private val work: Path,
        private val coderName: String,
        private val reviewerName: String,
        private val coder: Seat,
        private val reviewer: Seat,
        private val say: (String) -> Unit,
    ) {
        private val repository = "$organization/$TOY"
        private val clone = work.resolve(TOY)
        private var otherwise = 0
        private var pushed = false
        private var number: String? = null

        fun run(): Int {
            say("just apps prove on $repository, as $coderName (the coder) and $reviewerName (the reviewer); branch $branch")
            try {
                prove()
            } finally {
                remove()
            }
            say(if (otherwise == 0) "apps prove: every step as contracts.md §12 gives it" else "apps prove: steps that went otherwise: $otherwise")
            return if (otherwise == 0) 0 else 1
        }

        private fun prove() {
            // The office's own gh, by its full path — the first on the agent's path — finds no login of the office user's.
            val officeGh = BASE.getValue("PATH").split(':').map { Path.of(it, "gh") }.firstOrNull { Files.isExecutable(it) }
            val status = officeGh?.let { coder.run(work, it.toString(), "auth", "status") }
            outcome("0. the office's gh, by its full path ${officeGh ?: "(none on the path)"}, finds no login", status != null && noLogin(status.exit, status.out + status.err),
                "no login: ${status?.said()}", "SC-10: it found a login, or did not run: ${status?.said()}")
            val cloned = coder.run(work, "git", "clone", "--quiet", "--single-branch", "--branch", DEFAULT_BRANCH, "git@github.com:$repository.git", TOY)
            val remote = if (cloned.exit == 0) coder.run(clone, "git", "ls-remote", "--get-url", "origin").out.trim() else ""
            if (!outcome("the coder clones git@github.com:$repository.git", cloned.exit == 0, "cloned over $remote", "not cloned: ${cloned.said()}")) return
            Files.writeString(clone.resolve("apps-prove.md"), "Pushed by `just apps prove` at $time (contracts.md §12); its branch is deleted by the proof.\n")
            coder.run(clone, "git", "switch", "--quiet", "--create", branch)
            coder.run(clone, "git", "add", "apps-prove.md")
            val committed = coder.run(clone, "git", "commit", "--quiet", "--message", "just apps prove $time")
            if (!outcome("the coder commits on $branch", committed.exit == 0, "committed", "not committed: ${committed.said()}")) return

            val push = coder.run(clone, "git", "push", "--quiet", "origin", branch)
            pushed = push.exit == 0
            if (!outcome("1. the coder pushes $branch", pushed, "pushed", "not pushed: ${push.said()}")) return

            val master = coder.run(clone, "git", "push", "--quiet", "origin", "HEAD:refs/heads/$DEFAULT_BRANCH")
            outcome("2. the coder pushes $DEFAULT_BRANCH", refusedByGitHub(PUSH_REFUSED, master.exit, master.err + master.out), "refused by GitHub: ${master.said()}",
                if (master.exit == 0) "SC-10: GitHub let $coderName push $DEFAULT_BRANCH" else "failed for another reason than GitHub's refusal: ${master.said()}")

            val created = coder.run(clone, "gh", "pr", "create", "--repo", repository, "--base", DEFAULT_BRANCH, "--head", branch, "--title", "just apps prove $time",
                "--body", "Opened by `just apps prove` (contracts.md §12), which closes it and deletes its branch.")
            number = PULL.find(created.out)?.groupValues?.get(1)
            val opened = number
            if (opened == null) {
                outcome("3. the coder opens a pull request", false, "", "not opened: ${created.said()}")
                return
            }
            val author = coder.run(clone, "gh", "pr", "view", opened, "--repo", repository, "--json", "author", "--jq", ".author.login").out.trim()
            outcome("3. the coder opens a pull request", login(author) == coderName, "#$opened opened by $author", "#$opened opened by $author, not $coderName")

            val reviewed = reviewer.run(clone, "gh", "pr", "review", opened, "--repo", repository, "--comment", "--body", "`just apps prove`: the reviewer's review.")
            val reviewers = reviewer.run(clone, "gh", "api", "repos/$repository/pulls/$opened/reviews", "--jq", ".[].user.login").out.lines().filter { it.isNotBlank() }
            outcome("4. the reviewer reviews #$opened", reviewed.exit == 0 && reviewers.any { login(it) == reviewerName }, "a review left by ${reviewers.joinToString(", ")}",
                "no review by $reviewerName: ${reviewed.said()}")

            val approved = coder.run(clone, "gh", "pr", "review", opened, "--repo", repository, "--approve", "--body", "`just apps prove`: the coder approves its own.")
            outcome("5. the coder approves its own #$opened", refusedByGitHub(APPROVAL_REFUSED, approved.exit, approved.err + approved.out), "refused by GitHub: ${approved.said()}",
                if (approved.exit == 0) "SC-10: GitHub let $coderName approve its own pull request" else "failed for another reason than GitHub's refusal: ${approved.said()}")
        }

        /** The pull request closed and the branch deleted, whatever went before: what the proof made, removed. */
        private fun remove() {
            val opened = number
            if (opened != null) {
                val closed = coder.run(clone, "gh", "pr", "close", opened, "--repo", repository)
                val state = coder.run(clone, "gh", "pr", "view", opened, "--repo", repository, "--json", "state", "--jq", ".state").out.trim()
                outcome("6. the coder closes #$opened", closed.exit == 0 && state == "CLOSED", "closed", "not closed ($state): ${closed.said()}")
            }
            if (pushed) {
                val deleted = coder.run(clone, "git", "push", "--quiet", "origin", "--delete", branch)
                val left = coder.run(clone, "git", "ls-remote", "--heads", "origin", branch)
                outcome("7. the coder deletes $branch", deleted.exit == 0 && left.exit == 0 && left.out.isBlank(), "gone", "not gone: ${deleted.said()}")
            }
        }

        /** One step's outcome, printed: [yes] when it went as §12 gives it. */
        private fun outcome(what: String, yes: Boolean, went: String, otherwise: String): Boolean {
            if (!yes) this.otherwise++
            say("  $what — ${if (yes) went else otherwise}")
            return yes
        }

        /** [seat]'s program [command] run in [directory], in the seat's environment, its standard input closed. */
        private fun Seat.run(directory: Path, vararg command: String): Ran {
            val builder = ProcessBuilder(listOf("/usr/bin/env", "--") + command).directory(directory.toFile()).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            builder.environment().clear()
            builder.environment().putAll(environment)
            val process = builder.start()
            val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
            val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
            if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Ran(-1, "", "$name's ${command.first()} did not end in ${WAIT_SECONDS}s")
            }
            return Ran(process.exitValue(), out.get(), err.get())
        }

        /** The App's name in a login GitHub gives — `app/<name>`, `<name>[bot]` or `<name>`. */
        private fun login(text: String): String = text.trim().removePrefix("app/").removeSuffix("[bot]")
    }

    /**
     * Whether a step GitHub must refuse was refused by GitHub (Collite/keporkak#63, 2): it ended otherwise than 0 and [said]
     * holds one of GitHub's own words for that refusal, [words]. Any other failure — a credential, the network, a push
     * that is no fast-forward — is not GitHub's refusal.
     */
    internal fun refusedByGitHub(words: List<String>, exit: Int, said: String): Boolean = exit != 0 && words.any { it in said }

    /** Whether `gh auth status` found no login (Amendment 43): it ended otherwise than 0, saying so in gh's words. */
    internal fun noLogin(exit: Int, said: String): Boolean = exit != 0 && NO_LOGIN in said

    /** GitHub's words for a push to a branch its rules protect: the rule-violation code, or the protected-branch hook's (recorded on #14). */
    internal val PUSH_REFUSED = listOf("GH013", "protected branch hook declined", "Cannot update this protected ref")

    /** GitHub's words when an author approves its own pull request (recorded on #14). */
    internal val APPROVAL_REFUSED = listOf("Can not approve your own pull request")

    private const val NO_LOGIN = "You are not logged into any GitHub hosts"

    /** A GitHub App's installation token, as a seat's transcript knows it: its prefix and what follows, up to a space, a quote or a backslash. */
    private val INSTALLATION_TOKEN = Regex("""ghs_[^\s"'\\]+""")

    /** Nothing is delivered: the proof's mailroom carries no message. */
    private object NoDeliveries : Deliveries {
        override fun idle(seat: String) = false
        override fun wake(seat: String, message: Message) = false
    }

    private const val TOY = "kp-toy"
    private const val EFFORT = "TOY"
    private const val STAGE = "TOY/P0/S0.1"
    private const val PROFILE = "toy"
    private const val DEFAULT_BRANCH = "master"
    private const val CODER = "coder"
    private const val REVIEWER = "reviewer"
    private const val HIDDEN = "‹hidden›"
    private const val SAID = 300
    private const val WAIT_SECONDS = 120L
    private val PULL = Regex("/pull/([0-9]+)")
    /** What a seat's agent gives it (ClaudeAgent): a path, the office user's home, a locale. */
    private val BASE = mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin", "HOME" to System.getProperty("user.home"), "LANG" to "C.UTF-8")
}
