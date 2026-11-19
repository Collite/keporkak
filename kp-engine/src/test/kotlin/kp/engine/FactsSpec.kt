package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.engine.Need.Param.Known
import kp.engine.Need.Param.Of
import kp.engine.Trigger.Arrival

private val building = folded(log(move("stage", STAGE, "assigned", "building")))
private val review = message("review-request", from = "coder-1", args = """{"attest": ["done_lines_hold"]}""")

/** T3: who answers a fact (contracts.md §4.4). */
class FactsSpec : StringSpec({
    "before a judgement the engine names the facts it will read: those the table must supply, a parameter's fact as a fact of its own" {
        val needs = supervised().needs(building, Arrival(review))

        needs.map { it.name }.toSet() shouldBe setOf("now", "pr.ready", "pr.head", "ci.state", "list.tasks_open", "list.found_untriaged")
        needs.single { it.name == "ci.state" } shouldBe Need("ci.state", mapOf("of" to Of(Need("pr.head", emptyMap()))))
    }
    "a fact's parameters are resolved before it is named: an argument, a cap, a field of the message" {
        val stop = message("stop", from = "coder-1", to = "principal", args = """{"sc": "SC-3"}""")
        val qaReport = message("qa-report", from = "qa", to = "coder-1", args = """{"outcome": "pass", "fixture": "f", "command": "c", "counts": {}, "sha": "abc"}""")

        supervised().needs(building, Arrival(stop)).map { it.name } shouldBe listOf("now", "list.stop_conditions")
        supervised().needs(building, Arrival(qaReport)) shouldBe listOf(
            Need("now", emptyMap()), Need("fixture.exists", mapOf("pointer" to Known(Datum.Text("f")))), Need("live.namespace_absent", mapOf("stage" to Known(Datum.Text(STAGE)))),
            Need("stage.live", emptyMap()),
        )
    }
    "the mailroom collects what the engine names, a parameter's fact first" {
        val asked = ArrayList<String>()
        val world = mapOf<String, Any>("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "green", "list.tasks_open" to 0, "list.found_untriaged" to 0)

        val facts = Facts.collect(supervised().needs(building, Arrival(review))) { ref -> asked += ref.toString(); world[ref.toString()]?.let(Datum::of) }

        (asked.indexOf("pr.head") < asked.indexOf("ci.state(of=abc)")) shouldBe true
        facts[ref("ci.state(of=abc)")] shouldBe Datum.Text("green")
        supervised().judge(building, Arrival(review), facts).outcome shouldBe Outcome.DELIVER
    }
    "a fact whose source is exactly L is answered from the state, never from the table" {
        val assigned = folded(log(move("stage", STAGE, "planned", "assigned"), move("seat", "coder-1", "provisioned", "ready")))
        val assign = message("assign", from = "teamlead", to = "coder", args = """{"seat": "coder-1"}""")

        val decision = supervised().judge(assigned, Arrival(assign), table("now" to NOW, "stage.state" to "planned"))

        decision.outcome shouldBe Outcome.DELIVER
        decision.observed["stage.state"] shouldBe Datum.Text("assigned")
        supervised().needs(assigned, Arrival(assign)).map { it.name } shouldBe listOf("now")
    }
    "a fact the table does not supply is unknown: the requirement reading it fails, and the refusal says it was not supplied" {
        val decision = supervised().judge(building, Arrival(review), table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "list.tasks_open" to 0))

        decision should refusedBy("review-request", "{fact: ci.state, of: {fact: pr.head}, eq: green}")
        decision.unsupplied shouldBe listOf(ref("ci.state(of=abc)"))
        decision.observed["ci.state(of=abc)"] shouldBe UNKNOWN_FACT
    }
    "log.count counts a kind's deliveries in a scope, matching where, after the last since, within a window" {
        val stops = folded(log(
            *sent("m-panda-000001", "stop", from = "coder-1", to = "principal", args = """{"sc": "SC-3"}"""),
            *sent("m-panda-000002", "triage", from = "principal", to = "coder-1", args = """{"outcome": "resume"}"""),
            *sent("m-panda-000003", "stop", from = "coder-1", to = "principal", args = """{"sc": "SC-3"}"""),
            *sent("m-panda-000004", "stop", from = "coder-1", to = "principal", args = """{"sc": "SC-9"}""", stage = "KP/P1/S1.3"),
        ))
        requiring("{fact: log.count, kind: stop, scope: stage, where: {sc: SC-3}, eq: {cap: fix_rounds_autonomous}}").failure(state = stops) shouldBe null
        requiring("{fact: log.count, kind: stop, scope: phase, eq: {cap: max_fires_default}}").failure(state = stops) shouldBe null
        requiring("{fact: log.count, kind: stop, scope: stage, since: triage, eq: {cap: tail_retries}}").failure(state = stops) shouldBe null
        requiring("{fact: log.count, kind: stop, scope: stage, window: 1m, eq: 0}").failure(table("now" to "2026-11-15T10:03:00Z"), state = stops) shouldBe null
    }
    "request.open: a delivered request of that kind in the stage that no delivery answers" {
        val asked = folded(log(*sent("m-panda-000001", "deploy-request", from = "teamlead", to = "qa")))
        val answered = folded(log(*sent("m-panda-000001", "deploy-request", from = "teamlead", to = "qa"),
            *sent("m-panda-000002", "deploy-done", from = "qa", re = "m-panda-000001").let { arrayOf(it[0], it[1].replace("}", ""","answers":"m-panda-000001"}""")) }))
        val engine = requiring("{fact: request.open, kind: deploy-request}")

        engine.failure(state = asked) shouldBe null
        engine.failure(state = answered) shouldBe "{fact: request.open, kind: deploy-request}"
    }
    "review.open_blockers: the blocker ids of the stage's last delivered verdict" {
        val verdicts = folded(log(
            *sent("m-panda-000001", "verdict", from = "reviewer-1", to = "coder-1", args = """{"verdict": "FIX FIRST", "sha": "a", "blockers": [{"id": "B1"}, {"id": "B2"}]}"""),
            *sent("m-panda-000002", "re-verdict", from = "reviewer-1", to = "coder-1", args = """{"verdict": "FIX FIRST", "sha": "b", "blockers": [{"id": "B2"}], "reviewed": ["B1", "B2"]}"""),
        ))
        requiring("{fact: review.open_blockers, eq: {arg: items}}").failure(state = verdicts, args = """{"items": ["B2"]}""") shouldBe null
        requiring("{fact: review.open_blockers, eq: {arg: items}}").failure(state = verdicts, args = """{"items": ["B1", "B2"]}""") shouldBe "{fact: review.open_blockers, eq: {arg: items}}"
    }
    "the machines' states: stage.state, phase.state, seat.state; bora.present and bora.absent_since from the presence machine" {
        val state = folded(log(move("stage", "KP/P1/S1.3", "planned", "assigned"), move("phase", "KP/P1", "open", "exit_pending"), move("seat", "coder-1", "ready", "busy"),
            move("presence", "bora", "present", "absent")))
        requiring("{fact: stage.state, stage: KP/P1/S1.3, eq: assigned}", "{fact: phase.state, eq: exit_pending}", "{fact: seat.state, seat: coder-1, eq: busy}", "{fact: bora.present, eq: false}",
            "{fact: bora.absent_since, eq: '2026-11-14T09:03:00Z'}").failure(state = state) shouldBe null
        requiring("{fact: bora.present}").failure(state = State()) shouldBe null
    }
    "a thread's hops, its flag's state and whether it is open; a seat's refusals today; whether a seat's inbox was replayed" {
        val thread = folded(log(*sent("m-panda-000001", "classify-request", from = "reviewer-1", to = "bora"), move("flag", "m-panda-000001", "asked", "held"),
            """{"id":"m-panda-000002","thread":"m-panda-000002","from":"coder-1","to":"teamlead","kind":"started","stage":"$STAGE","subject":"s","created":"$NOW","by":"seat:coder-1"}""",
            """{"event":"refused","id":"m-panda-000002","rule":"started","requirement":"r","at":"2026-11-15T09:00:00Z"}"""))
        requiring("{fact: thread.hops, thread: m-panda-000001, eq: {cap: tail_retries}}", "{fact: thread.state, thread: m-panda-000001, eq: held}", "{fact: thread.open, thread: m-panda-000001}",
            "{fact: refusals.today, seat: coder-1, eq: {cap: tail_retries}}", "{fact: seat.inbox_replayed, seat: coder-1}").failure(state = thread) shouldBe null
        requiring("{fact: seat.inbox_replayed, seat: bora}").failure(state = thread) shouldBe "{fact: seat.inbox_replayed, seat: bora}"
    }
    "queue.names: the last delivered queue Bora signed names the stage; an unsigned one names none, and the wave is not read again" {
        fun queue(signed: Boolean) = folded(log(*sent("m-panda-000001", "queue-approval", from = "bora", to = "teamlead", args = """{"stages": ["$STAGE"]}""")
            .let { arrayOf(it[0].replace("\"by\":\"seat:bora\"", "\"by\":\"bora-cli\"" + if (signed) ",\"signed\":true" else ""), it[1]) }))
        requiring("{fact: queue.names}").failure(state = queue(signed = true)) shouldBe null
        requiring("{fact: queue.names}").failure(state = queue(signed = false)) shouldBe "{fact: queue.names}"
        requiring("{fact: queue.names}").failure(state = State()) shouldBe "{fact: queue.names}"
        supervised().needs(queue(signed = true), Trigger.Arrival(message("queued-approval", "deputy", by = "robot:DEP-2"))).map { it.name }.contains("plan.in_current_wave") shouldBe false
    }
})
