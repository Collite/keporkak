package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * KP/P1/S1.5 — `kp` and the service. One spec per behaviour the stage list names (T1), at the seam it gives: the control
 * socket of a daemon that rehearses `rules/walks/h1-prime.yaml` on a throwaway project, and `scripts/kp` run as a
 * subprocess. Whether a connection's peer is root is read through [SimulatedPeers]: `kp(…, root = true)` is `sudo kp …`,
 * simulated. Each rehearsal starts paused where the walk first needs Bora: the TeamLead's assignment-proposal waits for
 * his approval, under the ticket the `seq` of the line that opened it gives (contracts.md §1, §2) — read from the log, not
 * pinned to the walk's length (S3.0 T5).
 */
class KpAndTheServiceSpec : FreeSpec({
    "`kp inbox` lists what waits for Bora by ticket, with its class, kind, stage and the list's three lines" {
        Rehearsed("h1-prime").use { office ->
            val inbox = office.kp("inbox")

            inbox.code shouldBe 0
            inbox.text shouldBe """
                #${office.ticket(office.id("assignment-proposal"))} · B · assignment-proposal · REHEARSAL/P2/S2.1 · from teamlead
                   rolls: none · live: none · touches: unknown

            """.trimIndent()
        }
    }

    "a signed `approve` fires the transition; the same command unsigned is refused and logged; a ticket answered twice is refused" {
        Rehearsed("h1-prime").use { office ->
            val ticket = office.ticket(office.id("assignment-proposal"))
            val unsigned = office.kp("approve", "$ticket")
            val signed = office.kp("approve", "$ticket", root = true)
            office.rehearsal.awaitBora() // the walk goes on: `worktree.create` ends `ok` at its step (S1.6's harness), and the walk next waits for Bora's ruling
            val approvedAgain = office.kp("approve", "$ticket", root = true)
            val declinedAfter = office.kp("decline", "$ticket", root = true)

            listOf(unsigned.code, signed.code, approvedAgain.code, declinedAfter.code) shouldBe listOf(1, 0, 1, 1)
            office.log().messages("approval", "decline").map { "${it.kind} signed=${it.signed} ${office.log().outcome(it)}" } shouldBe listOf(
                "approval signed=false refused: signed by Bora's CLI",
                "approval signed=true delivered: T-02 proposed→assigned, T-03 assigned→assigned",
                "approval signed=true refused: max_fires: 1, window: stage",
                "decline signed=true refused: ticket #$ticket: answered once",
            )
        }
    }

    "only a signed command moves presence" {
        Rehearsed("h1-prime").use { office ->
            val before = office.kp("status").presence
            office.kp("away")
            val afterUnsigned = office.kp("status").presence
            office.kp("away", root = true)
            val afterSigned = office.kp("status").presence
            office.kp("inbox")
            val afterAnUnsignedRead = office.kp("status").presence

            listOf(before, afterUnsigned, afterSigned, afterAnUnsignedRead) shouldBe listOf("present", "present", "absent", "absent")
        }
    }

    "`kp check` on a hand log says in which state it leaves each stage, reports a repeated id and an `args` that breaks its kind's declaration, and lists what it could not check" {
        Rehearsed("h1-prime").use { office ->
            val hand = office.handLog("p9-s9.1.jsonl", """
                {"v":1,"seq":1,"at":"2026-11-17T09:00:00Z","id":"m-hand-000001","thread":"m-hand-000001","from":"teamlead","to":"bora","kind":"assignment-proposal","stage":"KP/P9/S9.1","subject":"a proposal","args":{"role":"coder","level":"senior","seat":"coder-1"},"created":"2026-11-17T09:00:00Z","by":"hand:teamlead"}
                {"v":1,"seq":2,"at":"2026-11-17T09:05:00Z","id":"m-hand-000002","thread":"m-hand-000002","from":"coder","to":"teamlead","kind":"started","stage":"KP/P9/S9.1","subject":"started","args":{"sc":"SC-1"},"created":"2026-11-17T09:05:00Z","by":"hand:coder"}
                {"v":1,"seq":3,"at":"2026-11-17T09:10:00Z","id":"m-hand-000002","thread":"m-hand-000002","from":"coder","to":"principal","kind":"stop","stage":"KP/P9/S9.1","subject":"stop","args":{"sc":"SC-2"},"created":"2026-11-17T09:10:00Z","by":"hand:coder"}
            """)

            val check = office.kp("check", hand.toString())

            check.text shouldContain "$hand:1 m-hand-000001 assignment-proposal: not checked — it reads plan.predecessors_exited, which a hand log does not record"
            check.text shouldContain "$hand:2 m-hand-000002 started: refused — args: 'sc' is not declared"
            check.text shouldContain "$hand:3 m-hand-000002: the id is used again — first on line 2"
            check.text shouldContain "KP/P9/S9.1 is left in planned"
            check.text shouldContain "not checked, a hand log records no observed fact: list.stop_conditions, plan.predecessors_exited"
        }
    }

    "`kp check` on a daemon's own log reproduces every recorded outcome, and prints the line where one differs" {
        Rehearsed("h1-prime").use { office ->
            val proposal = office.id("assignment-proposal")
            office.kp("approve", "${office.ticket(proposal)}", root = true)
            office.rehearsal.awaitBora()
            val copy = office.copyOfTheLog()

            val clean = office.kp("check", copy.toString())
            edit(copy, """"event":"delivered","id":"$proposal"""", """"list.fence_complete":true""", """"list.fence_complete":false""")
            val edited = office.kp("check", copy.toString())

            clean.code shouldBe 0
            clean.text shouldContain "every recorded outcome is reproduced"
            edited.code shouldBe 1
            edited.text shouldContain "$copy/REHEARSAL/p2-s2.1.jsonl:1 $proposal assignment-proposal: recorded delivered T-01"
            edited.text shouldContain "re-judged refused assignment-proposal"
        }
    }
})
