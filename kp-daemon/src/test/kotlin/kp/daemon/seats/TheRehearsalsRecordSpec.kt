@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.daemon.FakeSeating
import kp.daemon.Walk
import kp.daemon.WalkFailure
import kp.daemon.hero.Hero
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * KP/P3/S3.6 T1 — a rehearsal's record, and a turn's usage by its parts (Bora reads these). The seam: the thin thread
 * (`rules/walks/thin-thread.yaml`) played as `just rehearse thin-thread --seats fake` plays it — the real mailroom, seat
 * API and driver, S2.1's fake agent as the seats — its evidence going to a folder outside the checkout, as the office's
 * scratch root is; the evidence file it leaves, and a seat's transcript. The checkout's `build/` is where the rehearsal's
 * throwaway project is: a spec removes it and opens every pointer of the evidence.
 */
class TheRehearsalsRecordSpec : StringSpec({
    "a rehearsal's log and each seat's transcript lie beside its evidence, with one seat and with two; no pointer of the evidence is under a build/ folder" {
        val two = Rehearsed().also { it.play() }
        two.folder().listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("mail", "transcripts")
        two.folder().resolve("transcripts").listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("coder-1.log", "reviewer-1.log")
        two.removeTheBuildFolder()
        two.pointers().filterNot { it.exists() } shouldBe emptyList()
        two.pointers().filter { "/build/" in it.toString() } shouldBe emptyList()
        two.close()

        val one = Rehearsed(reviewer = """{"turns": [[{"do": "end"}]]}""", claude = setOf("coder-1")).also { it.play() }
        one.folder().resolve("transcripts").listDirectoryEntries().map { it.name } shouldBe listOf("coder-1.log")
        one.removeTheBuildFolder()
        one.pointers().filterNot { it.exists() } shouldBe emptyList()
        one.pointers().filter { "/build/" in it.toString() } shouldBe emptyList()
        one.close()
    }

    "the same holds for a rehearsal that fails and for one that ends over its time" {
        val failed = Rehearsed(coder = """{"turns": [[{"do": "mcp", "tool": "send", "arguments": {"kind": "stop", "subject": "a stop", "args": {"sc": "SC-10"}}}, {"do": "end"}]]}""")
        shouldThrow<WalkFailure> { failed.play() }.message shouldContain "coder-1 sent stop, not started"
        failed.evidence().readText() shouldContain "— failed"
        failed.removeTheBuildFolder()
        failed.pointers().filterNot { it.exists() } shouldBe emptyList()
        failed.pointers().filter { "/build/" in it.toString() } shouldBe emptyList()
        failed.close()

        val late = Rehearsed(coder = """{"turns": [[{"do": "end"}]]}""")
        late.hero.through(4)
        var failure: Throwable? = null
        val waiting = thread(isDaemon = true) { failure = runCatching { late.hero.rest() }.exceptionOrNull() }
        late.until { late.hero.player.awaiting == "coder-1" }
        late.hero.clock.now = late.hero.clock.now.plus(Duration.ofMinutes(10)).plusSeconds(1)
        late.hero.office.changes.signal()
        waiting.join(30_000)
        failure?.message shouldBe "walk thin-thread step 5 (started · coder-1 · started): coder-1 sent nothing in 10m — its transcript: " +
            late.hero.project.resolve("office/seats/_transcripts/coder-1.log")
        late.removeTheBuildFolder()
        late.pointers().filterNot { it.exists() } shouldBe emptyList()
        late.pointers().filter { "/build/" in it.toString() } shouldBe emptyList()
        late.close()
    }

    "a turn's end shows each part of its usage — input, output, cache read, cache write, thought, total — and says which the agent did not report; the evidence sums each part per seat over two turns" {
        val parts = Rehearsed(
            coder = """{"turns": [[{"do": "mcp", "tool": "send", "arguments": {"kind": "started", "subject": "started", "args": {}}},
                {"do": "mcp", "tool": "send", "arguments": {"kind": "verdict", "subject": "a kind outside the coder's", "args": {"verdict": "PASS", "sha": "none", "blockers": [], "follow_ups": []}}},
                {"do": "end", "usage": {"input": 6, "output": 205, "cache_read": 60000, "cache_write": 9000, "thought": 40, "total": 69211}}]],
              "loaded_turns": [
                [{"do": "run", "seconds": 2},
                 {"do": "mcp", "tool": "send", "arguments": {"kind": "review-request", "subject": "review-request", "args": {"attest": ["done_lines_hold"]}}},
                 {"do": "end", "usage": {"input": 4, "output": 100, "cache_read": 69000, "cache_write": 300, "total": 69404}}],
                [{"do": "end", "usage": {"input": 1, "output": 1, "total": 2}}],
                [{"do": "end", "usage": {"input": 1, "output": 1, "total": 2}}]]}""",
            reviewer = """{"turns": [[{"do": "end", "usage": {"input": 3, "output": 30, "cache_read": 50000, "cache_write": 7000, "thought": 10, "total": 57033}}]]}""",
        ).also { it.play() }

        parts.transcript("coder-1").filter { " turn ended: " in it }.map { it.substringAfter(" turn ended: ") }.take(2) shouldBe listOf(
            "end_turn; tokens input 6, output 205, cache read 60000, cache write 9000, thought 40, total 69211; model calls not reported",
            "end_turn; tokens input 4, output 100, cache read 69000, cache write 300, thought not reported, total 69404; model calls not reported",
        )
        parts.usage("coder-1") shouldBe listOf(
            "| coder-1 | 1 | 6 | 205 | 60000 | 9000 | 40 | 69211 |",
            "| coder-1 | 2 | 4 | 100 | 69000 | 300 | not reported | 69404 |",
            "| coder-1 | 3 | 1 | 1 | not reported | not reported | not reported | 2 |",
            "| coder-1 | 4 | 1 | 1 | not reported | not reported | not reported | 2 |",
            "| coder-1 | 4 turns | 12 | 307 | 129000 | 9300 | 40 (not reported in 3) | 138619 |",
        )
        parts.usage("reviewer-1") shouldBe listOf(
            "| reviewer-1 | 1 | 3 | 30 | 50000 | 7000 | 10 | 57033 |",
            "| reviewer-1 | 1 turn | 3 | 30 | 50000 | 7000 | 10 | 57033 |",
        )
        parts.close()
    }
})

