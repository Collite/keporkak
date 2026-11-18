package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** A kind of the fixture's own, which coder, pool and bora may send, with arguments of every type. */
private const val PROBE = """  probe:
    from: coder | pool | bora
    to: teamlead
    guard: '-'
    class: {default: A, cases: []}
    max_fires: null
    deadline: null
    args: {attest: {type: array}, name: {type: string}, items: {type: array}, seat: {type: string}}
    window: stage
    require: []
    expiry: []
"""

/** Master's rule set with one more kind, `probe`, and one more transition, T-90 (planned → exited on a probe), that requires [requirements]. */
fun requiring(vararg requirements: String): Engine = supervised(ruleSet(
    Edit("rules/message-kinds.yaml", "kinds:\n", "kinds:\n$PROBE"),
    Edit("rules/stage-lifecycle.yaml", "\nactive_states:",
        "\n- {id: T-90, from: planned, to: exited, kind: probe, 'on': message, guard: the probe, require: [${requirements.joinToString(", ")}], effects: []}\nactive_states:"),
))

/** A probe from [from] with [args], judged on [state] with [facts]: what failed, or null when T-90 fired. */
fun Engine.failure(facts: Facts = table("now" to NOW), args: String = "{}", state: State = State(), from: String = "coder-1", re: String? = null, signed: Boolean = false): String? {
    val decision = judge(state, Trigger.Arrival(message("probe", from = from, args = args, re = re, signed = signed)), facts)
    return if (decision.outcome == Outcome.DELIVER && decision.rule == "T-90") null else decision.failed?.flow()
}

