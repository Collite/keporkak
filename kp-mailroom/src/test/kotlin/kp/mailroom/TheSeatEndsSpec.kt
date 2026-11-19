package kp.mailroom

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.model.Refused
import kp.model.Transition
import kotlin.time.Duration.Companion.hours

/** The H-1′ steps that take the stage from `planned` to `exited`, as `TheMailroomSpec` plays them; [reseated] adds the coder's death and return. */
private fun toTheExit(reseated: Boolean) = listOf("assignment-proposal", "approval", "assign", "started", "stop", "triage") +
    (if (reseated) listOf("seat-gone", "re-seated") else emptyList()) +
    listOf("review-request", "review-bind", "verdict", "repair-done", "re-verdict", "merge", "stage-exit")

/**
 * KP/P2/S2.7 — the seat ends of the engine and the mailroom. One spec per sentence T1 names for the engine and the
 * mailroom, observed at the seam S1.3 gives: a message submitted in a sender's name through the mailroom's one way in,
 * with facts, operations and seats faked, master's rules, the log in a throwaway project (`Office`). The sentences of
 * the rehearsal and `kp` are `kp-daemon`'s (`TheSeatEndsSpec` there); the builder's is `kp-driver`'s.
 */
class TheSeatEndsSpec : FreeSpec({
    afterTest { Office.checkAndCloseAll() }

    "at a stage's exit every seat bound to the stage goes idle, the reviewer's too" - {
        /**
         * An office whose reviewer's seat is started for the stage — the TeamLead's session-request, the Pool Manager's
         * session-report, `seat.start` and `token.mint` ending `ok` — so that it is `ready` and bound to the stage, as
         * S2.4's seats are; then H-1′ to the exit. The coder's seat is `ready` before, as every walk's is.
         */
        fun exited(reseated: Boolean): Office = Office(seats = listOf("coder-1")).apply {
            facts.set("roster.model_allowed", true)
            facts.set("seat.process_gone", true)
            val request = send("teamlead", """{"kind": "session-request", "stage": "$STAGE", "subject": "a reviewer", "args": {"action": "add", "role": "reviewer", "seat": "reviewer-1"}}""")
            send("pool-1", """{"kind": "session-report", "re": "${request.id}", "subject": "reviewer-1", "args": {"seat": "reviewer-1", "role": "reviewer"}}""")
            mailroom.state.machine("seat", "reviewer-1")?.state shouldBe "ready"
            play(toTheExit(reseated)).last() shouldBe "exited"
        }

        fun idle(office: Office): List<String> = office.readBack().records<Transition>().filter { it.machine == "seat" && it.to == "idle" }.map { "${it.subject} ${it.from}→idle" }

        "the coder's seat, busy, and the reviewer's, ready" {
            idle(exited(reseated = false)) shouldContainExactlyInAnyOrder listOf("coder-1 busy→idle", "reviewer-1 ready→idle")
        }
        "a coder's seat that was re-seated during the stage, ready, and the reviewer's" {
            idle(exited(reseated = true)) shouldContainExactlyInAnyOrder listOf("coder-1 ready→idle", "reviewer-1 ready→idle")
        }
    }

    "a refusal of Bora's own message, or of a robot's, is counted for no one" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        val digest = "  - send: digest\n    to: bora\n    args: {}\n"
        office.texts.edit("robots/deputy.yaml", digest, digest + digest) // DEP-8's second digest of a return is refused
        office.restart()

        repeat(3) { office.bora("""{"kind": "decline", "stage": "$STAGE"}""", signed = false) }
        repeat(2) {
            office.bora("""{"kind": "away"}""")
            office.clock.advance(1.hours)
            office.command(signed = true)
        }
        office.send("coder-1", """{"kind": "started", "stage": "$STAGE", "args": {"colour": "blue"}}""")

        office.readBack().records<Refused>().take(3).forEach { it should refusedBy("decline", "{signed: bora}") }
        office.readBack().records<Refused>().slice(3..4).forEach { it should refusedBy("digest", "max_fires: 1, window: return") }
        office.readBack().records<Refused>().last() should refusedBy("started", "args: 'colour' is not declared")
        val day = office.clock.now.toString().substring(0, 10)
        office.mailroom.state.refusals.keys shouldBe setOf("coder-1")
        listOf("bora", "deputy", "coder-1").map { office.mailroom.state.refusalsOn(it, day) } shouldBe listOf(0L, 0L, 1L)
    }
})
