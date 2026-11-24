@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.hero.Hero
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.10 T1 — the pool block and a rehearsal's failure, as Amendment 38 corrects them (Bora reads these). The seams:
 * `scripts/check-rules.py --pool` and the daemon's load of the rules (`RuleFiles`, `InForceRules`) on a copy of the
 * checkout's rules, robots, pool and personas; the thin thread played as `just rehearse thin-thread --seats fake` plays
 * it, its record going to a folder outside the checkout, as the office's scratch root is — and what `just rehearse`
 * prints of a failed play, "the walk stops here: " and the failure.
 */
class SpecsThatFailByChanceSpec : StringSpec({
    "a role under `github.seats` that no persona declares is refused by check-rules.py and by the daemon's load — one such role among good ones, and a block of nothing else" {
        // Each line as the copy's file holds it.
        val among = poolWith("    coder: {app: kp-coder,", "    codr: {app: kp-coder,")
        val amongLine = lineOf(among.resolve("pool/offices.yaml"), "    codr: {app: kp-coder,")
        checkRules(among) shouldBe listOf("pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line $amongLine)")
        daemonLoad(among) shouldContain "pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line $amongLine)"

        val alone = poolWith { withSeats(it, "    codr: {app: kp-coder, permissions: {contents: write}}") }
        val aloneLine = lineOf(alone.resolve("pool/offices.yaml"), "    codr: {app: kp-coder,")
        checkRules(alone) shouldBe listOf("pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line $aloneLine)")
        daemonLoad(alone) shouldContain "pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line $aloneLine)"

        // Master's block: each of its seven roles is a persona's.
        val master = poolWith("  seats:", "  seats:")
        checkRules(master) shouldBe emptyList()
        daemonLoad(master) shouldBe emptyList()
    }

    "a rehearsal that fails prints where its record is; one whose record cannot be written prints both failures, the play's first" {
        Rehearsing(coder = STOPS).use { rehearsal ->
            val failure = shouldThrow<WalkFailure> { rehearsal.hero.rest() }

            val record = rehearsal.record()
            failure.message shouldBe "walk thin-thread step 5 (started · coder-1 · started): coder-1 sent stop, not started — its transcript: " +
                record.resolve("transcripts/coder-1.log")
            record.resolve("transcripts/coder-1.log").exists() shouldBe true
        }
        Rehearsing(coder = STOPS).use { rehearsal ->
            rehearsal.scratch.toFile().setWritable(false)
            val failure = shouldThrow<WalkFailure> { rehearsal.hero.rest() }

            failure.message!! shouldStartWith "walk thin-thread step 5 (started · coder-1 · started): coder-1 sent stop, not started — its transcript: "
            failure.message!! shouldContain Regex(""" — and its record could not be written: \S*AccessDeniedException: ${Regex.escape(rehearsal.scratch.toString())}/thin-thread-""")
            rehearsal.scratch.listDirectoryEntries() shouldBe emptyList()
        }
    }
})

/** A coder that sends `stop` where the thin thread waits for its `started`: the play fails at step 5. */
private const val STOPS = """{"turns": [[{"do": "mcp", "tool": "send", "arguments": {"kind": "stop", "subject": "a stop", "args": {"sc": "SC-10"}}}, {"do": "end"}]]}"""

/** A copy of the checkout's rules, robots, pool and personas whose `pool/offices.yaml` has its first [old] made [new]. */
private fun poolWith(old: String, new: String): Path = TestOffice.copyOfTheRules().also { rules ->
    val offices = rules.resolve("pool/offices.yaml")
    offices.readText() shouldContain old
    offices.writeText(offices.readText().replaceFirst(old, new))
}

/** A copy of the checkout's rules, robots, pool and personas whose `pool/offices.yaml` is [change]d. */
private fun poolWith(change: (String) -> String): Path = TestOffice.copyOfTheRules().also { it.resolve("pool/offices.yaml").rewrite(change) }

/** The thin thread with fake seats, [coder] its coder's script, its evidence and record going to [scratch]. */
private class Rehearsing(coder: String) : AutoCloseable {
    val scratch: Path = Files.createTempDirectory("kp-s3.10-scratch-")
    val hero = Hero("thin-thread", fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = "kp-no-tmux-here", panes = "kp-s3.10",
        evidence = scratch, scripts = mapOf("coder-1" to coder)))

    /** The play's record: the one folder beside its evidence. */
    fun record(): Path = scratch.listDirectoryEntries("thin-thread-*.md").single().let { it.resolveSibling(it.name.removeSuffix(".md")) }

    override fun close() {
        hero.close()
        hero.project.deleteRecursively()
        scratch.toFile().setWritable(true)
        scratch.deleteRecursively()
    }
}
