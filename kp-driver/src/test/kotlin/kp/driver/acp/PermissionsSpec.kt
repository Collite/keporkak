package kp.driver.acp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kp.driver.Answer
import kp.driver.OfferedOption
import kp.driver.Office
import kp.driver.PermissionRequest
import kp.driver.ok
import kp.model.SeatId
import java.util.Collections

/** T5 — permission requests, for the next developer. */
class PermissionsSpec : StringSpec({
    val seat = SeatId("coder-1")
    fun options(vararg kinds: String) = kinds.map { OfferedOption("opt-$it", it, it) }

    "the answerer is handed the tool, its whole input and every option offered" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[
                {"do": "ask", "id": "p1", "title": "Edit kp.md", "kind": "edit", "input": {"file_path": "/wt/kp.md", "new_string": "a\nb"}, "options": ["allow_always", "allow_once", "reject_once"]},
                {"do": "end"}]]}""")
            val asked = Collections.synchronizedList(mutableListOf<PermissionRequest>())
            val driver = office.driver(answerer = { asked += it; Answer.ALLOW })
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "edit it").ok()

            asked shouldBe listOf(PermissionRequest(seat, "p1", "Edit kp.md", "edit", Json.parseToJsonElement("""{"file_path":"/wt/kp.md","new_string":"a\nb"}"""),
                options("allow_always", "allow_once", "reject_once")))
        }
    }

    "a request without its input is handed the input its tool call carried" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[
                {"do": "tool", "id": "p1", "title": "Terminal", "input": {"command": "git push"}},
                {"do": "ask", "id": "p1", "title": "Terminal", "options": ["allow_once", "reject_once"]},
                {"do": "end"}]]}""")
            val asked = Collections.synchronizedList(mutableListOf<PermissionRequest>())
            val driver = office.driver(answerer = { asked += it; Answer.DENY })
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "push").ok()

            asked.single().input.toString() shouldBe """{"command":"git push"}"""
            agent.received("permission").single()["kind"].toString() shouldBe "\"reject_once\""
        }
    }

    "the choice is a one-time option, or a denial — never an option that persists" {
        Permissions.choose(Answer.ALLOW, options("allow_always", "allow_once", "reject_once")).option shouldBe options("allow_once").single()
        Permissions.choose(Answer.DENY, options("reject_always", "allow_once", "reject_once")).option shouldBe options("reject_once").single()
        Permissions.choose(Answer.ALLOW, options("allow_always", "reject_once")).words shouldBe "reject_once: allow_once is not offered (offered: allow_always, reject_once)"
        Permissions.choose(Answer.DENY, options("allow_once", "reject_always")).words shouldBe "denied: reject_once is not offered (offered: allow_once, reject_always)"
        Permissions.choose(Answer.ALLOW, emptyList()).option shouldBe null
        val kinds = listOf("allow_once", "allow_always", "reject_once", "reject_always", "_vendor_special")
        for (mask in 0 until (1 shl kinds.size)) for (answer in Answer.entries) {
            val offered = options(*kinds.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toTypedArray())
            Permissions.choose(answer, offered).option?.kind shouldBeIn listOf("allow_once", "reject_once", null)
        }
    }

    "a request still waiting for its answer when the turn is cancelled is answered cancelled" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "ask", "id": "p1", "title": "Terminal", "input": {"command": "make"}, "options": ["allow_once", "reject_once"]}, {"do": "end"}]]}""")
            val waiting = CompletableDeferred<Unit>()
            val driver = office.driver(answerer = { waiting.complete(Unit); awaitCancellation() })
            driver.start(office.session("coder-1", agent)).ok()
            val turn = async { driver.prompt(seat, "build") }
            waiting.await()

            driver.cancel(seat).ok()

            turn.await().ok().stopReason shouldBe "cancelled"
            agent.received("permission").single()["outcome"].toString() shouldBe "\"cancelled\""
            office.transcript("coder-1") shouldContain """2026-11-15T10:00:00Z coder-1 permission p1: Terminal {"command":"make"} → cancelled: the turn was cancelled"""
        }
    }

    "an answerer that fails is a denial" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[{"do": "ask", "id": "p1", "title": "Terminal", "options": ["allow_once", "reject_once"]}, {"do": "end"}]]}""")
            val driver = office.driver(answerer = { error("no rules loaded") })
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "go").ok()

            agent.received("permission") shouldHaveSize 1
            agent.received("permission").single()["kind"].toString() shouldBe "\"reject_once\""
            office.transcript("coder-1") shouldContain "2026-11-15T10:00:00Z coder-1 permission p1: the answerer failed: no rules loaded"
        }
    }
})
