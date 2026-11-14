package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/** T3 — the robots (contracts.md §8) and the three pool files (§9), typed. */
class RobotsPoolSpec : StringSpec({
    val rules = (Rules.load(rulesFilesOnMaster()) as Loaded.Valid).ruleSet

    "a robot's activity, intercepts, rules, queue and thresholds are typed" {
        val deputy = rules.robots.getValue("robots/deputy.yaml")
        deputy.activeWhen shouldBe Requirement.Fact(FactCall("bora.present", emptyMap()), Comparison(Operator.EQ, Value.Constant(Literal.Bool(false))))
        deputy.intercepts.first() shouldBe Intercept.ClassTo(MessageClass.B, "bora")
        deputy.rules.first { it.id == "DEP-8" }.on shouldBe RobotTrigger.Presence("present")
        rules.robots.getValue("robots/retro.yaml").rules.first().then shouldBe listOf(Action.Wake("stage-retro"))
        rules.robots.getValue("robots/qa.yaml").queue!!.order shouldBe listOf("deploy-request", "qa-request")
        rules.robots.getValue("robots/qa.yaml").rules.all { it.forPersona } shouldBe true
        rules.robots.getValue("robots/pool.yaml").thresholds.getValue("watchdog_silence") shouldBe Literal.Text("5m")
    }

    "a persona rule that executes, a robot that sends a kind of another role and a wake of another persona's mode are problems" {
        problemsAfterExpanded("robots/qa.yaml", "\"for\": \"persona\",", "\"for\": \"persona\", \"then\": [],") shouldContain
            "robots/qa.yaml: QA-3: persona rule must not execute"
        problemsAfterExpanded("robots/retro.yaml", "\"wake\": \"stage-retro\"", "\"send\": \"merge\", \"to\": \"daemon\"") shouldContain
            "robots/retro.yaml: RET-1: role 'retro' may not send 'merge'"
        problemsAfterExpanded("robots/retro.yaml", "\"wake\": \"stage-retro\"", "\"wake\": \"shadow\"") shouldContain
            "robots/retro.yaml: RET-1: unknown own persona mode 'shadow'"
    }

    "the roster's order, levels, floor, reserved models and role models are typed" {
        rules.pool.roster.order.size shouldBe 15
        rules.pool.roster.levels.getValue("senior").getValue("XL") shouldBe Line("claude", "claude-opus-5-5", "max")
        rules.pool.roster.reviewFloor shouldBe "claude-opus-5-5"
        rules.pool.offices.getValue("panda").computerUse shouldBe "off"
        rules.pool.budgets.slots.total shouldBe 4
    }

    "a reserved model only its roles may run outright, and a pool value the rules use resolves" {
        val reserved = """, "reserved": {"claude-sonnet-5-5": ["principal"]}, "role_models": {"principal": {"model": "claude-sonnet-5-5", "effort": "high"}, "coder": {"model": "claude-sonnet-5-5", "effort": "low"}}}"""
        problemsAfterExpanded("pool/roster.yaml", "\"floor\": \"claude-opus-5-5\"}}", "\"floor\": \"claude-opus-5-5\"}$reserved") shouldBe
            listOf("pool/roster.yaml: role_models coder runs claude-sonnet-5-5, which is reserved for principal")
        problemsAfterExpanded("pool/budgets.yaml", "\"warn\": [", "\"cautions\": [") shouldContain
            "robots/pool.yaml: POOL-7: {budget: warn.0} does not resolve in pool/"
    }
})

/** The problems once the first [from] in [file] (anchors written out) is replaced by [to]. */
fun problemsAfterExpanded(file: String, from: String, to: String): List<String> {
    val files = rulesFilesOnMaster()
    val text = files.getValue(file)
    check(from in text) { "'$from' is not in $file" }
    return Rules.load(files + (file to text.replaceFirst(from, to))).problems.map { it.toString() }
}
