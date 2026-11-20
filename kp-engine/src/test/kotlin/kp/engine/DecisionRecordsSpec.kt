package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival
import kp.model.Delivered
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import kp.model.plain
import kp.rules.RuleSet

private val building = folded(log(move("stage", STAGE, "assigned", "building")))
private fun review(id: String = "m-panda-000100") = Arrival(message("review-request", from = "coder-1", id = id, args = """{"attest": ["done_lines_hold"]}"""))
private fun ready(found: Int = 0) = table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "green", "list.tasks_open" to 0, "list.found_untriaged" to found)

/** The names of the facts a record's `observed` holds, read from the line as kp-model writes it. */
private fun observed(record: kp.model.Envelope): Set<String> =
    (record.plain().getValue("observed") as Map<*, *>).keys.map { it as String }.toSet()

/** A record line with `v`, `seq` and `at`, read back by kp-model — which validates it against contracts.md §2.2. */
private fun read(record: String) = parseLine("""{"v":1,"seq":7,"at":"$NOW",""" + record.removePrefix("{"))

/**
 * Review-requests sent one after another, each answered by a fix round that takes the stage back to building, until
 * the engine refuses one: how many were delivered, and the rule that refused the next.
 */
private fun deliveriesBeforeRefusal(rules: RuleSet): Pair<Int, String?> {
    val engine = supervised(rules)
    var state = building
    var delivered = 0
    while (true) {
        val id = "m-panda-%06d".format(200 + delivered)
        val decision = engine.judge(state, review(id), ready())
        if (decision.outcome == Outcome.REFUSE) return delivered to decision.rule
        delivered++
        val message = """{"id":"$id","thread":"$id","from":"coder-1","to":"teamlead","kind":"review-request","stage":"$STAGE","subject":"r","created":"$NOW","by":"seat:coder-1","args":{}}"""
        state = folded(after(state, message, deliveredRecord(decision, id, "teamlead"), *transitionRecords(decision, id).toTypedArray(),
            """{"event":"transition","machine":"stage","subject":"$STAGE","from":"ready_for_review","to":"building","cause":"$id"}"""), state)
    }
}

/** T7: the decision carries what its records need (contracts.md §2.2), and the rules — not the engine — hold every number. */
class DecisionRecordsSpec : StringSpec({
    "a delivery's decision fills its delivered record and its transition record, and kp-model reads both" {
        val decision = supervised().judge(building, review(), ready())

        val delivered = read(deliveredRecord(decision, "m-panda-000100", "teamlead")) as Delivered
        val transition = read(transitionRecords(decision, "m-panda-000100").single()) as Transition

        listOf(delivered.rule, delivered.deliveryClass, delivered.counted, delivered.attested) shouldBe listOf("T-17", "A", mapOf("review_requests" to 1.toBigInteger()), listOf("done_lines_hold"))
        observed(delivered) shouldBe setOf("pr.ready", "pr.head", "ci.state(of=abc)", "list.tasks_open", "list.found_untriaged", "now")
        listOf(transition.machine, transition.subject, transition.from, transition.to, transition.rule, transition.counted) shouldBe
            listOf("stage", STAGE, "building", "ready_for_review", "T-17", mapOf("review_requests" to 1.toBigInteger()))
    }
    "a refusal's decision fills its refused record: the rule, the requirement in the rule file's prose, every fact read" {
        val decision = supervised().judge(building, review(), table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "red"))

        val refused = read(refusedRecord(decision, "m-panda-000100")) as Refused

        decision should refusedBy("review-request", "{fact: ci.state, of: {fact: pr.head}, eq: green}")
        refused.rule shouldBe "review-request"
        refused.requirement shouldBe "PR ready · CI green on head · every task done <sha> · DONE lines ticked · Found lines carried (fan-out found-batch)"
        observed(refused) shouldBe setOf("pr.ready", "pr.head", "ci.state(of=abc)")
    }
    "the effects requested come with their if already decided: found-batch when Found lines wait, nothing when none do" {
        supervised().judge(building, review(), ready(found = 2)).effects shouldBe listOf(Requested.Emit("T-17", "found-batch", "principal", emptyMap()))
        supervised().judge(building, review(), ready(found = 0)).effects shouldBe emptyList()
    }
    "with caps.review_requests_per_stage changed in a fixture rule set, the number of deliveries before the first refusal changes accordingly" {
        fun capped(at: Int) = ruleSet(Edit("rules/engagement.yaml", "  review_requests_per_stage: 3\n", "  review_requests_per_stage: $at\n"))

        deliveriesBeforeRefusal(master) shouldBe (3 to "review-request")
        deliveriesBeforeRefusal(capped(2)) shouldBe (2 to "T-17")
        deliveriesBeforeRefusal(capped(1)) shouldBe (1 to "T-17")
    }
})
