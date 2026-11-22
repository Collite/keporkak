@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kp.daemon.FakeSeating
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.daemon.hero.Hero
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kp.model.Refused
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * S2.5 T1 — the thin thread (`rules/walks/thin-thread.yaml`), played as `just rehearse thin-thread --seats fake` plays it:
 * the real mailroom, seat API and driver, S2.1's fake agent as both seats — each step of theirs sent by the fake agent
 * through the seat API's `send`, as a real seat sends it — Bora's approval the real `kp`, the clock injected. Everything
 * of the thread but the model. The seats follow the walk's own fake scripts (`rules/walks/thin-thread/fake/`) unless a
 * spec gives one.
 */
class TheThinThreadSpec : StringSpec({
    "a step of a real seat is waited for, not sent, and judged like any other message when it comes" {
        ThinThread().use { thread ->
            thread.hero.through(4) // proposed, approved by `sudo kp approve`, the worktree, the assign

            val started = thread.hero.player.step()
            val outside = thread.hero.player.step()

            // Both are what coder-1's fake agent sent through the seat API, answered by the mailroom's judgement.
            thread.sent("coder-1").map { it.text("result") } shouldBe listOf(
                """{"status":"delivered","id":"${started.message}","transition":"T-08"}""",
                """{"status":"refused","id":"${outside.message}","rule":"verdict","requirement":"from: reviewer","refusals_today":1}""",
            )
            listOf(started.outcome, outside.outcome) shouldBe listOf("delivered", "refused")
            thread.lines().filterIsInstance<Message>().first { it.id.value == started.message }.by shouldBe "seat:coder-1"
            thread.lines().filterIsInstance<Refused>().single().rule shouldBe "verdict"
            thread.hero.office.state.stage(thread.hero.stage()) shouldBe "building"
        }
    }

    "a seat that sends another kind, or nothing within the walk's time, ends the rehearsal as failed, with the step" {
        ThinThread(coder = """{"turns": [[{"do": "mcp", "tool": "send", "arguments": {"kind": "stop", "subject": "a stop", "args": {"sc": "SC-10"}}}, {"do": "end"}]]}""").use { thread ->
            val failure = shouldThrow<WalkFailure> { thread.hero.rest() }

            failure.message shouldContain "walk thin-thread step 5 (started · coder-1 · started): coder-1 sent stop, not started"
            failure.message shouldContain "its transcript: ${thread.hero.project.resolve("office/seats/_transcripts/coder-1.log")}"
        }
        ThinThread(coder = """{"turns": [[{"do": "end"}]]}""").use { thread ->
            thread.hero.through(4)
            var failure: Throwable? = null
            val waiting = thread(isDaemon = true) { failure = runCatching { thread.hero.player.step() }.exceptionOrNull() }
            thread.until("the player waits for coder-1's step") { thread.hero.player.awaiting == "coder-1" }
            thread.hero.clock.now = thread.hero.clock.now.plus(Duration.ofMinutes(10)).plusSeconds(1)
            thread.hero.office.changes.signal()
            waiting.join(30_000)

            failure?.message shouldBe "walk thin-thread step 5 (started · coder-1 · started): coder-1 sent nothing in 10m — its transcript: " +
                thread.hero.project.resolve("office/seats/_transcripts/coder-1.log")
        }
    }

    "a rehearsal whose seats have used more tokens than the walk allows ends as failed" {
        val costly = """{"turns": [[{"do": "mcp", "tool": "send", "arguments": {"kind": "started", "subject": "started", "args": {}}},
            {"do": "end", "usage": {"input": 1500000, "output": 1, "total": 1500001}}]]}"""
        ThinThread(coder = costly).use { thread ->
            // Without that end the walk would wait for the coder's next step on a clock that never moves: a minute is the bound.
            var failure: Throwable? = null
            thread(isDaemon = true) { failure = runCatching { thread.hero.rest() }.exceptionOrNull() }.join(60_000)

            failure.shouldBeInstanceOf<WalkFailure>().message shouldContain Regex("""^walk thin-thread step [0-9]+ \(""")
            failure?.message shouldContain "the seats have used 1500001 tokens, more than the walk's 1500000 (coder-1 1500001); the last turn was coder-1's — its transcript: " +
                thread.hero.project.resolve("office/seats/_transcripts/coder-1.log")
        }
        // The walk's last turn — the reviewer's, woken by `review-bind` — is held to the walk's tokens too (review-024, B1).
        ThinThread(reviewer = """{"turns": [[{"do": "end", "usage": {"input": 1500000, "output": 0, "total": 1500000}}]]}""").use { thread ->
            var failure: Throwable? = null
            thread(isDaemon = true) { failure = runCatching { thread.hero.rest() }.exceptionOrNull() }.join(60_000)

            failure.shouldBeInstanceOf<WalkFailure>().message shouldContain "walk thin-thread step 12 (bind · teamlead · review-bind), at the walk's end: the seats have used "
            failure?.message shouldContain "more than the walk's 1500000 (coder-1 "
            failure?.message shouldContain "the last turn was reviewer-1's — its transcript: " + thread.hero.project.resolve("office/seats/_transcripts/reviewer-1.log")
            thread.evidence.listDirectoryEntries("thin-thread-*.md").single().readText() shouldStartWith "# Rehearsal thin-thread with its seats playing their own steps — failed"
        }
    }

    "a step that reports a seat gone ends that seat's process, and the seat comes back with its unread mail" {
        ThinThread().use { thread ->
            thread.hero.through(10) // … coder-1's process ended at step 7, re-seated at 9, `re-seated` sent at 10

            val (first, second) = thread.pids("coder-1")
            ProcessHandle.of(first).map { it.isAlive }.orElse(false) shouldBe false
            ProcessHandle.of(second).map { it.isAlive }.orElse(false) shouldBe true
            // The driver's own observation of the end it reported first: the process gone, or the protocol closed with it.
            thread.lines().filterIsInstance<Fact>().first { it.subject == "coder-1" }.evidence!! shouldContain Regex("^(the agent's process ended with exit code|the protocol closed at)")
            thread.lines().filterIsInstance<Op>().filter { it.op == "seat.reseat" }.map { it.phase } shouldBe listOf("requested", "ok")
            thread.prompts("coder-1")[1] shouldBe "You are coder-1, re-seated: your session was lost and is given back to you. " +
                "Your list: project:tasks-p2-s2.5-thin-thread.md. STATUS: project:STATUS.md. You have no unread mail."
        }
    }

    "the evidence names the log, each seat's session and transcript, and what each seat used — and no secret" {
        val leaking = Files.readString(checkout.resolve("rules/walks/thin-thread/fake/coder-1.json"))
            .replace(""""args": {}}""", """"args": {}, "body": "${'$'}{CLAUDE_CODE_OAUTH_TOKEN} ${'$'}{KP_SESSION_TOKEN}"}""")
        ThinThread(coder = leaking).use { thread ->
            thread.hero.rest()

            val file = thread.evidence.listDirectoryEntries("thin-thread-*.md").single()
            val evidence = file.readText()
            // The log and the transcripts as the play's record keeps them, beside the evidence (S3.6 T5).
            val record = file.resolveSibling(file.fileName.toString().removeSuffix(".md"))
            evidence shouldContain "the log: ${record.resolve("mail")}"
            evidence shouldContain "| coder-1 | coder | claude-opus-5-5 | low | ${thread.sessions("coder-1").joinToString(" · ")} | " +
                "${record.resolve("transcripts/coder-1.log")} | 4400 in 4 turns |"
            evidence shouldContain "| reviewer-1 | reviewer | claude-opus-5-5 | low | ${thread.sessions("reviewer-1").single()} | " +
                "${record.resolve("transcripts/reviewer-1.log")} | 1100 in 1 turn |"
            evidence shouldNotContain FAKE_CLAUDE_TOKEN
            evidence.split(Regex("""[^A-Za-z0-9_-]+""")).map(::sha256) shouldNotContainAny thread.sessionTokenDigests("coder-1")
        }
    }
})

