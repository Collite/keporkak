package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P1/S1.9 — Before the heroes. One spec per behaviour the stage list names (T1), at the seam it gives: the daemon as
 * S1.5 starts it in rehearsal — the robots among it — with `scripts/kp` over its socket; `kp(…, root = true)` is
 * `sudo kp …`, simulated. The outcome is read from the log and from `kp`. `h1-prime` starts paused at Bora's approval of
 * the TeamLead's proposal (#5); `queued-proposal` and `held-then-refused` are this module's walks (`src/test/resources/walks/`).
 */
class BeforeTheHeroesSpec : FreeSpec({
    "after `away`, Bora's next signed command — a read too — brings presence back, and the Deputy's digest follows" {
        Rehearsed("h1-prime").use { office ->
            office.kp("away", root = true)
            val whileAway = office.kp("status").presence
            office.kp("inbox", root = true)

            whileAway shouldBe "absent"
            office.kp("status").presence shouldBe "present"
            office.log().story() shouldBe listOf(
                "assignment-proposal delivered",
                "away delivered", "presence present→absent",
                "fact bora.acted", "presence absent→present", "digest by robot:DEP-8 delivered",
            )
        }
    }

    "a proposal the Deputy approves from the queue leaves no open ticket and no wait" {
        Rehearsed("queued-proposal").use { office ->
            office.kp("queue", "REHEARSAL/P2/S2.1", root = true)
            office.rehearsal.awaitBora() shouldBe "away"
            office.kp("away", root = true)
            office.rehearsal.awaitBora() shouldBe null // the TeamLead's proposal has arrived: the walk is done
            office.pass(Duration.ofHours(25))

            office.log().story() shouldBe listOf(
                "queue-approval delivered", "away delivered", "presence present→absent",
                "assignment-proposal delivered", "queued-approval by robot:DEP-2 delivered",
            )
            office.kp("inbox").text shouldBe "nothing waits for Bora\n"
        }
    }

    "a held message that is refused when it is let go reaches its sender as a nudge naming the rule" {
        Rehearsed("held-then-refused").use { office ->
            office.kp("away", root = true)
            office.rehearsal.awaitBora() shouldBe null // the Deputy holds the proposal; then the list loses its fence
            office.kp("inbox", root = true)
            val proposal = office.log().messages("assignment-proposal").single()
            val nudge = office.log().messages("nudge").single()

            office.log().story().takeLast(4) shouldBe listOf(
                "presence absent→present", "digest by robot:DEP-8 delivered", "assignment-proposal refused", "nudge by mailroom delivered",
            )
            listOf(nudge.from.value, nudge.to.value, nudge.re?.value, nudge.by) shouldBe listOf("mailroom", "teamlead", proposal.id.value, "mailroom")
            nudge.plainArgs shouldBe mapOf(
                "rule" to "assignment-proposal",
                "failed_guard" to "R4.8 predecessors exited · seat free · slots open · fence has SC ids + touches: (all observed)",
            )
            office.rehearsal.office.state.unread("teamlead") shouldContain nudge.id.value
        }
    }

    "a refusal of Bora's own message, or of a robot's, pauses no one" {
        Rehearsed("h1-prime", root = deputyWhoSendsTwoDigests()).use { office ->
            repeat(3) { office.kp("decline", "7") } // unsigned: refused, three times in a day
            repeat(3) {
                office.kp("away", root = true)
                office.clock.pass(Duration.ofHours(1))
                office.kp("status", root = true) // back an hour later: the Deputy's second digest of this return is refused
            }

            office.log().story().count { it == "decline refused" } shouldBe 3
            office.log().story().count { it == "digest by robot:DEP-8 refused" } shouldBe 3
            office.log().messages("pause").shouldBeEmpty()
        }
    }

    "`kp check` over a hand log that starts in the middle of a stage reports its slips and nothing else" {
        Rehearsed("h1-prime").use { office ->
            val hand = office.handLog("p9-s9.2.jsonl", """
                {"v":1,"seq":1,"at":"2026-11-17T09:00:00Z","id":"m-hand-000001","thread":"m-hand-000001","from":"coder","to":"reviewer","kind":"repair-done","stage":"KP/P9/S9.2","subject":"repair-done","args":{"verdict":"m-hand-000000"},"created":"2026-11-17T09:00:00Z","by":"hand:coder"}
                {"v":1,"seq":2,"at":"2026-11-17T09:05:00Z","id":"m-hand-000002","thread":"m-hand-000002","from":"coder","to":"principal","kind":"started","stage":"KP/P9/S9.2","subject":"started","created":"2026-11-17T09:05:00Z","by":"hand:coder"}
            """)

            val check = office.kp("check", hand.toString())

            check.code shouldBe 1
            check.text.lines().filter { "refused" in it } shouldBe listOf("$hand:2 m-hand-000002 started: refused — to: teamlead")
            check.text shouldContain "$hand: starts in the middle of KP/P9/S9.2: the state it was in before line 1 is not known"
            check.text shouldContain "$hand:1 m-hand-000001 repair-done: not checked — the state of KP/P9/S9.2 is not known (fix_round|fix_round_3|fix_round_4→re_review)"
            check.text shouldContain "KP/P9/S9.2 is left in a state the log does not show"
        }
    }
})

/** Master's rules, but the Deputy's DEP-8 sends its digest twice: the second of a return is refused (`max_fires: 1, window: return`). */
private fun deputyWhoSendsTwoDigests(): Path = TestOffice.copyOfTheRules().also { copy ->
    val deputy = copy.resolve("robots/deputy.yaml")
    val digest = "  - send: digest\n    to: bora\n    args: {}\n"
    check(digest in deputy.readText()) { "DEP-8 no longer sends its digest as this spec expects" }
    deputy.writeText(deputy.readText().replace(digest, digest + digest))
}
