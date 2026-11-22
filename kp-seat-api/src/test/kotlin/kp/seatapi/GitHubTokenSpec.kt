package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S3.7 T2 — `/hook/github-token` on the seat API's listener (contracts.md §4.4, Amendment 35), in front of answers of the
 * spec's: what each answer is on the wire, and what the daemon is handed. Minting is the daemon's (kp-daemon's specs).
 */
class GitHubTokenSpec : StringSpec({
    "an ask is handed the binding of its session token, and is answered {token, expires} and nothing else" {
        val asked = CopyOnWriteArrayList<Binding>()
        SeatOffice(github = { binding -> asked += binding; TokenAnswer.Handed("ghs_spec-token", "2026-11-22T10:00:00Z") }).use { office ->
            val coder = office.seat("coder-1", role = "coder")

            val answer = coder.post("/hook/github-token", "")

            answer shouldBe Answered(200, """{"token":"ghs_spec-token","expires":"2026-11-22T10:00:00Z"}""")
            asked shouldBe listOf(Binding("coder-1", "coder", "panda", STAGE, "claude-haiku-5-5", "low"))
        }
    }

    "a token withheld is 403 and a failed mint 502, each with its reason; neither writes to the log" {
        val answers = ArrayDeque(listOf<TokenAnswer>(TokenAnswer.Withheld("github.seats gives the role qa no App"), TokenAnswer.Failed("GitHub answered 422")))
        SeatOffice(github = { answers.removeFirst() }).use { office ->
            val qa = office.seat("qa-1", role = "qa")
            val lines = office.lines()

            val refused = qa.post("/hook/github-token", "")
            val failed = qa.post("/hook/github-token", "")

            refused shouldBe Answered(403, """{"error":"github.seats gives the role qa no App"}""")
            failed shouldBe Answered(502, """{"error":"GitHub answered 422"}""")
            office.lines() shouldBe lines
        }
    }

    "a dead or unknown session token is answered 401 and the daemon is asked nothing" {
        val asked = CopyOnWriteArrayList<Binding>()
        SeatOffice(github = { binding -> asked += binding; TokenAnswer.Handed("ghs_spec-token", "later") }).use { office ->
            val dead = office.seat("coder-2", role = "coder").also { office.tokens.revoke(it.token) }

            val answers = listOf(dead.post("/hook/github-token", ""), dead.post("/hook/github-token", "", token = "kp-spec-no-such-token"), dead.post("/hook/github-token", "", token = null))

            answers.map { it.status } shouldBe listOf(401, 401, 401)
            asked shouldBe emptyList()
        }
    }
})
