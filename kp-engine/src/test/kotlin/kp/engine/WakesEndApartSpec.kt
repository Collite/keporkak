package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * S2.9 T4 — a wake's `ok` or `failed` record names its seat as its request does, and the fold ends a wake by its seat
 * (contracts.md §4.4, Amendment 28; issue #42, item 3): one message that wakes two seats — its addressee and a seat it
 * copies — has two wakes in flight, and whichever result comes first ends its own.
 */
class WakesEndApartSpec : StringSpec({
    val bind = """{"id":"m-panda-000001","thread":"m-panda-000001","from":"teamlead","to":"reviewer-1","cc":["coder-1"],"kind":"review-bind",""" +
        """"stage":"KP/P2/S2.9","subject":"review-bind","created":"2026-11-19T09:00:00Z","by":"seat:teamlead"}"""
    val requested = listOf(
        bind,
        """{"event":"delivered","id":"m-panda-000001","to":["reviewer-1","coder-1"],"woke":["reviewer-1","coder-1"],"class":"A"}""",
        """{"event":"op","op":"seat.wake","phase":"requested","cause":"m-panda-000001","with":{"seat":"reviewer-1"}}""",
        """{"event":"op","op":"seat.wake","phase":"requested","cause":"m-panda-000001","with":{"seat":"coder-1"}}""",
    )

    "the copied seat's wake ends first, though it was requested second: the addressee's is still in flight" {
        val lines = log(*(requested + """{"event":"op","op":"seat.wake","phase":"ok","cause":"m-panda-000001","with":{"seat":"coder-1"}}""").toTypedArray())

        folded(lines).operations.map { it.with.fields["seat"] } shouldBe listOf(Datum.Text("reviewer-1"))
    }

    "a failed wake ends its own seat's wake, and the other's ok then ends the other" {
        val lines = log(*(requested + listOf(
            """{"event":"op","op":"seat.wake","phase":"failed","cause":"m-panda-000001","with":{"seat":"reviewer-1"},"reason":"the session of seat reviewer-1 has ended"}""",
            """{"event":"op","op":"seat.wake","phase":"ok","cause":"m-panda-000001","with":{"seat":"coder-1"}}""",
        )).toTypedArray())

        folded(lines.take(5)).operations.map { it.with.fields["seat"] } shouldBe listOf(Datum.Text("coder-1"))
        folded(lines).operations shouldBe emptyList()
    }

    "a robot's own seat woken in a mode ends by its result, which names no seat, though a seat's wake of the same message is in flight (S3.3, Amendment 36)" {
        val lines = log(*(requested + listOf(
            """{"event":"op","op":"seat.wake","phase":"requested","cause":"m-panda-000001","with":{"role":"retro","mode":"stage-retro","rule":"RET-2"}}""",
            """{"event":"op","op":"seat.wake","phase":"ok","cause":"m-panda-000001"}""",
        )).toTypedArray())

        // Operations run outside the mailroom's lock: the mode wake's result may come before the seats' (contracts.md §7, Amendment 36).
        val state = folded(lines)
        state.operations.map { it.with.fields["seat"] } shouldBe listOf(Datum.Text("reviewer-1"), Datum.Text("coder-1"))
        state.wakes.keys shouldBe setOf("retro")
    }
})
