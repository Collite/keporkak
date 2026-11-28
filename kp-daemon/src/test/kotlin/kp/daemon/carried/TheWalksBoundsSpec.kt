package kp.daemon.carried

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.daemon.OnTheOffice
import java.time.Duration

/**
 * S3.5 T1 — the walk's two bounds on the office (`Player`, `SeatsAtPlay`; contracts.md §12, Amendments 27, 46): silence past
 * the walk's `wait`, and the seats' usage past its `tokens`, each end the rehearsal as failed, naming the step, with the
 * stage left where the office's log has it — nothing is taken back.
 */
class TheWalksBoundsSpec : StringSpec({
    "silence past the walk's wait ends it as failed, naming the step, with the stage left where the log has it" {
        // The coder, assigned, says nothing: each of its turns ends at once.
        Carried(coder = """{"turns": [[{"do": "end"}]], "loaded_turns": [[{"do": "end"}]]}""").use { carried ->
            val rehearsal = carried.start()
            val end = System.nanoTime() + 120_000_000_000
            while (rehearsal.playing?.awaiting != "coder-1") {
                check(System.nanoTime() < end && rehearsal.passed == null) { "the walk did not wait for coder-1:\n" + carried.said.joinToString("\n") }
                Thread.sleep(200)
            }
            carried.clock.pass(Duration.ofMinutes(60).plusSeconds(1))
            rehearsal.office.changes.signal()
            rehearsal.awaitPlayed(60_000) shouldBe true
            rehearsal.passed shouldBe false
            // The failure points at the record, beside the evidence (Amendment 38).
            carried.said.joinToString("\n") shouldContain Regex(Regex.escape("the walk stops here: walk first-carried-stage step 13 (started · coder-1 · started): coder-1 sent nothing in 60m — its transcript: " +
                "${carried.evidence}/first-carried-stage-") + "[0-9TZ]+/transcripts/coder-1.log")
            rehearsal.close()
            kp.engine.fold(OnTheOffice.lines(carried.office.project, FIXTURE_OFFICE)).stage(CARRIED) shouldBe "assigned"
        }
    }

    "usage past the walk's tokens ends it as failed, naming the step, with the stage left where the log has it" {
        // The reviewer's turn uses more than the walk's 2 000 000 alone.
        val office = FixtureOffice()
        Carried(office, reviewer = FakeScripts.reviewer(office, office.folder.resolve("markers").also { it.toFile().mkdirs() }, usage = 3_000_000)).use { carried ->
            carried.play() shouldBe false
            val told = carried.said.joinToString("\n")
            told shouldContain Regex("the walk stops here: walk first-carried-stage step [0-9]+ \\([^)]+\\)(, at the walk's end)?: the seats have used [0-9]+ tokens, more than the walk's 2000000 \\(")
            told shouldContain "the last turn was reviewer-1's — its transcript: "
            val state = kp.engine.fold(OnTheOffice.lines(carried.office.project, FIXTURE_OFFICE)).stage(CARRIED)
            (state in setOf("passed", "merged", "exited")) shouldBe true
        }
    }
})