/** T2: every requirement form of contracts.md §4.1 — each holds, and each fails and is the one reported. */
class RequirementFormsSpec : StringSpec({
    "{fact: …, <params>, <op>: …} compares the fact asked with those parameters" {
        val engine = requiring("{fact: ci.state, of: {fact: pr.head}, eq: green}")
        engine.failure(table("now" to NOW, "pr.head" to "abc", "ci.state(of=abc)" to "green")) shouldBe null
        engine.failure(table("now" to NOW, "pr.head" to "abc", "ci.state(of=abc)" to "red")) shouldBe "{fact: ci.state, of: {fact: pr.head}, eq: green}"
    }
    "{fact: …} with no comparison holds when the fact is true" {
        val engine = requiring("{fact: pr.ready}")
        engine.failure(table("now" to NOW, "pr.ready" to true)) shouldBe null
        engine.failure(table("now" to NOW, "pr.ready" to false)) shouldBe "{fact: pr.ready}"
    }
    "a set-valued fact: in means every member is in the value" {
        val engine = requiring("{fact: list.sections_changed, since: {arg: name}, in: [Found while doing, Retro notes]}")
        engine.failure(table("now" to NOW, "list.sections_changed(since=t0)" to listOf("Retro notes", "Found while doing")), args = """{"name": "t0"}""") shouldBe null
        engine.failure(table("now" to NOW, "list.sections_changed(since=t0)" to listOf("Found while doing", "Tasks")), args = """{"name": "t0"}""") shouldBe
            "{fact: list.sections_changed, since: {arg: name}, in: [Found while doing, Retro notes]}"
    }
    "a set-valued fact: eq means the same set, in any order" {
        val engine = requiring("{fact: pr.blocker_responses, ids: {arg: items}, eq: {arg: items}}")
        engine.failure(table("now" to NOW, """pr.blocker_responses(ids=["B1","B2"])""" to listOf("B2", "B1")), args = """{"items": ["B1", "B2"]}""") shouldBe null
        engine.failure(table("now" to NOW, """pr.blocker_responses(ids=["B1","B2"])""" to listOf("B1")), args = """{"items": ["B1", "B2"]}""") shouldBe
            "{fact: pr.blocker_responses, ids: {arg: items}, eq: {arg: items}}"
    }
    "{arg: …, <op>: …} compares the message's argument" {
        val engine = requiring("{arg: name, ne: recut}")
        engine.failure(args = """{"name": "resume"}""") shouldBe null
        engine.failure(args = """{"name": "recut"}""") shouldBe "{arg: name, ne: recut}"
    }
    "a bare {arg: …} holds when the argument is present and not empty" {
        val engine = requiring("{arg: items}")
        engine.failure(args = """{"items": ["B1"]}""") shouldBe null
        engine.failure(args = """{"items": []}""") shouldBe "{arg: items}"
        engine.failure(args = "{}") shouldBe "{arg: items}"
    }
    "{msg: …} compares a field of the message itself: by, from, to, stage" {
        val engine = requiring("{msg: from, eq: coder-1}", "{msg: by, eq: 'seat:coder-1'}", "{msg: to, eq: teamlead}", "{msg: stage, eq: $STAGE}")
        engine.failure(from = "coder-1") shouldBe null
        engine.failure(from = "coder-2") shouldBe "{msg: from, eq: coder-1}"
    }
    "{re: …} compares a field of the message this one replies to: kind, class, from, to" {
        val engine = requiring("{re: kind, eq: stop}", "{re: class, eq: C}", "{re: from, eq: coder-1}", "{re: to, eq: principal}")
        val stopped = folded(log(*sent("m-panda-000090", "stop", from = "coder-1", to = "principal").let { arrayOf(it[0], it[1].replace("\"A\"", "\"C\"")) }))
        engine.failure(state = stopped, re = "m-panda-000090") shouldBe null
        engine.failure(state = stopped) shouldBe "{re: kind, eq: stop}"
    }
    "{counter: …} compares the stage's counter" {
        val engine = requiring("{counter: resumes, lt: {cap: resumes_per_stage}}")
        engine.failure(state = folded(log(move("stage", STAGE, "planned", "planned", extra = ""","counted":{"resumes":1}""")))) shouldBe null
        engine.failure(state = folded(log(move("stage", STAGE, "planned", "planned", extra = ""","counted":{"resumes":2}""")))) shouldBe "{counter: resumes, lt: {cap: resumes_per_stage}}"
    }
    "{attest: …} holds when the sender listed it in args.attest, and the decision says it was attested" {
        val engine = requiring("{attest: done_lines_hold}")
        engine.failure(args = """{"attest": ["done_lines_hold"]}""") shouldBe null
        engine.failure(args = """{"attest": ["floors_checked"]}""") shouldBe "{attest: done_lines_hold}"
        engine.judge(State(), Trigger.Arrival(message("probe", from = "coder-1", args = """{"attest": ["done_lines_hold"]}""")), table("now" to NOW)).attested shouldBe listOf("done_lines_hold")
    }
    "{signed: bora} holds for Bora's message over a root peer, and for nothing else" {
        val engine = requiring("{signed: bora}")
        engine.failure(from = "bora", signed = true) shouldBe null
        engine.failure(from = "bora", signed = false) shouldBe "{signed: bora}"
        engine.failure(from = "coder-1", signed = true) shouldBe "{signed: bora}"
    }
    "{op: …, eq: ok | failed} reads the result of the operation the request started" {
        val assigned = folded(log(move("stage", STAGE, "proposed", "assigned"), *sent("m-panda-000091", "approval", from = "bora")))
        val engine = supervised()
        engine.judge(assigned, Trigger.Result("m-panda-000091", "worktree.create", ok = true), table("now" to NOW)).moves.map { it.rule } shouldBe listOf("T-03")
        engine.judge(assigned, Trigger.Result("m-panda-000091", "worktree.create", ok = false), table("now" to NOW)).outcome shouldBe Outcome.NOTHING
    }
    "{holds: …} holds when every requirement of the named block holds" {
        val engine = requiring("{holds: second_key}")
        val ranks = arrayOf("now" to NOW, "roster.rank(of=reviewer)" to 2, "roster.rank(of=coder)" to 1, "roster.model_rank(of=reviewer)" to 2, "roster.floor_rank" to 2)
        engine.failure(table(*ranks, "seat.name(of=reviewer)" to "reviewer-1", "seat.name(of=coder)" to "coder-1")) shouldBe null
        engine.failure(table(*ranks, "seat.name(of=reviewer)" to "coder-1", "seat.name(of=coder)" to "coder-1")) shouldBe "{holds: second_key}"
    }
    "{any: […]} holds when one of its requirements holds" {
        val engine = requiring("{any: [{arg: name, eq: resume}, {arg: name, eq: drop}]}")
        engine.failure(args = """{"name": "drop"}""") shouldBe null
        engine.failure(args = """{"name": "recut"}""") shouldBe "{any: [{arg: name, eq: resume}, {arg: name, eq: drop}]}"
    }
    "{not: {…}} holds when its requirement does not" {
        val engine = requiring("{not: {arg: items}}")
        engine.failure(args = "{}") shouldBe null
        engine.failure(args = """{"items": ["x"]}""") shouldBe "{not: {arg: items}}"
    }
    "requirements are evaluated in order, and the first that fails is the one reported" {
        val engine = requiring("{arg: name}", "{fact: pr.ready}", "{arg: items}")
        engine.failure(table("now" to NOW, "pr.ready" to false), args = "{}") shouldBe "{arg: name}"
        engine.failure(table("now" to NOW, "pr.ready" to false), args = """{"name": "x"}""") shouldBe "{fact: pr.ready}"
        engine.failure(table("now" to NOW, "pr.ready" to true), args = """{"name": "x"}""") shouldBe "{arg: items}"
    }
})

