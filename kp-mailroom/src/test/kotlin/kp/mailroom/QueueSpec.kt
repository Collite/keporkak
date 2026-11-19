package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kp.model.Held
import kotlin.time.Duration.Companion.minutes

/** S1.4 T7 — QA's queue (robots/qa.yaml `queue:`): one request at a time, in the order of kinds given, then first come first served. */
class QueueSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    val certify = """{"task": "T5", "done": "D1", "verify": "the candidate"}"""
    val roll = """{"pin": "image-head-1", "smoke": "the stage smoke"}"""
    fun qa() = Office(walk = "H-3", others = (1..4).associate { "KP/P3/S3.$it" to "released" }, robots = true)
    fun Office.handed(): List<String> = readBack().records<Delivered>().filter { "qa" in it.to }.map { it.id }

    "the first request is handed over at once: kept and let go in one breath, its deadline counted from the hand-over" {
        val office = qa()

        val first = office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "args": $certify}""")

        office.readBack().story() shouldBe listOf("qa-request held by robot:QA-6", "qa-request delivered")
        office.readBack().records<Held>().single().until shouldBe "live.namespace_absent"
        office.readBack().records<Delivered>().single { it.id == first.id }.deadline?.value shouldBe "2026-11-16T11:00:00Z"
    }

    "the waiting go deploys first, then first come first served, each when the last one's wait is over" {
        val office = qa()

        val running = office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "args": $certify}""")
        val early = office.send("coder-2", """{"kind": "qa-request", "stage": "KP/P3/S3.2", "args": $certify}""")
        val late = office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.3", "args": $certify}""")
        val deploy = office.send("teamlead", """{"kind": "deploy-request", "stage": "KP/P3/S3.4", "args": $roll}""")
        repeat(3) { office.clock.advance(121.minutes); office.mailroom.tick() }

        office.handed() shouldBe listOf(running.id, deploy.id, early.id, late.id)
    }

    "a request the queue would keep that its kind refuses is refused at once, not kept" {
        val office = qa()
        office.facts.set("list.task_exists", false)

        val asked = office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "args": $certify}""")

        asked should refusedBy("qa-request", "{fact: list.task_exists, id: {arg: task}}")
        office.readBack().records<Held>() shouldBe emptyList()
    }
})
