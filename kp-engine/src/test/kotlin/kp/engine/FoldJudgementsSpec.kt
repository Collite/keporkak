package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Envelope
import kp.model.parseLine

private fun line(seq: Int, text: String): Envelope = parseLine("""{"v":1,"seq":$seq,"at":"2026-11-17T10:00:00Z",""" + text.removePrefix("{"))
private fun moved(seq: Int, to: String, from: String = "planned") =
    line(seq, """{"event":"transition","machine":"stage","subject":"KP/P1/S1.10","from":"$from","to":"$to","cause":"m-panda-000001"}""")
private fun judged(seq: Int, first: Int) = line(seq, """{"event":"judged","first":$first}""")
private fun voided(seq: Int, first: Int, last: Int) = line(seq, """{"event":"voided","first":$first,"last":$last}""")
private fun hand(seq: Int) = line(seq, """{"id":"m-hand-00000$seq","thread":"m-hand-00000$seq","from":"teamlead","to":"coder","kind":"assign","stage":"KP/P1/S1.10","subject":"assign","created":"2026-11-17T10:00:00Z","by":"hand:teamlead"}""")

/** T4 — the fold applies whole judgements (contracts.md §2.2, §3, Amendment 23). */
class FoldJudgementsSpec : StringSpec({
    "a judgement's lines are applied when its judged line is read, not before" {
        val open = fold(listOf(moved(1, "proposed"), moved(2, "assigned", from = "proposed")))

        open.stage("KP/P1/S1.10") shouldBe null
        fold(listOf(judged(3, 1)), open).stage("KP/P1/S1.10") shouldBe "assigned"
    }

    "a voided judgement leaves its first line and nothing else; the judgement the voided record opens carries it on" {
        val log = listOf(judged(1, 1), moved(2, "proposed"), moved(3, "assigned", from = "proposed"), voided(4, 2, 3))

        fold(log + judged(5, 4)).stage("KP/P1/S1.10") shouldBe "proposed"
        fold(log + moved(5, "building", from = "proposed") + judged(6, 4)).stage("KP/P1/S1.10") shouldBe "building"
    }

    "a judgement voided twice — its carrying again cut too — still leaves the first line of the first" {
        val log = listOf(judged(1, 1), moved(2, "proposed"), voided(3, 2, 2), moved(4, "assigned", from = "proposed"), voided(5, 3, 4), judged(6, 5))

        fold(log).stage("KP/P1/S1.10") shouldBe "proposed"
    }

    "a voided judgement whose first line was lost leaves nothing" {
        val log = listOf(judged(1, 1), moved(3, "assigned", from = "proposed"), voided(4, 2, 3), judged(5, 4))

        fold(log).stage("KP/P1/S1.10") shouldBe null
    }

    "a hand log's lines — no judged, no voided — are each a judgement of their own" {
        fold(listOf(hand(1), hand(2))).pending.keys shouldBe setOf("m-hand-000001", "m-hand-000002")
    }

    "settled, the open judgement's lines are applied as written: what its writer reads while it writes" {
        val open = fold(listOf(judged(1, 1), moved(2, "proposed")))

        open.settled().stage("KP/P1/S1.10") shouldBe "proposed"
        fold(listOf(judged(3, 2)), open) shouldBe fold(listOf(judged(3, 2)), open.settled())
    }
})