/** T2: every value form of contracts.md §4.1 and §4.8, each resolved where a requirement compares with it. */
class ValueFormsSpec : StringSpec({
    "a literal of the rules file's own: a state name, an outcome, a list" {
        requiring("{arg: name, in: [resume, drop]}").failure(args = """{"name": "drop"}""") shouldBe null
        requiring("{arg: name, in: [resume, drop]}").failure(args = """{"name": "recut"}""") shouldBe "{arg: name, in: [resume, drop]}"
    }
    "{cap: …} is the cap's value in engagement.yaml" {
        val engine = requiring("{fact: list.tasks_total, lte: {cap: stage_tasks_max}}")
        engine.failure(table("now" to NOW, "list.tasks_total" to 8)) shouldBe null
        engine.failure(table("now" to NOW, "list.tasks_total" to 9)) shouldBe "{fact: list.tasks_total, lte: {cap: stage_tasks_max}}"
    }
    "{arg: …} is the message's argument, also as a fact's parameter" {
        val engine = requiring("{fact: plan.stage_has_list, stage: {arg: name}}")
        engine.failure(table("now" to NOW, "plan.stage_has_list(stage=KP/P2/S2.2)" to true), args = """{"name": "KP/P2/S2.2"}""") shouldBe null
        engine.failure(table("now" to NOW, "plan.stage_has_list(stage=KP/P2/S2.2)" to true), args = """{"name": "KP/P2/S2.3"}""") shouldBe "{fact: plan.stage_has_list, stage: {arg: name}}"
    }
    "{fact: …} as a value is that fact's value" {
        val engine = requiring("{arg: name, eq: {fact: pr.head}}")
        engine.failure(table("now" to NOW, "pr.head" to "abc"), args = """{"name": "abc"}""") shouldBe null
        engine.failure(table("now" to NOW, "pr.head" to "def"), args = """{"name": "abc"}""") shouldBe "{arg: name, eq: {fact: pr.head}}"
    }
    "{roster: …}, {office: …} and {budget: …} are values of pool/*.yaml — the office is the one in the message's id" {
        val engine = requiring("{fact: seat.model, of: coder, eq: {roster: review.floor}}", "{fact: office.computer_use, office: panda, eq: {office: computer_use}}", "{fact: budget.consumption, subscription: claude, lt: {budget: warn.0}}")
        val held = arrayOf("now" to NOW, "seat.model(of=coder)" to "claude-opus-5-5", "office.computer_use(office=panda)" to "off")
        engine.failure(table(*held, "budget.consumption(subscription=claude)" to 0.5)) shouldBe null
        engine.failure(table(*held, "budget.consumption(subscription=claude)" to 0.75)) shouldBe "{fact: budget.consumption, subscription: claude, lt: {budget: warn.0}}"
    }
    "{engagement: …} is a value of engagement.yaml outside its caps — a duration compares as one" {
        val engine = requiring("{fact: bora.idle_for, gte: {engagement: presence.idle_hours_to_absent}}")
        engine.failure(table("now" to NOW, "bora.idle_for" to "5h")) shouldBe null
        engine.failure(table("now" to NOW, "bora.idle_for" to "90m")) shouldBe "{fact: bora.idle_for, gte: {engagement: presence.idle_hours_to_absent}}"
    }
    "{robot: …} outside a robot is a threshold of the sender's robot file" {
        val engine = requiring("{fact: seat.context_used, seat: {arg: seat}, gt: {robot: context_health}}")
        engine.failure(table("now" to NOW, "seat.context_used(seat=coder-1)" to 0.8), args = """{"seat": "coder-1"}""", from = "pool") shouldBe null
        engine.failure(table("now" to NOW, "seat.context_used(seat=coder-1)" to 0.6), args = """{"seat": "coder-1"}""", from = "pool") shouldBe
            "{fact: seat.context_used, seat: {arg: seat}, gt: {robot: context_health}}"
    }
    "{msg: …} as a value is a field of the message" {
        val engine = requiring("{fact: live.namespace_absent, stage: {msg: stage}}")
        engine.failure(table("now" to NOW, "live.namespace_absent(stage=$STAGE)" to true)) shouldBe null
        engine.failure(table("now" to NOW, "live.namespace_absent(stage=KP/P1/S1.3)" to true)) shouldBe "{fact: live.namespace_absent, stage: {msg: stage}}"
    }
    "{re: …} as a value is a field of the message this one replies to" {
        val engine = requiring("{arg: seat, eq: {re: from}}")
        val asked = folded(log(*sent("m-panda-000092", "stop", from = "coder-1", to = "principal")))
        engine.failure(args = """{"seat": "coder-1"}""", state = asked, re = "m-panda-000092") shouldBe null
        engine.failure(args = """{"seat": "coder-2"}""", state = asked, re = "m-panda-000092") shouldBe "{arg: seat, eq: {re: from}}"
    }
    "{trigger: …} is a field of what triggered a robot's rule; a judgement has no such trigger, so what reads it fails" {
        requiring("{arg: seat, eq: {trigger: seat}}").failure(args = """{"seat": "coder-1"}""") shouldBe "{arg: seat, eq: {trigger: seat}}"
    }
})
