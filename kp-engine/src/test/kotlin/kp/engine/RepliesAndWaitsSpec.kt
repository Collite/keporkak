package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival

private const val ASSIGN = "m-panda-000030"
private val assignLine = """{"id":"$ASSIGN","thread":"$ASSIGN","from":"teamlead","to":"coder-1","kind":"assign","stage":"$STAGE","subject":"a","deadline":"2026-11-15T10:15:00Z","created":"$NOW","by":"seat:teamlead","args":{"seat":"coder-1"}}"""
private val assignDelivered = """{"event":"delivered","id":"$ASSIGN","to":["coder-1"],"class":"A"}"""
private val branch = table("now" to NOW, "git.branch_exists" to true)

/** S1.7 T3, T4, T7: which waiting message a delivery answers, what an expiry does to a wait, and what the fold keeps of waits. */
class RepliesAndWaitsSpec : StringSpec({
    "a delivery answers the message its re names only while that message waits" {
        val waiting = fold(log(move("stage", STAGE, "planned", "assigned"), assignLine, assignDelivered))
        val over = fold(log(move("stage", STAGE, "planned", "assigned"), assignLine, assignDelivered, """{"event":"expired","id":"$ASSIGN"}"""))
        val started = message("started", from = "coder-1", re = ASSIGN, thread = ASSIGN)

        supervised().judge(waiting, Arrival(started), branch).answers shouldBe ASSIGN
        supervised().judge(over, Arrival(started), branch).answers shouldBe null
        supervised().judge(waiting, Arrival(message("started", from = "coder-1")), branch).answers shouldBe null
    }
    "an expiry of a kind with no {wait: again} ends the wait, and so does one at its cap" {
        val stop = """{"id":"m-panda-000040","thread":"m-panda-000040","from":"coder-1","to":"principal","kind":"stop","stage":"$STAGE","subject":"s","deadline":"2026-11-15T10:00:00Z","created":"$NOW","by":"seat:coder-1","args":{"sc":"SC-3"}}"""
        val stopped = fold(log(move("stage", STAGE, "building", "stopped"), stop, """{"event":"delivered","id":"m-panda-000040","to":["principal"],"class":"C"}"""))
        val repaired = fold(log(move("stage", STAGE, "re_review", "re_review", extra = ""","counted":{"repair_expiries":1}"""),
            *sent("m-panda-000041", "repair-done", from = "coder-1", to = "reviewer-1")))

        supervised().judge(stopped, Trigger.Expiry("m-panda-000040"), table("now" to NOW, "bora.present" to true)).deadline shouldBe null
        supervised().judge(repaired, Trigger.Expiry("m-panda-000041"), table("now" to NOW)).deadline shouldBe null
        supervised().judge(repaired, Trigger.Expiry("m-panda-000041"), table("now" to NOW)).counted shouldBe mapOf("repair_expiries" to 2L)
    }
})
