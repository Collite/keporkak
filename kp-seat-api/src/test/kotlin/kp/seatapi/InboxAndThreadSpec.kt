package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** S2.3 T4 — `inbox` and `thread` (contracts.md §5). */
class InboxAndThreadSpec : StringSpec({

    "inbox returns at most limit and says whether more are left; with unread false it returns every message delivered to the seat, and marks nothing read twice" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val pool = office.seat("pool-1", role = "pool", stage = STAGE)
            val coder = office.seat("coder-1", role = "coder")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")
            val compact = pool.call("send", """{"kind": "compact", "subject": "your context is full", "args": {"seat": "coder-1"}}""").json!!.text("id")

            val pages = listOf("""{"limit": 1}""", "{}", """{"unread": false}""", """{"unread": false, "limit": 1}""").map { coder.call("inbox", it).json!! }

            pages.map { it.messageIds to it.text("more") } shouldBe listOf(
                listOf(assign) to "true", listOf(compact) to "false", listOf(assign, compact) to "false", listOf(assign) to "true",
            )
            office.records("read").map { it.text("seat") to it["ids"].toString() } shouldBe listOf("coder-1" to "[\"$assign\"]", "coder-1" to "[\"$compact\"]")
        }
    }

    "thread returns a thread's messages in order to a seat that sent, received or was copied on one of them, and to no other" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val reviewer = office.seat("reviewer-1", role = "reviewer")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")
            val started = coder.call("reply", """{"re": "$assign", "kind": "started"}""").json!!.text("id")
            val stop = coder.call("send", """{"kind": "stop", "subject": "the contract must change", "args": {"sc": "SC-2"}}""").json!!.text("id")

            listOf(coder, teamlead).map { it.call("thread", """{"id": "$assign"}""").json!!.messageIds } shouldBe listOf(listOf(assign, started), listOf(assign, started))
            teamlead.call("thread", """{"id": "$stop"}""").json!!.messageIds shouldBe listOf(stop)
            reviewer.call("thread", """{"id": "$stop"}""").text shouldBe "id: no thread $stop that reviewer-1 is a party to"
            reviewer.call("thread", """{"id": "m-panda-999999"}""").text shouldBe "id: no thread m-panda-999999 that reviewer-1 is a party to"
        }
    }

    "an inbox or thread input of the wrong type is an error of the tool that names the field" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")
            val lines = office.lines()

            val answers = listOf(
                coder.call("inbox", """{"unread": "yes"}"""),
                coder.call("inbox", """{"limit": 0}"""),
                coder.call("inbox", """{"limit": "ten"}"""),
                coder.call("thread", """{"id": 7}"""),
                coder.call("thread", "{}"),
            )

            answers.map { it.isError to it.text.substringBefore(':') } shouldBe listOf("unread", "limit", "limit", "id", "id").map { true to it }
            office.lines() shouldBe lines
        }
    }
})
