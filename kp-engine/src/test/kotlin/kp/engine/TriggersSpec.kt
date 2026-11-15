package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival
import kp.rules.MessageClass

private fun Decision.fired(): List<String> = moves.map { "${it.rule} ${it.machine} ${it.subject}: ${it.from} → ${it.to}" }

private val passed = fold(log(move("stage", STAGE, "in_review", "passed"), *sent("m-panda-000010", "merge", from = "teamlead", to = "daemon")))
private val awaitingBind = fold(log(*sent("m-panda-000020", "review-request", from = "coder-1"), move("stage", STAGE, "building", "ready_for_review")))
private val gone = table("now" to NOW, "seat.process_gone(seat=coder-1)" to true, "seat.protocol_closed(seat=coder-1)" to false, "seat.tool_call_open_for(seat=coder-1)" to 0)

/** T4: candidates and triggers (contracts.md §4.1). */
class TriggersSpec : StringSpec({
    "on: op-result — the result of the operation the request started chooses among the request's transitions" {
        val retried = fold(log(move("stage", STAGE, "in_review", "passed", extra = ""","counted":{"merge_attempts":2}"""), *sent("m-panda-000010", "merge", from = "teamlead", to = "daemon")))
        val engine = supervised()

        engine.judge(passed, Trigger.Result("m-panda-000010", "git.merge", ok = true), table("now" to NOW)).fired() shouldBe listOf("T-36 stage $STAGE: passed → merged")
        engine.judge(passed, Trigger.Result("m-panda-000010", "git.merge", ok = false), table("now" to NOW)).fired() shouldBe listOf("T-37 stage $STAGE: passed → passed")
        engine.judge(retried, Trigger.Result("m-panda-000010", "git.merge", ok = false), table("now" to NOW)).fired() shouldBe listOf("T-39 stage $STAGE: passed → tail_failed")
    }
    "on: expiry — the kind's deadline passed: its expiry transition fires, and its expiry effects follow with their if decided after it" {
        val once = fold(log(*sent("m-panda-000020", "review-request", from = "coder-1"), move("stage", STAGE, "building", "ready_for_review"),
            move("stage", STAGE, "ready_for_review", "ready_for_review", extra = ""","counted":{"review_request_expiries":1}""")))

        val first = supervised().judge(awaitingBind, Trigger.Expiry("m-panda-000020"), table("now" to NOW))
        val second = supervised().judge(once, Trigger.Expiry("m-panda-000020"), table("now" to NOW))

        first.fired() shouldBe listOf("T-74 stage $STAGE: ready_for_review → ready_for_review")
        first.effects shouldBe listOf(Requested.Emit("review-request", "nudge", "teamlead", mapOf("rule" to Datum.Text("T-74"), "failed_guard" to Datum.Text("nudge teamlead; 2nd expiry → Bora (C)"))))
        second.counted shouldBe mapOf("review_request_expiries" to 2L)
        second.effects shouldBe listOf(Requested.Report("review-request", "bora", MessageClass.C))
        second.waitsForBora shouldBe true
    }
    "on: expiry — a {transition: …, if: …} of the kind's expiry fires only while its if holds, even where the transition itself requires nothing" {
        val gateOnly = ruleSet(Edit("rules/stage-lifecycle.yaml", "  id: T-32\n  'on': expiry\n  require:\n  - fact: bora.present\n  effects: []", "  id: T-32\n  'on': expiry\n  require: []\n  effects: []"))
        val asked = fold(log(move("stage", STAGE, "re_review", "ruling_pending"), *sent("m-panda-000030", "ruling-request", from = "principal", to = "bora")))
        val absent = fold(log(move("stage", STAGE, "re_review", "ruling_pending"), *sent("m-panda-000030", "ruling-request", from = "principal", to = "bora"), move("presence", "bora", "present", "absent")))

        supervised().judge(asked, Trigger.Expiry("m-panda-000030"), table("now" to NOW)).fired() shouldBe listOf("T-32 stage $STAGE: ruling_pending → recut", "FLAG_THREAD-04 flag m-panda-000030: asked → expired")
        supervised().judge(absent, Trigger.Expiry("m-panda-000030"), table("now" to NOW)).outcome shouldBe Outcome.NOTHING
        supervised(gateOnly).judge(absent, Trigger.Expiry("m-panda-000030"), table("now" to NOW)).outcome shouldBe Outcome.NOTHING
    }
    "on: fact — a fact named in a transition's requirements changed; no sender is named" {
        val away = fold(log(move("presence", "bora", "present", "absent")))
        val engine = supervised()

        engine.judge(State(), Trigger.Changed("bora.idle_for"), table("now" to NOW, "bora.idle_for" to "5h")).fired() shouldBe listOf("PRESENCE-02 presence bora: present → absent")
        engine.judge(State(), Trigger.Changed("bora.idle_for"), table("now" to NOW, "bora.idle_for" to "3h")).outcome shouldBe Outcome.NOTHING
        engine.judge(away, Trigger.Changed("bora.acted"), table("now" to NOW, "bora.acted" to true)).fired() shouldBe listOf("PRESENCE-03 presence bora: absent → present")
    }
    "*active is any active state: a seat gone pauses a building stage, and leaves a planned one where it is" {
        val building = fold(log(move("stage", STAGE, "assigned", "building"), move("seat", "coder-1", "ready", "busy")))
        val planned = fold(log(move("seat", "coder-1", "ready", "busy")))
        val seatGone = Arrival(message("seat-gone", from = "pool", args = """{"seat": "coder-1"}"""))

        supervised().judge(building, seatGone, gone).fired() shouldBe listOf("T-59 stage $STAGE: building → paused", "SEAT-06 seat coder-1: busy → gone")
        supervised().judge(building, seatGone, gone).effects shouldBe listOf(Requested.Do("T-59", "seat.reseat", mapOf("seat" to Datum.Text("coder-1"))))
        supervised().judge(planned, seatGone, gone).fired() shouldBe listOf("SEAT-06 seat coder-1: busy → gone")
    }
    "*previous is the state the stage was in before paused" {
        val paused = fold(log(move("stage", STAGE, "in_review", "paused", extra = ""","counted":{"seat_gone_events":1}"""), move("seat", "reviewer-1", "busy", "gone")))
        val reseated = Arrival(message("re-seated", from = "pool", args = """{"seat": "reviewer-1"}"""))

        supervised().judge(paused, reseated, table("now" to NOW)).fired() shouldBe listOf("T-60 stage $STAGE: paused → in_review", "SEAT-07 seat reviewer-1: gone → ready")
    }
    "the five companion machines move by the same evaluation as the stage: found, flag, seat, phase, presence" {
        val engine = supervised()
        val stopped = fold(log(move("stage", STAGE, "building", "stopped")))
        val classifying = fold(log(move("stage", STAGE, "assigned", "in_review"), *sent("m-panda-000040", "classify-request", from = "reviewer-1", to = "bora")))
        val retro = fold(log(*sent("m-panda-000041", "retro-report", from = "retro", to = "principal", args = """{"mode": "phase-retro", "report": "r", "candidates": []}""")))
        val phaseExit = table("now" to NOW, "plan.last_stage_exited" to true, "plan.chart_fresh" to true, "status.entries" to 3)

        engine.judge(stopped, Arrival(message("triage", from = "principal", to = "coder", args = """{"outcome": "issue", "found": "F1", "since": "t0"}""")),
            table("now" to NOW, "list.sections_changed(since=t0)" to listOf("Found while doing"))).fired() shouldBe listOf("FOUND_ITEM-01 found $STAGE#F1: found → triaged")
        engine.judge(classifying, Arrival(message("ruling", from = "bora", to = "reviewer-1", thread = "m-panda-000040", re = "m-panda-000040", args = """{"outcome": "x"}""", signed = true)),
            table("now" to NOW)).fired() shouldBe listOf("FLAG_THREAD-01 flag m-panda-000040: asked → ruled")
        engine.judge(fold(log(move("stage", STAGE, "planned", "assigned"), move("seat", "coder-1", "provisioned", "ready"))), Arrival(message("assign", from = "teamlead", to = "coder", args = """{"seat": "coder-1"}""")),
            table("now" to NOW)).fired() shouldBe listOf("SEAT-03 seat coder-1: ready → busy")
        engine.judge(retro, Arrival(message("phase-exit-request", from = "teamlead", to = "bora", args = """{"attest": ["floors_checked"]}""")), phaseExit).fired() shouldBe
            listOf("PHASE-01 phase KP/P1: open → exit_pending")
        engine.judge(State(), Arrival(message("away", from = "bora", to = "mailroom", signed = true)), table("now" to NOW)).fired() shouldBe listOf("PRESENCE-01 presence bora: present → absent")
    }
})
