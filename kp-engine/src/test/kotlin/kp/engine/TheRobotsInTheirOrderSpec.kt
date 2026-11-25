package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.rules.RuleSet

/**
 * KP/P3/S3.4 T1 — the robots are asked in their files' `order:`, the lowest first (contracts.md §8, Amendment 45; D-118).
 * The seam: the engine's `react` on an arrival, on a fixture rule set made from master's in which the Pool Manager too
 * intercepts a classify-request and answers it to the asker, as the Deputy does while Bora is away — two robots that
 * intercept one kind.
 */
class TheRobotsInTheirOrderSpec : StringSpec({
    val away = folded(after(State(), """{"event":"transition","machine":"presence","subject":"bora","from":"present","to":"absent","rule":"PRESENCE-01","cause":"m-panda-000001"}"""))
    val asked = message("classify-request", "reviewer-1", """{"question":"blocker?","default":"follow-up"}""", to = "bora")

    // The Pool Manager intercepts the kind and holds it as the Deputy's DEP-9 does: a hold to the asker. `hold` is the
    // Deputy's kind on master; in the fixture the pool may send it too.
    val poolTakesIt = arrayOf(
        Edit("rules/message-kinds.yaml", "  hold:\n    from: deputy\n", "  hold:\n    from: deputy | pool\n"),
        Edit("robots/pool.yaml", "intercepts: []\nrules:\n", """
            |intercepts:
            |- classify-request
            |rules:
            |- id: POOL-90
            |  when: a classify-request arrives (a fixture's)
            |  note: send hold to the asker
            |  'on':
            |    arrives: classify-request
            |  if:
            |  - msg: to
            |    eq: bora
            |  then:
            |  - send: hold
            |    to:
            |      trigger: from
            |    args: {}
            |""".trimMargin()),
    )
    val exchanged = arrayOf(Edit("robots/deputy.yaml", "\norder: 1\n", "\norder: 2\n"), Edit("robots/pool.yaml", "\norder: 2\n", "\norder: 1\n"))

    fun holder(rules: RuleSet, state: State): String? = Engine(rules).react(state, Trigger.Arrival(asked), table("now" to NOW)).held?.by

    "a message two robots would take is taken by the lower order: the Deputy's 1 before the Pool Manager's 2" {
        holder(ruleSet(*poolTakesIt), away) shouldBe "robot:DEP-9"
    }

    "with the Deputy's and the Pool Manager's numbers exchanged, the other takes it — their files' names unchanged" {
        holder(ruleSet(*poolTakesIt, *exchanged), away) shouldBe "robot:POOL-90"
    }

    "the rule set keeps the robots lowest first; master's four files load with 1, 2, 3, 4" {
        master.robots.values.map { it.file to it.order } shouldBe listOf("deputy.yaml" to 1, "pool.yaml" to 2, "qa.yaml" to 3, "retro.yaml" to 4)
        ruleSet(*exchanged).robots.values.map { it.file } shouldBe listOf("pool.yaml", "deputy.yaml", "qa.yaml", "retro.yaml")
    }
})
