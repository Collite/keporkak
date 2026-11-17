package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** S2.3 T3 — `send` and `reply` (contracts.md §5): the seat's name, the defaults, and input that is no message at all. */
class SendAndReplySpec : StringSpec({

    "a reply takes its thread, its stage and, when none is given, its subject from the message it answers" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder", stage = null)
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")

            val started = coder.call("reply", """{"re": "$assign", "kind": "started", "stage": "KP/P9/S9.9", "to": "principal"}""").json!!

            started.text("status") shouldBe "delivered"
            val written = office.messages().last()
            listOf("thread", "re", "stage", "subject", "to", "from", "by").map(written::text) shouldBe
                listOf(assign, assign, STAGE, "re: S2.3 is yours", "teamlead", "coder-1", "seat:coder-1")
            office.records("delivered").last().text("answers") shouldBe assign
        }
    }

    "send's stage defaults to the seat's; a stage it is given is kept" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")

            coder.call("send", """{"kind": "started", "subject": "started on the branch"}""")
            coder.call("send", """{"kind": "stop", "subject": "a stop elsewhere", "stage": "KP/P1/S1.9", "args": {"sc": "SC-1"}}""")

            office.messages().map { it.text("stage") } shouldBe listOf(STAGE, "KP/P1/S1.9")
        }
    }

    "input that is no message at all is an error of the tool that names the field, and the log does not grow" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")
            val lines = office.lines()
            val long = "x".repeat(121)

            val sent = listOf(
                """{"subject": "no kind"}""",
                """{"kind": "Started!", "subject": "a kind outside its form"}""",
                """{"kind": "started", "stage": "S2.3", "subject": "a stage outside its form"}""",
                """{"kind": "started", "subject": "$long"}""",
                """{"kind": "started", "subject": 7}""",
                """{"kind": 7, "subject": "a kind of the wrong type"}""",
                """{"kind": "started", "subject": "a body of the wrong type", "body": ["x"]}""",
                """{"kind": "started", "subject": "pointers of the wrong type", "pointers": "pr:Collite/keporkak#1"}""",
                """{"kind": "started", "subject": "args of the wrong type", "args": ["x"]}""",
            ).map { coder.call("send", it) }
            val replied = listOf(
                """{"kind": "started"}""",
                """{"re": "the assign", "kind": "started"}""",
            ).map { coder.call("reply", it) }

            (sent + replied).map { it.isError to it.text.substringBefore(':') } shouldBe
                listOf("kind", "kind", "stage", "subject", "subject", "kind", "body", "pointers", "args", "re", "re").map { true to it }
            office.lines() shouldBe lines
        }
    }
})
