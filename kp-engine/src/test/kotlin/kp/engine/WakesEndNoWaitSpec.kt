package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * S2.4 T3 — a wake is an `op` record `seat.wake` with the message as its cause (contracts.md §4.4, Amendment 22). It is
 * no operation the message asked for: its result ends no wait (§2.2), as an operation the message requested does.
 */
class WakesEndNoWaitSpec : StringSpec({
    "a message's wait outlives the wake that prompted its addressee with it, and ends with the answer" {
        val assign = """{"id":"m-panda-000001","thread":"m-panda-000001","from":"teamlead","to":"coder-1","kind":"assign","stage":"KP/P2/S2.4","subject":"assign",""" +
            """"deadline":"2026-11-14T09:15:00Z","created":"2026-11-14T09:00:00Z","by":"seat:teamlead"}"""
        val lines = log(
            assign,
            """{"event":"delivered","id":"m-panda-000001","to":["coder-1"],"woke":["coder-1"],"class":"A"}""",
            """{"event":"op","op":"seat.wake","phase":"requested","cause":"m-panda-000001","with":{"seat":"coder-1"}}""",
            """{"event":"op","op":"seat.wake","phase":"ok","cause":"m-panda-000001"}""",
            """{"event":"read","seat":"coder-1","ids":["m-panda-000001"]}""",
            """{"id":"m-panda-000006","thread":"m-panda-000001","re":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"started","stage":"KP/P2/S2.4",""" +
                """"subject":"started","created":"2026-11-14T09:01:00Z","by":"seat:coder-1"}""",
            """{"event":"delivered","id":"m-panda-000006","to":["teamlead"],"class":"A","answers":"m-panda-000001"}""",
        )

        folded(lines.take(5)).let { listOf(it.deadlines.keys, it.operations, it.unread) } shouldBe listOf(setOf("m-panda-000001"), emptyList<InFlight>(), emptyMap<String, List<String>>())
        folded(lines).deadlines shouldBe emptyMap()
    }
})
