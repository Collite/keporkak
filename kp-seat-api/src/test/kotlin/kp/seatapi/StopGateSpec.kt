package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.TimeUnit

/** S2.3 T7 — the gate's judgement and `/hook/stop` (contracts.md §4.3, §11). */
class StopGateSpec : StringSpec({
    val kept = json("""{"block": true, "reason": "a turn ends only when the seat has no unread mail"}""")
    val ends = json("""{"block": false, "reason": ""}""")
    val assign = """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}"""

    "with caps.stop_gate_blocks changed in the rule set, the number of blocks changes with it" {
        SeatOffice(rules = MasterRules().edit("rules/engagement.yaml", "stop_gate_blocks: 3", "stop_gate_blocks: 1")).use { office ->
            office.seat("teamlead", role = "teamlead").call("send", assign)
            val coder = office.seat("coder-1", role = "coder")

            val answers = listOf(false, true).map { coder.stop(it) }

            answers shouldBe listOf(kept, ends)
            office.records("fact").map { it.text("value") } shouldBe listOf("1")
        }
    }

    "the count starts again when the hook says no block is active" {
        SeatOffice().use { office ->
            office.seat("teamlead", role = "teamlead").call("send", assign)
            val coder = office.seat("coder-1", role = "coder")

            val answers = listOf(false, true, false, true, true, true).map { coder.stop(it) }

            answers shouldBe listOf(kept, kept, kept, kept, kept, ends)
            office.records("fact").map { it.text("value") } shouldBe listOf("3")
        }
    }

    "a seat with no unread mail ends its turn at once, and one that reads its mail is let go; neither writes a fact" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")

            val empty = coder.stop(false)
            teamlead.call("send", assign)
            val held = coder.stop(false)
            coder.call("inbox")
            val read = coder.stop(true)

            listOf(empty, held, read) shouldBe listOf(ends, kept, ends)
            office.records("fact") shouldBe emptyList()
        }
    }

    "an unknown or dead token is answered 401 at /hook/stop, and the log does not grow" {
        SeatOffice().use { office ->
            office.seat("teamlead", role = "teamlead").call("send", assign)
            val coder = office.seat("coder-1", role = "coder")
            val lines = office.lines()

            val unknown = coder.post("/hook/stop", """{"stop_hook_active": false}""", token = "kp-spec-no-such-token")
            office.tokens.revoke(coder.token)
            val dead = coder.post("/hook/stop", """{"stop_hook_active": false}""")

            listOf(unknown.status, dead.status) shouldBe listOf(401, 401)
            office.lines() shouldBe lines
        }
    }

    "hooks/stop-gate, run against the real endpoint, relays a block with its reason, and lets the turn end once the token is dead" {
        SeatOffice().use { office ->
            office.seat("teamlead", role = "teamlead").call("send", assign)
            val coder = office.seat("coder-1", role = "coder")

            val blocked = stopHook(coder.base, coder.token, """{"hook_event_name": "Stop", "stop_hook_active": false}""")
            office.tokens.revoke(coder.token)
            val dead = stopHook(coder.base, coder.token, """{"hook_event_name": "Stop", "stop_hook_active": true}""")

            blocked shouldBe (0 to """{"decision":"block","reason":"a turn ends only when the seat has no unread mail"}""")
            dead shouldBe (0 to "")
        }
    }
})

/** `hooks/stop-gate` as Claude Code runs it (contracts.md §11): the hook's JSON on stdin, the URL and token in its environment. */
private fun stopHook(url: String, token: String, input: String): Pair<Int, String> {
    val process = ProcessBuilder("/bin/sh", File(root, "hooks/stop-gate").path, "5")
        .apply { environment().putAll(mapOf("KP_HOOK_URL" to url, "KP_SESSION_TOKEN" to token)) }.start()
    process.outputStream.use { it.write(input.toByteArray()) }
    val out = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor(30, TimeUnit.SECONDS)) { "the hook did not end" }
    return process.exitValue() to out
}
