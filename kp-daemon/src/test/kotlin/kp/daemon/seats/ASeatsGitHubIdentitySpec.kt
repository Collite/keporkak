@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.printedDuring
import kp.daemon.seats.IdentityOffice.Companion.sha256
import kp.daemon.seats.SeatsOffice.Companion.text
import kp.facts.TOY_STAGE
import kp.model.Op
import kp.model.Transition
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * S3.7 T1 — a seat's GitHub identity (contracts.md §4.4, Amendment 35). Seams: the seat API's listener over HTTP with
 * session tokens, in front of S3.2's local GitHub ([IdentityOffice]); a seat's start through the daemon's seats, with
 * S2.1's fake agent ([SeatsOffice] with a local GitHub); the home's two programs run as processes against that listener
 * and a local git repository; a seat's transcript. Each spec is named as its sentence in the list.
 */
class ASeatsGitHubIdentitySpec : StringSpec({
    "an ask is answered with a token minted then, for its seat's role and stage: a coder and a reviewer of one stage get different Apps and permissions; a seat with no stage gets the office's repositories; two asks are two mints; a dead token, an unknown one and a role with no entry are refused, nothing minted" {
        IdentityOffice().use { office ->
            val coder = office.seat("coder-1", "coder", TOY_STAGE)
            val reviewer = office.seat("reviewer-1", "reviewer", TOY_STAGE)
            val principal = office.seat("principal", "principal", stage = null)

            val asks = listOf(coder.ask(), coder.ask(), reviewer.ask(), principal.ask())

            // What each mint asked GitHub for: the role's App and permissions, the stage's repository or the office's.
            office.github.apps.issued.map { "${it.app.identity} ${it.repositories} ${it.permissions}" } shouldBe listOf(
                "kp-coder [kp-toy] {contents=write, pull_requests=write}",
                "kp-coder [kp-toy] {contents=write, pull_requests=write}",
                "kp-reviewer [kp-toy] {checks=read, contents=read, pull_requests=write}",
                "kp-reviewer [keporkak, kp-toy] {contents=read, pull_requests=read}",
            )
            // Each ask is answered with the token of its own mint, and when it expires.
            asks.map { it.status } shouldBe listOf(200, 200, 200, 200)
            asks.map { it.token } shouldBe office.github.apps.issued.map { it.token }
            asks.forEach { it.expires shouldNotBe null }
            // One journal line per ask: the seat, the App, the repositories.
            office.journal.map { it.substringBefore(": handed") } shouldBe listOf(
                "github-token: coder-1 (coder) as kp-coder for Collite/kp-toy",
                "github-token: coder-1 (coder) as kp-coder for Collite/kp-toy",
                "github-token: reviewer-1 (reviewer) as kp-reviewer for Collite/kp-toy",
                "github-token: principal (principal) as kp-reviewer for Collite/keporkak, Collite/kp-toy",
            )

            val before = office.github.apps.requests.size
            val qa = office.seat("qa-1", "qa", TOY_STAGE)
            coder.dies()

            listOf(coder.ask(), office.stranger().ask(), qa.ask()).map { "${it.status} ${it.error}" } shouldBe listOf(
                "401 a live session token is required, in the Authorization header only",
                "401 a live session token is required, in the Authorization header only",
                "403 github.seats gives the role qa no App",
            )
            office.github.apps.requests.size shouldBe before
        }
    }

    "`token.mint` at a seat's start: `ok` makes the seat ready and names the App, the repositories and the permissions; `failed` leaves it provisioned, with GitHub's reason; a role with no entry is `ok` with nothing minted; with two seats of one stage each result is its own seat's" {
        SeatsOffice(github = true).use { office ->
            office.project.resolve("tasks-p2-s2.4-seats.md").writeText("# KP-kp-toy-P2 · S2.4 — Seats            (PR: feat/kp-kp-toy-p2-s2.4-seats)\n")
            fun start(seat: String, role: String) {
                val request = office.send("teamlead", "session-request", """{"action": "add", "role": "$role", "seat": "$seat"}""").id
                office.send("pool", "session-report", """{"seat": "$seat", "role": "$role"}""", re = request)
                office.until("$seat's token.mint ended") {
                    val mints = office.ops("token.mint")
                    mints.any { it.with?.text("seat") == seat } && mints.count { it.phase != "requested" } == mints.count { it.phase == "requested" }
                }
            }
            // GitHub refuses the reviewer's App the checks its role is given.
            office.apps!!.withdraw("kp-reviewer", "checks")

            start("reviewer-1", "reviewer")
            start("coder-3", "coder")
            start("qa-1", "qa")

            office.ops("token.mint").filter { it.phase != "requested" }.map { "${it.phase} ${it.result ?: it.reason}" } shouldBe listOf(
                "failed GitHub answered 422 to POST /app/installations/184047637/access_tokens: The permissions requested are not granted to this installation.",
                """ok {"app":"kp-coder","repositories":["Collite/kp-toy"],"permissions":{"contents":"write","pull_requests":"write"}}""",
                """ok {"app":"none"}""",
            )
            // Each seat moved by its own result: ready only after its token.mint was ok.
            val seats = office.lines().filterIsInstance<Transition>().filter { it.machine == "seat" && it.subject in setOf("reviewer-1", "coder-3", "qa-1") && it.from != it.to }
            seats.map { "${it.subject} ${it.from}→${it.to}" } shouldBe listOf("coder-3 provisioned→ready", "qa-1 provisioned→ready")
            office.office.state.machine("seat", "reviewer-1")?.state shouldBe "provisioned"
            val coderReady = seats.first { it.subject == "coder-3" }.seq.toLong()
            val coderMinted = office.lines().filterIsInstance<Op>().single { it.op == "token.mint" && it.phase == "ok" && it.result?.text("app") == "kp-coder" }.seq.toLong()
            (coderMinted < coderReady) shouldBe true
            // Nothing was minted for the qa seat, and no record of the log holds a token.
            office.apps!!.issued.map { it.app.identity } shouldBe listOf("kp-coder")
            val log = office.project.resolve("mail").walk().filter { it.isRegularFile() }.joinToString("\n") { it.readText() }
            office.apps!!.issued.filter { it.token in log } shouldBe emptyList()
        }
    }

    "`git` in a seat's environment asks at each use — two uses are two asks — reaches GitHub over https where the remote is written for ssh, and with no answer fails with the reason and prompts for nothing" {
        IdentityOffice().use { office ->
            val coder = office.seat("coder-1", "coder", TOY_STAGE)
            // The office user's own configuration names two helpers of its own: the seat's git uses neither.
            office.userGitConfig("[credential]\n\thelper = store\n[credential \"https://github.com\"]\n\thelper = \"!echo password=the-office-users\"\n")
            val fill = "protocol=https\nhost=github.com\npath=Collite/kp-toy.git\n\n"

            val first = coder.run("git", "credential", "fill", input = fill)
            val second = coder.run("git", "credential", "fill", input = fill)

            val minted = office.github.apps.issued.map { it.token }
            minted.size shouldBe 2
            first.out.lines().filter { it.startsWith("username=") || it.startsWith("password=") } shouldBe listOf("username=x-access-token", "password=${minted[0]}")
            second.out.lines().filter { it.startsWith("password=") } shouldBe listOf("password=${minted[1]}")

            coder.run("git", "init", "--quiet", "repository")
            coder.run("git", "-C", "repository", "remote", "add", "origin", "git@github.com:Collite/kp-toy.git")
            coder.run("git", "-C", "repository", "ls-remote", "--get-url", "origin").out shouldBe "https://github.com/Collite/kp-toy.git\n"

            office.down()
            val unanswered = coder.run("git", "credential", "fill", input = fill, seconds = 20)

            unanswered.exit shouldNotBe 0
            unanswered.out shouldBe ""
            unanswered.err shouldContain "git-credential-keporkak: no GitHub token: the daemon did not answer: Failed to connect to 127.0.0.1"
            unanswered.err shouldContain "told us to quit"
        }
    }

    "`gh` in a seat's environment asks, then runs the office's `gh` with every argument as given — a space, a quote, an empty one — and its exit code; the token is in that process's environment and in no file" {
        IdentityOffice().use { office ->
            val coder = office.seat("coder-1", "coder", TOY_STAGE)
            office.officeGh(exit = 3)

            val created = coder.run("gh", "pr", "create", "--title", "a b", "--body", "")

            created.exit shouldBe 3
            office.officeGhArguments() shouldBe listOf("pr", "create", "--title", "a b", "--body", "")
            val token = office.github.apps.issued.single().token
            office.officeGhToken() shouldBe sha256(token)
            office.filesHolding(token) shouldBe emptyList()

            coder.run("gh", "pr", "comment", "--body", "it's \"quoted\"", "").exit shouldBe 3
            office.officeGhArguments() shouldBe listOf("pr", "comment", "--body", "it's \"quoted\"", "")
            office.github.apps.issued.size shouldBe 2

            office.down()
            val unanswered = coder.run("gh", "pr", "list", seconds = 20)

            unanswered.exit shouldBe 1
            unanswered.err shouldStartWith "gh (keporkak): no GitHub token: the daemon did not answer: Failed to connect to 127.0.0.1"
            office.officeGhRuns() shouldBe 2
        }
    }

    "a token handed to a seat and then printed by it is in no line of its transcript: at a line's start, across the cut, after it; and no ask's journal line holds one" {
        // The seat's turn: `git credential fill` in its own environment, then the token printed three times — at the start of
        // its output, across the transcript's cut at 2000 characters, and after it.
        val print = """t=${'$'}(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p'); """ +
            """printf '%s %1944s%s %s\n' "${'$'}t" '' "${'$'}t" "${'$'}t""""
        val turn = """{"turns": [[{"do": "shell", "id": "print", "command": ${kotlinx.serialization.json.JsonPrimitive(print)}}, {"do": "end"}]]}"""
        lateinit var office: SeatsOffice
        val printed = printedDuring {
            office = SeatsOffice(github = true, scripts = mapOf("coder-1" to turn))
            office.project.resolve("tasks-p2-s2.4-seats.md").writeText("# KP-kp-toy-P2 · S2.4 — Seats            (PR: feat/kp-kp-toy-p2-s2.4-seats)\n")
            val stop = office.send("coder-1", "stop", """{"sc": "SC-3"}""").id
            office.send("principal", "triage", """{"outcome": "resume", "found": "1", "since": "$stop"}""", re = stop)
            val transcript = office.project.resolve("office/seats/_transcripts/coder-1.log")
            office.until("coder-1's turn ended") { transcript.isRegularFile() && transcript.readText().lines().any { " coder-1 turn ended" in it } }
            office.close()
        }

        val minted = office.apps!!.issued.map { it.token }
        minted.size shouldBe 1
        office.received("coder-1").single { it.text("event") == "shell" }.text("exit") shouldBe "0"
        val transcript = office.project.resolve("office/seats/_transcripts/coder-1.log").readText()
        transcript.lines().single { " tool print " in it && "→" in it } shouldContain "‹hidden›"
        // No token, nor any dozen characters of one, in a transcript, the log, the journal or what the spec printed — nor in
        // what the fake agents recorded. (The agent's own sessions, in its home, keep what it printed: that is the agent's.)
        val files = listOf("office/seats/_transcripts", "mail", "office/fake").flatMap { folder -> office.project.resolve(folder).walk().filter { it.isRegularFile() }.map { it.readText() } }
        val seen = files + office.said + printed
        minted.flatMap { it.windowed(12) }.filter { piece -> seen.any { piece in it } } shouldBe emptyList()
        // The seat's environment holds no GitHub token: its variables, every one.
        office.received("coder-1").first { it.text("event") == "start" }["env"].toString() shouldBe
            """["CLAUDE_CODE_OAUTH_TOKEN","CLAUDE_CONFIG_DIR","GIT_ASKPASS","GIT_CONFIG_COUNT","GIT_CONFIG_KEY_0","GIT_CONFIG_KEY_1","GIT_CONFIG_KEY_2",""" +
            """"GIT_CONFIG_KEY_3","GIT_CONFIG_VALUE_0","GIT_CONFIG_VALUE_1","GIT_CONFIG_VALUE_2","GIT_CONFIG_VALUE_3","GIT_TERMINAL_PROMPT","KP_HOOK_URL","KP_SESSION_TOKEN","LANG","PATH"]"""
        office.delete()
    }

    "a token a seat printed before the daemon is started again is in no line of its transcript when the seat is re-seated with its session loaded and replayed — by a daemon that never handed it (review-030 B1)" {
        // The second daemon's turn: `git credential fill`, then the token printed twice. Once its session is loaded, the
        // seat's turns do nothing: what writes the token again is the replay alone.
        val print = """t=${'$'}(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p'); """ +
            """printf '%s, and again: %s\n' "${'$'}t" "${'$'}t""""
        val turn = """{"turns": [[{"do": "shell", "id": "print", "command": ${kotlinx.serialization.json.JsonPrimitive(print)}}, {"do": "end"}]], "loaded_turns": [[{"do": "end"}]]}"""
        lateinit var first: SeatsOffice
        lateinit var second: SeatsOffice
        lateinit var third: SeatsOffice
        var printing = 0
        var loading = 0
        val printed = printedDuring {
            // The first daemon seeds coder-1 as the walk starts it, with no session on the log.
            first = SeatsOffice(github = true)
            first.close()
            first.project.resolve("tasks-p2-s2.4-seats.md").writeText("# KP-kp-toy-P2 · S2.4 — Seats            (PR: feat/kp-kp-toy-p2-s2.4-seats)\n")
            val transcript = first.project.resolve("office/seats/_transcripts/coder-1.log")
            printing = transcript.readText().lines().size
            // The second re-seats it in a new session, which the log names; the re-seat's prompt is the turn that prints.
            second = first.again(scripts = mapOf("coder-1" to turn), github = true)
            second.until("coder-1 re-seated, its turn ended") { transcript.readText().lines().drop(printing).any { " coder-1 turn ended" in it } }
            second.close()
            loading = transcript.readText().lines().size
            // The third re-seats it with that session loaded: its agent replays the history, then the re-seat's prompt ends.
            third = second.again(github = true)
            third.until("coder-1 re-seated, its session loaded, its turn ended") { transcript.readText().lines().drop(loading).any { " coder-1 turn ended" in it } }
            third.close()
        }

        val minted = second.apps!!.issued.map { it.token }
        minted.size shouldBe 1
        // The third daemon was asked for no token: the one its seat's agent replays is one it never handed, never knew.
        third.apps!!.issued shouldBe emptyList()
        third.said.filter { "github-token:" in it } shouldBe emptyList()
        val lines = first.project.resolve("office/seats/_transcripts/coder-1.log").readText().lines()
        lines.subList(printing, loading).single { " coder-1 tool print " in it && "→" in it } shouldContain "‹hidden›, and again: ‹hidden›"
        lines.drop(loading).single { " coder-1 loading session " in it }
        lines.drop(loading).single { " coder-1 replay tool print " in it && "→" in it } shouldContain "‹hidden›, and again: ‹hidden›"
        // No token, nor any dozen characters of one, in a transcript, the log, the journal or what the spec printed.
        val files = listOf("office/seats/_transcripts", "mail", "office/fake").flatMap { folder -> first.project.resolve(folder).walk().filter { it.isRegularFile() }.map { it.readText() } }
        val seen = files + first.said + second.said + third.said + printed
        minted.flatMap { it.windowed(12) }.filter { piece -> seen.any { piece in it } } shouldBe emptyList()
        first.delete()
    }
})
