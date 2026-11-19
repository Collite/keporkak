package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Op

private const val RETIRE = """{"kind": "retire", "stage": "$STAGE", "args": {"seat": "coder-1"}}"""
private const val KILL = """{"kind": "kill", "stage": "$STAGE", "args": {"seat": "coder-1"}}"""

/**
 * S2.9 T3 — an end the daemon's own operation caused (contracts.md §4.4, Amendment 30; D-85): the daemon reports a
 * seat's process gone and its protocol closed when they happen, and those of a seat it retires or kills may reach the
 * mailroom before that operation's result or after it. Here the spec plays both orders, through the real mailroom and
 * robots, with the performer keeping each operation until the spec answers it: the rules give one outcome in either.
 */
class AnEndAnOperationCausedSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a retired seat's two reports — before the retire's result, and after it — send no seat-gone and request no re-seat" {
        val before = anIdleCoder()
        before.send("pool", RETIRE).status shouldBe Status.DELIVERED
        before.reportsTheEnd()
        before.operations.answer("seat.retire")
        before.outcome() shouldBe listOf("seat coder-1 retired", "seat-gone 0", "seat.reseat requested 0")

        val after = anIdleCoder()
        after.send("pool", RETIRE).status shouldBe Status.DELIVERED
        after.operations.answer("seat.retire")
        after.reportsTheEnd()
        after.outcome() shouldBe listOf("seat coder-1 retired", "seat-gone 0", "seat.reseat requested 0")
    }

    "a killed seat's two reports — before the kill's result, and after it — send one seat-gone and request one re-seat" {
        val before = aSilentCoder()
        before.send("pool", KILL).status shouldBe Status.DELIVERED
        before.reportsTheEnd()
        before.operations.answer("seat.kill")
        before.outcome() shouldBe listOf("seat coder-1 gone", "seat-gone 1", "seat.reseat requested 1")

        val after = aSilentCoder()
        after.send("pool", KILL).status shouldBe Status.DELIVERED
        after.operations.answer("seat.kill")
        after.reportsTheEnd()
        after.outcome() shouldBe listOf("seat coder-1 gone", "seat-gone 1", "seat.reseat requested 1")
    }
})

/** H-1′'s stage building, with the robots, its coder idle and a slot of the office over its limit: it may be retired. */
private fun anIdleCoder(): Office = Office(seed = "building", robots = true, seatStates = mapOf("coder-1" to "idle")).apply {
    facts.set("budget.slots_over", mapOf("office" to 1))
    operations.keeping = true
}

/** H-1′'s stage building, with the robots, its coder silent past the watchdog with no tool call open: it may be killed. */
private fun aSilentCoder(): Office = Office(seed = "building", robots = true).apply {
    facts.set("seat.silent_for", mapOf("coder-1" to "6m"))
    operations.keeping = true
}

/** The daemon reports both ends of coder-1, one after the other: its protocol closed, then its process gone. */
private fun Office.reportsTheEnd() {
    changed("seat.protocol_closed", true, STAGE, mapOf("seat" to "coder-1"))
    changed("seat.process_gone", true, STAGE, mapOf("seat" to "coder-1"))
}

/** coder-1's state, how many `seat-gone` were delivered, and how many re-seats were requested. */
private fun Office.outcome(): List<String> = listOf(
    "seat coder-1 ${mailroom.state.machine("seat", "coder-1")?.state}",
    "seat-gone ${readBack().story().count { it.startsWith("seat-gone") && it.endsWith("delivered") }}",
    "seat.reseat requested ${readBack().counted.filterIsInstance<Op>().count { it.op == "seat.reseat" && it.phase == "requested" }}",
)
