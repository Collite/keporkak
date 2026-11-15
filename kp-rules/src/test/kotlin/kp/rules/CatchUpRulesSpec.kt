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
})
