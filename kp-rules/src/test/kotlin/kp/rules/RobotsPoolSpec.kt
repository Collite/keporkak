package kp.rules

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.yamlMap
import io.kotest.core.spec.style.StringSpec
import java.io.File
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
        // By content, not by a count another stage's roster edit moves (S3.0 T5): the order as pool/roster.yaml writes it.
        rules.pool.roster.order shouldBe Yaml.default.parseToYamlNode(File(root, "pool/roster.yaml").readText()).yamlMap.get<YamlList>("order")!!.items
            .map { it.yamlMap }.map { Line(it.getScalar("vendor")!!.content, it.getScalar("model")!!.content, it.getScalar("effort")!!.content) }
        rules.pool.roster.order.last() shouldBe Line("claude", "claude-fable-5-2", "max")
        rules.pool.roster.reserved shouldBe mapOf("claude-fable-5-2" to listOf("principal"))
        rules.pool.roster.roleModels shouldBe emptyMap()
        rules.pool.roster.levels.getValue("senior").getValue("XL") shouldBe Line("claude", "claude-opus-5-5", "max")
        rules.pool.roster.reviewFloor shouldBe "claude-opus-5-5"
        rules.pool.offices.getValue("panda").computerUse shouldBe "off"
        rules.pool.budgets.slots.total shouldBe 4
    }

    "an office names one project checkout and a folder per effort inside it" {
        rules.pool.offices.getValue("panda").project shouldBe Project("~/Dev/keporkak/project", mapOf("KP" to ".", "TOY" to "toy"))
        problemsAfter("pool/offices.yaml", "TOY: toy}", "TOY: ../toy}") shouldBe
            listOf("pool/offices.yaml: panda: project effort 'TOY' must be an effort id with a folder inside the checkout")
        problemsAfter("pool/offices.yaml", "project: {checkout: ~/Dev/keporkak/project, efforts: {KP: \".\", TOY: toy}}", "project: {KP: ~/Dev/keporkak/project}") shouldBe
            listOf("pool/offices.yaml: panda: project must name a checkout and a folder per effort", "pool/offices.yaml: panda: unknown field 'project.KP'")
    }

    "a reserved model only its roles may run outright, and a pool value the rules use resolves" {
        val reserved = """"reserved": {"claude-sonnet-5-5": ["principal"]}, "role_models": {"principal": {"model": "claude-sonnet-5-5", "effort": "high"}, "coder": {"model": "claude-sonnet-5-5", "effort": "low"}}}"""
        problemsAfterExpanded("pool/roster.yaml", "\"reserved\": {\"claude-fable-5-2\": [\"principal\"]}, \"role_models\": {}}", reserved) shouldBe
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