/**
 * The thin thread with fake seats (S2.5's seam) — [coder] and [reviewer] their scripts when given, [claude] the seats that
 * play their own steps — its evidence in a folder of its own outside the checkout.
 */
private class Rehearsed(coder: String? = null, reviewer: String? = null, claude: Set<String>? = null) : AutoCloseable {
    private val scratch: Path = Files.createTempDirectory("kp-s3.6-scratch-")
    val hero = Hero("thin-thread", amend = { walk -> claude?.let { walk.only(it) } ?: walk },
        fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = "kp-no-tmux-here", panes = "kp-record", evidence = scratch,
            scripts = listOfNotNull(coder?.let { "coder-1" to it }, reviewer?.let { "reviewer-1" to it }).toMap()))
    private var closed = false

    /** Plays the walk to its end; a failure is thrown once the daemon is closed. */
    fun play() = try { hero.rest() } finally { stop() }

    fun until(done: () -> Boolean) {
        val end = System.nanoTime() + 30_000_000_000
        while (!done()) { check(System.nanoTime() < end) { "waited 30s" }; Thread.sleep(20) }
    }

    /** The evidence file: the one `.md` under the scratch folder. */
    fun evidence(): Path = scratch.listDirectoryEntries("thin-thread-*.md").single()

    /** The folder beside the evidence, named as it is. */
    fun folder(): Path = evidence().resolveSibling(evidence().name.removeSuffix(".md"))

    /** Every absolute path the evidence names: a `/` that no name, dot or tilde comes before. */
    fun pointers(): List<Path> = Regex("""(?<![\w.~-])(/[^\s|`)]+)""").findAll(evidence().readText()).map { Path.of(it.value.trimEnd('.', ',', ';', ':')) }.toList()

    /** The rows of the evidence's usage table for [seat]: each turn, and the seat's sums. */
    fun usage(seat: String): List<String> = evidence().readLines().filter { it.startsWith("| $seat | ") && it.split(" | ").size == 8 }

    fun transcript(seat: String): List<String> = hero.project.resolve("office/seats/_transcripts/$seat.log").readLines()

    /** Closes the daemon, then removes its throwaway project — what lies under the checkout's `build/`. */
    fun removeTheBuildFolder() {
        stop()
        hero.project.deleteRecursively()
    }

    private fun stop() {
        if (!closed) hero.close()
        closed = true
    }

    override fun close() {
        stop()
        hero.project.deleteRecursively()
        scratch.deleteRecursively()
    }
}

/** This walk with only [seats] playing their own steps. */
private fun Walk.only(seats: Set<String>): Walk = Walk(name, stage, this.seats, facts, steps, initial, stages, final, expectedCounts,
    claudeSeats?.let { Walk.ClaudeSeats(seats, it.wait, it.tokens) }, fixtures, file)
