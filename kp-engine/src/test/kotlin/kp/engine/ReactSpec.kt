package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** S1.4 T2 — `react`: a robot's rules on each trigger of contracts.md §8, read by the guard's evaluator. */
class ReactSpec : StringSpec({
    val engine = supervised()
    val away = folded(after(State(), """{"event":"transition","machine":"presence","subject":"bora","from":"present","to":"absent","rule":"PRESENCE-01","cause":"m-panda-000001"}"""))

    fun Reaction.said(): List<String> = sends.map { "${it.rule}: ${it.kind} to ${it.to} ${Datum.of(it.args).json()}" } + wakes.map { "${it.rule}: wake ${it.mode}" }

    "a fact: the rule's `to` and `args` come from the trigger, and `{robot: …}` reads the robot's own threshold" {
        val silent = Trigger.Changed("seat.silent_for", mapOf("seat" to Datum.Text("coder-1")))
        fun react(silence: String) = engine.react(State(), silent, table("now" to NOW, "seat.silent_for(seat=coder-1)" to silence, "seat.tool_call_open_for(seat=coder-1)" to "0s"))

        react("6m").said() shouldBe listOf("""POOL-2: kill to coder-1 {"seat":"coder-1"}""")
        react("4m").said() shouldBe emptyList()
    }

    "a delivery, an expiry and an operation's result each fire the rules that name them, with what triggered them as `{trigger: …}`" {
        val delivered = folded(after(State(), *sent("m-panda-000010", "anomaly-stop", "deputy", "teamlead", """{"rule":"DEP-4","target":"stage","seat":"coder-2"}""")))
        val assigned = folded(after(State(), *sent("m-panda-000010", "assign", "teamlead", "coder-1", """{"seat":"coder-1"}""")))
        val gone = folded(after(State(), *sent("m-panda-000010", "seat-gone", "pool", "teamlead", """{"seat":"coder-1"}""")))

        engine.react(delivered, Trigger.Delivered("m-panda-000010"), table("now" to NOW)).said() shouldBe
            listOf("""POOL-5: pause to coder-2 {"seat":"coder-2","cause":"anomaly-stop"}""")
        engine.react(assigned, Trigger.Expiry("m-panda-000010"), table("now" to NOW, "seat.process_gone(seat=coder-1)" to true, "seat.protocol_closed(seat=coder-1)" to false,
            "seat.tool_call_open_for(seat=coder-1)" to 0)).said() shouldBe listOf("""POOL-10: seat-gone to teamlead {"seat":"coder-1"}""")
        engine.react(gone, Trigger.Result("m-panda-000010", "seat.reseat", ok = true), table("now" to NOW)).said() shouldBe
            listOf("""POOL-14: re-seated to teamlead {"seat":"coder-1"}""")
    }

    "an arrival is heard by an active robot only; a send in reply to it is `re` it, and presence is heard as it was before it moved" {
        val asked = message("classify-request", "reviewer-1", """{"question":"blocker?","default":"follow-up"}""", to = "bora")

        engine.react(away, Trigger.Arrival(asked), table("now" to NOW)).said() shouldBe listOf("""DEP-9: hold to reviewer-1 {}""", "DEP-9: wake shadow")
        engine.react(away, Trigger.Arrival(asked), table("now" to NOW)).sends.single().re shouldBe asked.id.value
        engine.react(State(), Trigger.Arrival(asked), table("now" to NOW)).said() shouldBe emptyList()
        engine.react(away, Trigger.Presence("present"), table("now" to NOW)).said() shouldBe listOf("DEP-8: digest to bora {}")
    }

    "the Deputy holds what it answers back to the asker; what it answers otherwise goes on" {
        val asked = message("classify-request", "reviewer-1", """{"question":"blocker?","default":"follow-up"}""", to = "bora")

        engine.react(away, Trigger.Arrival(asked), table("now" to NOW)).held shouldBe RobotHold("robot:DEP-9", "presence", ticket = true, kp.rules.MessageClass.B)
        engine.react(State(), Trigger.Arrival(asked), table("now" to NOW)).held shouldBe null
    }

    "a proposal for a stage in Bora's signed queue is answered with a queued approval, and goes on to its addressee" {
        val queued = folded(after(away,
            """{"id":"m-panda-000005","thread":"m-panda-000005","from":"bora","to":"teamlead","kind":"queue-approval","subject":"q","created":"$NOW","by":"bora-cli","signed":true,"args":{"stages":["$STAGE"]}}""",
            """{"event":"delivered","id":"m-panda-000005","to":["teamlead"],"class":"B"}"""), away)
        val proposal = message("assignment-proposal", "teamlead", """{"role":"coder","level":"senior","seat":"coder-1"}""", to = "bora")

        val reaction = engine.react(queued, Trigger.Arrival(proposal), table("now" to NOW, "stage.cut_at" to "2026-11-13T20:00:00Z"))

        reaction.said() shouldBe listOf("DEP-2: queued-approval to teamlead {}")
        reaction.held shouldBe null
    }

    "a robot's message is judged like a seat's: a kind its role does not own is refused, and the guard names the sender's rule" {
        val stop = message("stop", "deputy", """{"sc":"SC-3"}""", by = "robot:DEP-8", to = "principal")

        engine.judge(State(), Trigger.Arrival(stop), table("now" to NOW)).refusal() shouldBe ("stop" to "from: coder")
    }
})
