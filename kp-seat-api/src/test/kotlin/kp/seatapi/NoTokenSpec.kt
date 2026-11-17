package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * S2.3 DONE — no token appears in any log line, error text or spec output, and the API's and the engine's sources hold
 * no cap value: the stop gate's cap is read from the rules.
 */
class NoTokenSpec : StringSpec({

    "no token a spec made shows in a log line, a tool's answer, an error, a hook's answer or the spec's output" {
        val output = ByteArrayOutputStream()
        val (out, err) = System.out to System.err
        val answers = ArrayList<String>()
        val office = SeatOffice()
        try {
            PrintStream(output, true).let { System.setOut(it); System.setErr(it) }
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val dead = office.seat("coder-2", role = "coder").also { office.tokens.revoke(it.token) }
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").also { answers += it.text }.json!!.text("id")
            answers += listOf(
                coder.call("send", """{"kind": "verdict", "subject": "PASS", "args": {"verdict": "PASS", "sha": "abc1234"}}"""),
                coder.call("send", """{"subject": "no kind"}"""),
                coder.call("reply", """{"re": "$assign", "kind": "started"}"""),
                coder.call("inbox", """{"unread": false}"""),
                coder.call("thread", """{"id": "$assign"}"""),
                coder.call("thread", """{"id": "m-panda-999999"}"""),
                coder.call("whoami"),
            ).map { it.text }
            answers += listOf(false, true, true, true).map { coder.stop(it).toString() }
            answers += listOf(
                coder.post("/mcp", toolsCall("whoami"), token = null),
                dead.post("/mcp", toolsCall("whoami")),
                coder.post("/mcp?token=${coder.token}", toolsCall("whoami"), token = null),
                dead.post("/hook/stop", """{"stop_hook_active": false}"""),
                coder.post("/hook/stop", "not the hook's body"),
            ).map { "${it.status} ${it.body}" }
        } finally {
            System.setOut(out)
            System.setErr(err)
            office.close()
        }

        val seen = office.written() + answers + output.toString()
        office.made.size shouldBe 3
        office.made.filter { token -> seen.any { token in it } } shouldBe emptyList()
    }

    "the API's and the engine's sources hold no cap value: neither names the stop gate's cap" {
        val sources = listOf("kp-seat-api/src/main", "kp-engine/src/main").flatMap { File(root, it).walk().filter { file -> file.extension == "kt" }.toList() }

        sources.filter { "stop_gate_blocks" in it.readText() }.map { it.name } shouldBe emptyList()
    }
})
