package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.Status

/**
 * KP/P3/S3.1 — the daemon asks `kp-facts` (T1's fifth sentence; T7). The office runs on a copy of master's rules whose
 * `pool/offices.yaml` names a project checkout and a main checkout the spec builds with the real `git`; its world is the
 * service's — [ProjectWorld] over the office's `project` and `repos` and the fold. Its seams are the daemon's own
 * entries: a judgement through [Office], and the `kp inbox` view.
 */
class ProjectAndGitFactsSpec : FreeSpec({
    "asked by a judgement and by `kp inbox`, the daemon answers a stage's three lines from a real checkout; a fact `kp-facts` does not hold stays unknown, and the judgement is written whole" {
        FactsOffice().use { office ->
            office.send("teamlead", """{"kind": "ruling-request", "stage": "KP/P3/S3.1", "subject": "a ruling", "args": {"default": "go on", "citation": "D-94"}}""")
            office.kp("inbox") shouldContain "   rolls: none · live: Panda · touches: kp-facts/\n"

            office.send("coder-1", """{"kind": "qa-request", "stage": "KP/P3/S3.1", "subject": "verify T1", "args": {"task": "T1", "done": "D1", "verify": "the facts"}}""")
            office.observedOf("qa-request").filterKeys { it.startsWith("list.") || it.startsWith("stage.") } shouldBe
                mapOf("list.task_exists(id=T1)" to "true", "list.done_item_exists(id=D1)" to "true", "stage.live" to "\"Panda\"")

            office.send("teamlead", proposal("KP/P3/S3.1")) should
                refusedBy("assignment-proposal", """{"fact":"pool.seat_available","role":{"arg":"role"},"level":{"arg":"level"}}""")
            office.observedOf("assignment-proposal").filterKeys { it.startsWith("plan.") || it.startsWith("pool.") } shouldBe
                mapOf("plan.predecessors_exited" to "true", "pool.seat_available(level=senior, role=coder)" to NOT_SUPPLIED)
            office.endsWithAJudgedLine() shouldBe true
        }
    }

    "with no `git` on the path, a judgement that reads `git.branch_exists` is written whole — refused, the fact unknown — and nothing is thrown" {
        FactsOffice(path = "").use { office ->
            office.send("coder-1", """{"kind": "started", "stage": "KP/P3/S3.1", "subject": "started"}""") should refusedBy("started", """{"fact":"git.branch_exists"}""")

            office.observedOf("started") shouldBe mapOf("git.branch_exists" to NOT_SUPPLIED)
            office.journal.joinToString("\n") shouldContain "git.branch_exists"
            office.journal.joinToString("\n") shouldContain "no `git` on the path"
            office.endsWithAJudgedLine() shouldBe true
        }
        FactsOffice().use { office ->
            office.send("coder-1", """{"kind": "started", "stage": "KP/P3/S3.1", "subject": "started"}""")
            office.observedOf("started") shouldBe mapOf("git.branch_exists" to "true")
        }
    }

    "the log's word reaches `kp-facts` through the fold: a stage the overview ticks is past until the log holds it in another state" {
        FactsOffice(beside = TableFacts(Walk.read(checkout.resolve("rules/walks/h1-prime.yaml")).facts)).use { office ->
            val wave = FactRef("plan.wave_of", mapOf("stage" to Datum.Text("KP/P3/S3.1")))

            office.world.observe(wave, "KP/P3/S3.1") shouldBe Datum.Text("past")
            office.send("teamlead", proposal("KP/P3/S3.1")).status shouldBe Status.DELIVERED
            office.office.state.machine("stage", "KP/P3/S3.1")?.state shouldBe "proposed"
            office.world.observe(wave, "KP/P3/S3.1") shouldBe Datum.Text("current")
        }
    }

    "at start the daemon says which project checkout and which main checkouts it reads" {
        FactsOffice().use { office ->
            office.world.reads shouldContain "project checkout ${office.project}"
            office.world.reads shouldContain "keporkak ${office.keporkak}"
            office.world.reads shouldContain "kp-toy ${office.toy}"
        }
    }
})
