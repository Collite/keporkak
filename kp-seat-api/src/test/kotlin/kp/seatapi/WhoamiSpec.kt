package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** S2.3 T5 — `whoami` (contracts.md §5): `open`, `left`, and no kind's name in the API's or the engine's source. */
class WhoamiSpec : StringSpec({

    "open names the threads that wait for the seat and those it waits on, until an answer closes them" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val reviewer = office.seat("reviewer-1", role = "reviewer")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")

            val waiting = listOf(coder, teamlead, reviewer).map { it.call("whoami").json!!.open() }
            coder.call("reply", """{"re": "$assign", "kind": "started"}""")
            val answered = listOf(coder, teamlead).map { it.call("whoami").json!!.open() }

            waiting shouldBe listOf(listOf(assign!!), listOf(assign), emptyList())
            answered shouldBe listOf(emptyList(), emptyList())
        }
    }

    "left counts the firings the kind's window has seen" {
        SeatOffice(seed = "building").use { office ->
            val coder = office.seat("coder-1", role = "coder")

            val before = coder.call("whoami").json!!.left("stop")
            coder.call("send", """{"kind": "stop", "subject": "the contract must change", "args": {"sc": "SC-2"}}""")
            val after = coder.call("whoami").json!!.left("stop")

            listOf(before, after) shouldBe listOf("2", "1")
        }
    }
})

private fun JsonObject.open(): List<String> = (this["open"] as JsonArray).map { it.jsonPrimitive.content }

private fun JsonObject.left(kind: String): String? =
    (this["allowed"] as JsonArray).map { it as JsonObject }.single { it.text("kind") == kind }.text("left")
