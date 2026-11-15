package kp.engine

import kp.rules.RuleSet

/**
 * The mailroom's guard (architecture.md §1): the rule set applied to a trigger. Pure — it reads no file, no clock
 * and no world; "now" is a fact like any other. [profile] names the autonomy profile in force (`engagement.yaml:
 * autonomy_profiles`), which the rules do not choose.
 */
class Engine(private val rules: RuleSet, private val profile: String) {
    /** The facts [judge] will read that the table must supply (contracts.md §4.4): every fact but those of source L. */
    fun needs(state: State, trigger: Trigger): List<Need> = TODO("S1.2 T3")

    /** What is to happen: deliver or refuse, which transitions fire, which counters move, which effects are requested. */
    fun judge(state: State, trigger: Trigger, facts: Facts): Decision = TODO("S1.2 T2–T7")
}
