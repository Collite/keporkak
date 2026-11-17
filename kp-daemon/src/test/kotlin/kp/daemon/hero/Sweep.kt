package kp.daemon.hero

/**
 * The refusal sweep (S1.6 T5): generated from the loaded rule set — one case per requirement of every transition and
 * every kind, each through the daemon. [onMaster] requirements; [swept] of them refused, naming the one broken;
 * [exceptions] cannot be broken alone, each with its reason; [failures] are cases that did not go as they must.
 */
class Sweep private constructor(val onMaster: Int, val swept: Int, val exceptions: List<String>, val failures: List<String>) {
    companion object {
        fun ofTheRulesOnMaster(): Sweep = TODO("S1.6 T5: the refusal sweep")
    }
}
