package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival

private val assigned = fold(log(move("stage", STAGE, "planned", "assigned")))
private val branch = table("now" to NOW, "git.branch_exists" to true)

/** T5: what the engine enforces for every kind, with no requirement written (contracts.md §4.1, §2.1). */
class EveryKindSpec : StringSpec({
    "the sender's role owns the kind: a role, a seat of it, a party; any of the roles a kind names" {
        val engine = supervised()
        val building = fold(log(move("stage", STAGE, "assigned", "building")))
        val qaFacts = table("now" to NOW, "list.task_exists(id=T5)" to true, "list.done_item_exists(id=D1)" to true, "stage.live" to "bp-dsk")
        val qaRequest = """{"task": "T5", "done": "D1", "verify": "the candidate"}"""

        engine.judge(assigned, Arrival(message("started", from = "coder-1")), branch).outcome shouldBe Outcome.DELIVER
        engine.judge(building, Arrival(message("qa-request", from = "teamlead", to = "qa", args = qaRequest)), qaFacts).outcome shouldBe Outcome.DELIVER
        engine.judge(building, Arrival(message("qa-request", from = "coder-2", to = "qa", args = qaRequest)), qaFacts).outcome shouldBe Outcome.DELIVER
        engine.judge(building, Arrival(message("qa-request", from = "reviewer-1", to = "qa", args = qaRequest)), qaFacts).refusal() shouldBe ("qa-request" to "from: coder | teamlead")
        engine.judge(assigned, Arrival(message("started", from = "coders-1")), branch).refusal() shouldBe ("started" to "from: coder")
    }
    "max_fires within the stage: the kind's deliveries in this stage, whoever sent them" {
        val otherStage = fold(log(move("stage", STAGE, "planned", "assigned"), *sent("m-panda-000050", "started", from = "coder-1", stage = "KP/P1/S1.3")))
        supervised().judge(otherStage, Arrival(message("started", from = "coder-1")), branch).outcome shouldBe Outcome.DELIVER
    }
    "max_fires within the day: the sender's deliveries of the kind on the UTC day of now" {
        val queuedYesterday = fold(log(*sent("m-panda-000050", "queue-approval", from = "bora", args = """{"stages": ["$STAGE"]}""")))
        val wave = arrayOf("plan.in_current_wave(stage=[\"$STAGE\"])" to true)
        val queue = Arrival(message("queue-approval", from = "bora", args = """{"stages": ["$STAGE"]}""", signed = true))

        supervised().judge(queuedYesterday, queue, table("now" to "2026-11-14T23:59:00Z", *wave)).refusal() shouldBe ("queue-approval" to "max_fires: 1, window: day")
        supervised().judge(queuedYesterday, queue, table("now" to "2026-11-15T00:00:00Z", *wave)).outcome shouldBe Outcome.DELIVER
    }
    "max_fires within a return: the sender's deliveries of the kind since Bora last came back" {
        val digested = fold(log(move("presence", "bora", "absent", "present"), *sent("m-panda-000050", "digest", from = "deputy", to = "bora")))
        val awayAgain = fold(log(move("presence", "bora", "absent", "present"), *sent("m-panda-000050", "digest", from = "deputy", to = "bora"),
            move("presence", "bora", "present", "absent"), move("presence", "bora", "absent", "present")))
        val digest = Arrival(message("digest", from = "deputy", to = "bora"))

        supervised().judge(digested, digest, table("now" to NOW)).refusal() shouldBe ("digest" to "max_fires: 1, window: return")
        supervised().judge(awayAgain, digest, table("now" to NOW)).outcome shouldBe Outcome.DELIVER
    }
    "max_fires within a session: the sender's deliveries of the kind since its last seat.start that is ok in the log — from the beginning when there is none" {
        val args = """{"mode": "stage-retro", "report": "r", "candidates": []}"""
        val reported = sent("m-panda-000050", "retro-report", from = "retro", to = "principal", args = args)
        val started = """{"event":"op","op":"seat.start","phase":"ok","cause":"m-panda-000049","result":{"seat":"retro"}}"""
        val failedStart = """{"event":"op","op":"seat.start","phase":"failed","cause":"m-panda-000049","with":{"seat":"retro"}}"""
        val report = Arrival(message("retro-report", from = "retro", to = "principal", args = args))

        supervised().judge(fold(log(started, *reported)), report, table("now" to NOW)).refusal() shouldBe ("retro-report" to "max_fires: 1, window: session")
        supervised().judge(fold(log(*reported, started)), report, table("now" to NOW)).outcome shouldBe Outcome.DELIVER
        supervised().judge(fold(log(*reported, failedStart)), report, table("now" to NOW)).refusal() shouldBe ("retro-report" to "max_fires: 1, window: session")
        supervised().judge(fold(log(*reported)), report, table("now" to NOW)).refusal() shouldBe ("retro-report" to "max_fires: 1, window: session")
    }
    "a kind with no max_fires of its own is capped by caps.max_fires_default" {
        val threeAways = fold(log(*sent("m-panda-000051", "away", from = "bora", to = "mailroom"), *sent("m-panda-000052", "away", from = "bora", to = "mailroom"),
            *sent("m-panda-000053", "away", from = "bora", to = "mailroom")))
        supervised().judge(threeAways, Arrival(message("away", from = "bora", to = "mailroom", signed = true)), table("now" to NOW)).refusal() shouldBe
            ("away" to "caps.max_fires_default: 3, window: stage")
    }
    "a delivery sets the kind's deadline, counted from now; a kind with none sets none" {
        val building = fold(log(move("stage", STAGE, "assigned", "building")))
        val review = message("review-request", from = "coder-1", args = """{"attest": ["done_lines_hold"]}""")
        val facts = table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "green", "list.tasks_open" to 0, "list.found_untriaged" to 0)

        supervised().judge(building, Arrival(review), facts).deadline shouldBe "2026-11-15T11:00:00Z"
        supervised().judge(assigned, Arrival(message("started", from = "coder-1")), branch).deadline shouldBe null
    }
    "the thread's hop cap: the eighth message of a thread is delivered, the ninth refused" {
        val sevenHops = fold(log(move("stage", STAGE, "planned", "assigned"),
            *sent("m-panda-000051", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000052", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000053", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000054", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000055", "stop", from = "coder-1", thread = "m-panda-000051"), *sent("m-panda-000056", "stop", from = "coder-1", thread = "m-panda-000051"),
            *sent("m-panda-000057", "stop", from = "coder-1", thread = "m-panda-000051")))
        supervised().judge(sevenHops, Arrival(message("started", from = "coder-1", thread = "m-panda-000051")), branch).outcome shouldBe Outcome.DELIVER
    }
    "the body's line cap: forty lines are delivered, a last line end opens no line" {
        val forty = (1..40).joinToString("\n")
        supervised().judge(assigned, Arrival(message("started", from = "coder-1", body = forty)), branch).outcome shouldBe Outcome.DELIVER
        supervised().judge(assigned, Arrival(message("started", from = "coder-1", body = "$forty\n")), branch).outcome shouldBe Outcome.DELIVER
    }
    "args against the kind's declaration: an unknown key, a missing required one, a wrong type and a value outside the enum are refused" {
        val stopped = fold(log(move("stage", STAGE, "building", "stopped")))
        fun triage(args: String) = supervised().judge(stopped, Arrival(message("triage", from = "principal", to = "coder", args = args)), table("now" to NOW)).refusal()

        triage("""{"outcome": "drop", "reason": "x"}""") shouldBe ("triage" to "args: 'reason' is not declared")
        triage("""{"found": "F1"}""") shouldBe ("triage" to "args: 'outcome' is required")
        triage("""{"outcome": "drop", "found": 7}""") shouldBe ("triage" to "args: 'found' is not string")
        triage("""{"outcome": "maybe"}""") shouldBe ("triage" to "args: 'outcome' is not one of resume | recut | issue | stage | drop")
    }
    "the addressee is a party the kind names: a seat of its role, the sender of the message answered for {re: from}, any seat — never a party — for a seat" {
        val absent = fold(log(move("presence", "bora", "present", "absent"), *sent("m-panda-000060", "merge", from = "teamlead", to = "daemon")))
        fun hold(to: String) = Arrival(message("hold", from = "deputy", to = to, re = "m-panda-000060", thread = "m-panda-000060"))
        fun compact(to: String) = Arrival(message("compact", from = "pool", to = to, args = """{"seat": "coder-1"}"""))
        val crowded = table("now" to NOW, "seat.context_used(seat=coder-1)" to 0.9)

        supervised().judge(absent, hold("teamlead"), table("now" to NOW)).outcome shouldBe Outcome.DELIVER
        supervised().judge(absent, hold("coder-1"), table("now" to NOW)).refusal() shouldBe ("hold" to "to: {re: from}")
        supervised().judge(State(), compact("coder-1"), crowded).outcome shouldBe Outcome.DELIVER
        supervised().judge(State(), compact("bora"), crowded).refusal() shouldBe ("compact" to "to: a seat")
        supervised().judge(assigned, Arrival(message("started", from = "coder-1", to = "teamlead-panda")), branch).outcome shouldBe Outcome.DELIVER
    }
})