private const val FAKE_CLAUDE_TOKEN = "no-token-the-fake-agent-needs-none"

/** The thin thread with fake seats on a throwaway project, its evidence in a folder of its own; [coder], [reviewer]: their scripts. */
private class ThinThread(coder: String? = null, reviewer: String? = null) : AutoCloseable {
    private val classpath = System.getProperty("kp.driver.fake.classpath")
    val evidence: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "evidence-")
    val hero = Hero("thin-thread", fake = FakeSeating(classpath, tmux = "kp-no-tmux-here", panes = "kp-thin-thread", evidence = evidence,
        scripts = listOfNotNull(coder?.let { "coder-1" to it }, reviewer?.let { "reviewer-1" to it }).toMap()))
    private val agents = FakeAgents(classpath, hero.project.resolve("office/fake"))

    fun lines() = hero.office.counted()

    /** Waits until [done] holds, at most 30 s. */
    fun until(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "waited 30s for $what" }
            Thread.sleep(20)
        }
    }

    /** What [seat]'s fake agents sent through the seat API, in order, each with the tool's result as it came back. */
    fun sent(seat: String): List<JsonObject> = agents.received(seat).filter { it.text("event") == "mcp" }

    /** The texts [seat]'s fake agents were prompted with, in order, across its processes. */
    fun prompts(seat: String): List<String> = agents.received(seat).filter { it.text("event") == "session/prompt" }.map { it.text("text")!! }

    /** The pids of [seat]'s fake agents, in the order they started. */
    fun pids(seat: String): List<Long> = agents.received(seat).filter { it.text("event") == "start" }.map { it.text("pid")!!.toLong() }

    /** The sessions [seat]'s fake agents opened or loaded, each once, in order. */
    fun sessions(seat: String): List<String> = hero.project.resolve("office/seats/$seat/fake-sessions").listDirectoryEntries("*.jsonl").map { it.fileName.toString().removeSuffix(".jsonl") }

    /** The digests of the session tokens [seat]'s processes were given, as the fake agent recorded them. */
    fun sessionTokenDigests(seat: String): List<String> = agents.received(seat).filter { it.text("event") == "start" }.map { it.text("KP_SESSION_TOKEN_sha256")!! }

    override fun close() {
        hero.close()
        evidence.deleteRecursively()
    }

}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private infix fun List<String>.shouldNotContainAny(digests: List<String>) = filter { it in digests } shouldBe emptyList()

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
