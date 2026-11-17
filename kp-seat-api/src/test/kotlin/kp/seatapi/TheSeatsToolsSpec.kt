package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject

/**
 * S2.3 — the seat's tools (contracts.md §5, §11). The seat API over HTTP on a loopback port — an MCP client for the
 * tools, a plain request for the hook — in front of a real mailroom on a throwaway log with master's rules; facts,
 * operations and delivery faked; tokens registered by the spec. This stage is seeded in `assigned`.
 */
class TheSeatsToolsSpec : StringSpec({

    "a seat's `send` is carried in its own name, whatever its input claims, and answered as the mailroom answers — delivered with its transition, or refused with the rule and the requirement" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")

            val started = coder.call("send", """{"kind": "started", "subject": "started on the branch", "from": "teamlead", "by": "seat:teamlead"}""")
            val verdict = coder.call("send", """{"kind": "verdict", "subject": "PASS", "args": {"verdict": "PASS", "sha": "abc1234"}}""")

            started.json!!.withoutId() shouldBe json("""{"status": "delivered", "transition": "T-08"}""")
            verdict.json!!.withoutId() shouldBe json("""{"status": "refused", "rule": "verdict", "requirement": "from: reviewer", "refusals_today": 1}""")
            office.messages().map { "${it.text("from")} ${it.text("by")}" } shouldBe listOf("coder-1 seat:coder-1", "coder-1 seat:coder-1")
        }
    }

    "a call with no token or a dead one is rejected before any tool runs, and a seat is shown no thread it is not a party to" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val reviewer = office.seat("reviewer-1", role = "reviewer")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")
            office.tokens.revoke(coder.token)
            val lines = office.lines()

            val noToken = coder.post("/mcp", toolsCall("send", """{"kind": "started", "subject": "started"}"""), token = null)
            val deadToken = coder.post("/mcp", toolsCall("send", """{"kind": "started", "subject": "started"}"""))

            listOf(noToken.status, deadToken.status) shouldBe listOf(401, 401)
            office.lines() shouldBe lines
            reviewer.call("thread", """{"id": "$assign"}""") shouldBe Called(null, "id: no thread $assign that reviewer-1 is a party to", isError = true)
            teamlead.call("thread", """{"id": "$assign"}""").json!!.messageIds shouldBe listOf(assign)
        }
    }

    "`inbox` returns a seat's unread mail once, and the log holds one `read` record for it" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")

            val first = coder.call("inbox").json!!
            val second = coder.call("inbox").json!!

            listOf(first.messageIds, first.text("more")) shouldBe listOf(listOf(assign), "false")
            second.messageIds shouldBe emptyList()
            office.records("read").map { it.content() } shouldBe listOf(json("""{"event": "read", "seat": "coder-1", "ids": ["$assign"]}"""))
        }
    }

    "`whoami` names the seat, its role and its stage, and each kind it may send with how many are left" {
        SeatOffice().use { office ->
            val coder = office.seat("coder-1", role = "coder")
            coder.call("send", """{"kind": "started", "subject": "started on the branch"}""")

            val me = coder.call("whoami").json!!

            listOf("seat", "role", "office", "stage", "model", "effort").map(me::text) shouldBe listOf("coder-1", "coder", "panda", STAGE, "claude-haiku-5-5", "low")
            me.allowed("started") shouldBe json("""{"kind": "started", "to": "teamlead", "reply": [], "deadline": null, "window": "stage", "left": 0}""")
            me.allowed("stop") shouldBe json("""{"kind": "stop", "to": "principal", "reply": ["triage"], "deadline": "4h", "window": "stage", "left": 2}""")
            me.allowed("verdict") shouldBe null
        }
    }

    "a seat with unread mail is kept from ending its turn, in the rule's words; at the cap it is let go and the log says so" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""")

            val answers = listOf(false, true, true, true).map { active -> coder.stop(active) }

            val kept = json("""{"block": true, "reason": "a turn ends only when the seat has no unread mail"}""")
            answers shouldBe listOf(kept, kept, kept, json("""{"block": false, "reason": ""}"""))
            office.records("fact").map { listOf(it.text("fact"), it.text("subject"), it.text("value")) } shouldBe listOf(listOf("turn.blocked", "coder-1", "3"))
        }
    }
})

private fun JsonObject.withoutId() = JsonObject(this - "id")

/** A record as the mailroom wrote it, without what every line carries (`v`, `seq`, `at`). */
private fun JsonObject.content() = JsonObject(this - "v" - "seq" - "at")

/** `whoami`'s line for [kind] in `allowed`, or null when the seat may not send it. */
private fun JsonObject.allowed(kind: String): JsonObject? =
    (this["allowed"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }.firstOrNull { it.text("kind") == kind }
