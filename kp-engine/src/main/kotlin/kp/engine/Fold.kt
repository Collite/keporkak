package kp.engine

import kp.model.Envelope

/** A message that waits for an answer by its `deadline`. */
data class Wait(val kind: String, val stage: String?, val from: String, val to: String, val deadline: String, val expiries: Int)

/** The state the mailroom works from — a fold of the log (architecture.md §3). */
data class State(
    val counters: Map<String, Map<String, Long>> = emptyMap(),
    val deadlines: Map<String, Wait> = emptyMap(),
    val unread: Map<String, List<String>> = emptyMap(),
) {
    fun stage(id: String): String? = TODO("T5")
}

fun fold(lines: Iterable<Envelope>, from: State = State()): State = TODO("T5")
