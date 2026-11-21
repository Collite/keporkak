package kp.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.model.SeatId

/**
 * KP/P3/S3.6 T1 — a tool's result in a seat's transcript is hidden, then cut (issue #49; Bora reads this). The seam: the
 * driver and S2.1's fake agent, whose tool result holds the session token it was given three times; the transcript the
 * driver writes. A transcript shows a tool's result shortened to its first 2000 characters (contracts.md §12, Amendment 31).
 */
class TheToolsResultSpec : StringSpec({
    "a tool's result that holds a secret is hidden, then cut: the secret at its start, across the cut, and wholly after it" {
        Office().use { office ->
            // The token is 43 characters: one at the start, a space and 1936 more put the second across the cut — its first
            // 20 characters before it — and the third comes wholly after.
            val agent = office.agent("""{"turns": [[{"do": "tool", "id": "t1", "title": "cat notes.txt"},
                {"do": "tool_update", "id": "t1", "status": "completed", "text": "${'$'}{KP_SESSION_TOKEN} ${"a".repeat(1936)}${'$'}{KP_SESSION_TOKEN}${"b".repeat(100)}${'$'}{KP_SESSION_TOKEN}"},
                {"do": "end"}]]}""")
            val driver = office.driver()
            // The hooks' address makes the driver hand the process its session token (contracts.md §11).
            val token = driver.start(office.session("coder-1", agent).copy(hookUrl = "http://127.0.0.1:9")).ok().token.value

            driver.prompt(SeatId("coder-1"), "read your notes").ok()

            token.length shouldBe 43
            val result = office.transcript("coder-1").single { " tool t1 completed" in it }
            result shouldContain "→ ‹hidden› aaaa"
            office.transcript("coder-1").filter { line -> token.windowed(12).any { it in line } } shouldBe emptyList()
        }
    }
})
