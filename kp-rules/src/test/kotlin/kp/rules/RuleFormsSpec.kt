package kp.rules

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes

/** T2 — every form of contracts.md §4.1–§4.4 has a type, and a form the contract does not list is a problem. */
class RuleFormsSpec : StringSpec({
    "master's four rules files type without a problem once their anchors are written out" {
        val loaded = Rules.load(rulesFilesOnMaster())

        loaded.problems.shouldBeEmpty()
        val rules = (loaded as Loaded.Valid).ruleSet
        rules.lifecycle.machines.keys shouldBe setOf("stage", "found_item", "flag_thread", "seat", "phase", "presence")
        rules.kinds.getValue("merge").deadline shouldBe 15.minutes
        rules.engagement.secondKey!!.otherwise shouldBe MessageClass.B
        rules.vocabulary.facts.getValue("plan.predecessors_exited").sources shouldBe setOf(Source.P, Source.L)
    }

    "a requirement types as its form, with its comparison" {
        val t14 = transitionOf("""{fact: ci.state, of: {fact: pr.head}, eq: green}""")
        t14.require.single() shouldBe Requirement.Fact(
            FactCall("ci.state", mapOf("of" to Value.Fact(FactCall("pr.head", emptyMap())))),
            Comparison(Operator.EQ, Value.Constant(Literal.Text("green"))),
        )
        transitionOf("""{counter: review_requests, lt: {cap: review_requests_per_stage}}""").require.single() shouldBe
            Requirement.Counter("review_requests", Comparison(Operator.LT, Value.Cap("review_requests_per_stage")))
        transitionOf("""{not: {any: [{attest: done_lines_hold}, {signed: bora}]}}""").require.single() shouldBe
            Requirement.Not(Requirement.Any(listOf(Requirement.Attest("done_lines_hold"), Requirement.Signed("bora"))))
    }

    "a requirement, a value or an effect the contract does not list is a problem, not something skipped" {
        problemsOf(require = """[{frobnicate: ready}]""") shouldContain "rules/stage-lifecycle.yaml: T-X: unknown requirement form"
        problemsOf(require = """[{arg: verdict, eq: {weird: 1}}]""") shouldContain "rules/stage-lifecycle.yaml: T-X: unknown value form {weird}"
        problemsOf(effects = """[{notify: bora}]""") shouldContain "rules/stage-lifecycle.yaml: T-X: unknown effect"
        problemsOf(extra = """, "colour": "blue"""") shouldContain "rules/stage-lifecycle.yaml: T-X: unknown field 'colour'"
        problemsOf(require = """[{arg: verdict, eq: PASS, ne: FAIL}]""") shouldContain "rules/stage-lifecycle.yaml: T-X: more than one comparison in one requirement"
    }

    "a kind's class, window, deadline and arguments are typed" {
        val kind = (Rules.load(rulesFilesOnMaster()) as Loaded.Valid).ruleSet.kinds.getValue("triage")
        kind.window shouldBe Window.STAGE
        kind.classRule.cases.map { it.messageClass } shouldBe listOf(MessageClass.C, MessageClass.B)
        kind.args.getValue("outcome").required shouldBe true
        kind.to.shouldBeInstanceOf<Destination.Parties>()
    }
})

/** Master's four rules files, with any anchor written out so they can be typed before T5 removes the anchors. */
fun rulesFilesOnMaster(): Map<String, String> {
    val permissive = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
    return filesOnMaster().filterKeys { it.startsWith("rules/") }.mapValues { (_, text) -> jsonOf(permissive.parseToYamlNode(text)) }
}

private fun lifecycleWith(transition: String): Map<String, String> {
    val files = rulesFilesOnMaster().toMutableMap()
    val lifecycle = files.getValue(LIFECYCLE)
    files[LIFECYCLE] = lifecycle.replaceFirst("\"transitions\": [", "\"transitions\": [$transition, ")
    return files
}

private fun transitionJson(require: String, effects: String = "[]", extra: String = "") =
    """{"id": "T-X", "from": "building", "to": "building", "kind": "started", "on": "message", "guard": "-", "require": $require, "effects": $effects$extra}"""

private fun transitionOf(requirement: String): Transition {
    val loaded = Rules.load(lifecycleWith(transitionJson("[$requirement]")))
    return (loaded as Loaded.Valid).ruleSet.lifecycle.stage.transitions.first { it.id == "T-X" }
}

private fun problemsOf(require: String = "[]", effects: String = "[]", extra: String = ""): List<String> =
    Rules.load(lifecycleWith(transitionJson(require, effects, extra))).problems.map { it.toString() }
