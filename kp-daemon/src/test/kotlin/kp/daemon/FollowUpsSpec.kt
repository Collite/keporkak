package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kp.model.Message

/** S2.7 T6 — the follow-ups of reviews 016 to 019 that `kp` shows, in a rehearsal (contracts.md §6, Amendment 25). */
class FollowUpsSpec : FreeSpec({
    "sudo kp reverse with an item the log does not hold sends nothing, and says so — as kp rule answers one" {
        Rehearsed("h1-prime").use { office ->
            val before = office.log().lines.size

            val reverse = listOf(office.kp("reverse", "m-panda-999999", "minor", root = true), office.kp("reverse", "#999", "serious", root = true))
            val rule = listOf(office.kp("rule", "m-panda-999999", "stage", root = true), office.kp("rule", "#999", "stage", root = true))

            reverse shouldBe listOf(Ran(1, "kp: no message m-panda-999999 — kp inbox --all lists the tickets\n"), Ran(1, "kp: no ticket #999 — kp inbox --all lists them\n"))
            rule shouldBe reverse
            office.log().lines.drop(before).filterIsInstance<Message>().shouldBeEmpty()
        }
    }
})
