package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private val master: RuleSet by lazy { (Rules.load(filesOnMaster()) as Loaded.Valid).ruleSet }

/** S1.7 — the machine forms written into the rules from their prose (contracts.md §4.2, §4.3), and what the loader reports of them. */
class CatchUpRulesSpec : StringSpec({
    "every kind whose reply prose names a kind lists exactly those kinds in replies, and the prose stays" {
        val named = master.kinds.values.associate { kind ->
            kind.name to Regex("[a-z][a-z-]*").findAll(kind.reply.orEmpty()).map { it.value }.filter { it in master.kinds }.toList()
        }

        master.kinds.values.associate { it.name to it.replies } shouldBe named
        master.kinds.getValue("verdict").reply shouldBe "repair-done (if FIX FIRST)"
        master.kinds.getValue("verdict").replies shouldBe listOf("repair-done")
        master.kinds.values.count { it.replies.isNotEmpty() } shouldBe 18
    }
    "a replies that names an unknown kind, or is not a list of kinds, is reported with the kind's name" {
        problemsAfter("rules/message-kinds.yaml", "    replies:\n    - review-bind\n", "    replies:\n    - review-bnd\n") shouldContain
            "rules/message-kinds.yaml: review-request: unknown kind 'review-bnd'"
        problemsAfter("rules/message-kinds.yaml", "    replies:\n    - review-bind\n", "    replies: review-bind\n") shouldContain
            "rules/message-kinds.yaml: review-request: replies must be a list"
    }
    "{wait: again} stands in the three kinds whose expiry prose keeps the wait going, each under its counter's cap" {
        val waits = master.kinds.values.filter { kind -> kind.expiry.any { it is Effect.Wait } }.associate { kind ->
            kind.name to kind.expiry.filterIsInstance<Effect.Wait>().single().condition
        }
        val under = { counter: String -> Requirement.Counter(counter, Comparison(Operator.LT, Value.Cap("expiries_before_escalation"))) }

        waits shouldBe mapOf("review-request" to under("review_request_expiries"), "review-bind" to under("review_bind_expiries"), "repair-done" to under("repair_expiries"))
        master.kinds.getValue("review-bind").onExpiry shouldBe "re-bind once, then Bora (C)"
    }
    "a {wait: again} that is not under a counter's cap, or a wait that is not again, is reported" {
        val wait = "    - wait: again\n      if:\n        counter: review_request_expiries\n        lt:\n          cap: expiries_before_escalation\n"

        problemsAfter("rules/message-kinds.yaml", wait, "    - wait: again\n") shouldContain
            "rules/message-kinds.yaml: review-request: {wait: again} has no if that reads a counter against a cap"
        problemsAfter("rules/message-kinds.yaml", wait, "    - wait: again\n      if:\n        fact: bora.present\n") shouldContain
            "rules/message-kinds.yaml: review-request: {wait: again} has no if that reads a counter against a cap"
        problemsAfter("rules/message-kinds.yaml", wait, wait.replace("wait: again", "wait: later")) shouldContain
            "rules/message-kinds.yaml: review-request: wait must be again"
    }
    "the profile in force is named in engagement.yaml — supervised on master — and must be one of autonomy_profiles" {
        master.engagement.profile shouldBe "supervised"
        problemsAfter("rules/engagement.yaml", "profile: supervised", "profile: supervisd") shouldContain
            "rules/engagement.yaml: profile 'supervisd' is not in autonomy_profiles"
        problemsAfter("rules/engagement.yaml", "profile: supervised", "profiles: supervised") shouldContain "rules/engagement.yaml: missing profile"
    }
})
