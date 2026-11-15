package kp.driver.acp

import kp.driver.Outcome
import kp.driver.SessionSpec

/**
 * What one agent needs beyond ACP itself: the environment its process gets, and which of its values are secrets that
 * no transcript, reason or evidence may show. The binding knows the vendor's variable names; the interface does not.
 */
interface AgentBinding {
    /** The agent's whole environment: the caller's, plus the binding's own variables — nothing inherited. */
    fun environment(spec: SessionSpec): Outcome<Environment>
}

/** An agent's environment and the values in it that are secret. */
class Environment(val variables: Map<String, String>, val secrets: Set<String>) {
    override fun toString() = "Environment(${variables.keys.sorted()})"
}
