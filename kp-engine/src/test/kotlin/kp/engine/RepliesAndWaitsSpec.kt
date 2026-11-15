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
})
