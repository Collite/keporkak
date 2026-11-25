package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

/** The kind `retire`'s requirement on the seat and its stage, in flow style: what fails for a seat it may not reach. */
private const val RETIRE = "{any: [{fact: seat.state, seat: {arg: seat}, eq: idle}, {not: {any: [{fact: seat.state, seat: {arg: seat}, eq: busy}, " +
    "{fact: seat.state, seat: {arg: seat}, eq: retired}, {fact: stage.state, in: [assigned, building, stopped, candidate_deployed, ready_for_review, " +
    "in_review, fix_round, re_review, grant_pending, fix_round_3, ruling_pending, fix_round_4, passed, merged, released, paused]}]}}]}"

/**
 * KP/P3/S3.4 T1 — what `retire` may reach (D-118, 2): an `idle` seat in any stage; a `ready`, `provisioned` or `gone`
 * seat when its stage is neither active nor paused (SEAT-44, SEAT-45, SEAT-46) — no re-seat can be in flight then. The
 * seam: the engine's judgement of the Pool Manager's `retire`, on a log that moved the seat and its stage, with a slot
 * over its limit; and its `react` on a retired seat's end report.
 */
class WhatMayBeRetiredSpec : StringSpec({
    val engine = supervised()
    val seat = "coder-1"

    fun state(seatState: String, stageState: String): State {
        val stage = if (stageState == "planned") emptyList() else listOf(
            """{"event":"transition","machine":"stage","subject":"$STAGE","from":"planned","to":"$stageState","rule":"T-01","cause":"m-panda-000001"}""",
        )
        val moved = if (seatState == "provisioned") emptyList() else listOf(
            """{"event":"transition","machine":"seat","subject":"$seat","from":"provisioned","to":"$seatState","rule":"SEAT-01","cause":"m-panda-000002"}""",
        )
        return folded(after(State(), *(stage + moved).toTypedArray()))
    }

    val slotOver = table("now" to NOW, "budget.slots_over(dimension=office)" to 1, "budget.slots_over(dimension=subscription)" to 0,
        "budget.slots_over(dimension=effort)" to 0)

    /** `retired by SEAT-n`, or `refused by retire` — the kind, its requirement on what the seat and its stage are failing. */
    fun retire(seatState: String, stageState: String): String {
        val retire = message("retire", "pool", """{"seat":"$seat"}""", to = seat, by = "robot:POOL-8")
        val decision = engine.judge(state(seatState, stageState), Trigger.Arrival(retire), slotOver)
        return decision.moves.singleOrNull { it.machine == "seat" }?.let { "${it.to} by ${it.rule}" } ?: "refused by retire".also { decision should refusedBy("retire", RETIRE) }
    }

    "a seat idle, ready, provisioned or gone, its stage planned, recut, closed, active or paused: retired, or refused naming the kind" {
        val stages = listOf("planned", "recut", "closed", "building", "paused")
        listOf("idle", "ready", "provisioned", "gone").associateWith { seatState -> stages.associateWith { retire(seatState, it) } } shouldBe mapOf(
            "idle" to stages.associateWith { "retired by SEAT-11" },
            "ready" to mapOf("planned" to "retired by SEAT-44", "recut" to "retired by SEAT-44", "closed" to "retired by SEAT-44",
                "building" to "refused by retire", "paused" to "refused by retire"),
            "provisioned" to mapOf("planned" to "retired by SEAT-45", "recut" to "retired by SEAT-45", "closed" to "retired by SEAT-45",
                "building" to "refused by retire", "paused" to "refused by retire"),
            "gone" to mapOf("planned" to "retired by SEAT-46", "recut" to "retired by SEAT-46", "closed" to "retired by SEAT-46",
                "building" to "refused by retire", "paused" to "refused by retire"),
        )
    }

    "the end reports of a retired seat send nothing" {
        val retired = state("retired", "recut")
        val facts = table("now" to NOW, "seat.process_gone(seat=$seat)" to true, "seat.protocol_closed(seat=$seat)" to true,
            "seat.tool_call_open_for(seat=$seat)" to 0)
        listOf("seat.process_gone", "seat.protocol_closed").map { fact ->
            engine.react(retired, Trigger.Changed(fact, mapOf("seat" to Datum.Text(seat)), STAGE), facts).sends
        } shouldBe listOf(emptyList(), emptyList())
    }
})
