package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
 * entries: a judgement through [Office], and the `kp inbox` view. What the service says at start is `DaemonProcessSpec`'s,
 * which starts it through `main`.
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

    "a judgement whose rule names no phase, effort or office reads them from the message's stage and the daemon's own office — a merge's stray changes, a stage exit's STATUS entries, a phase exit's last stage, chart and entries: each the fixture's, none unknown" {
        val walk = TableFacts(Walk.read(checkout.resolve("rules/walks/h1-prime.yaml")).facts)
        FactsOffice(beside = walk, states = mapOf("KP/P3/S3.1" to "passed")).use { office ->
            office.edit(office.project, "tasks-p3-s3.1-facts.md") { it.replace(Regex("(?m)^- \\[ \\] (T[0-9]+ .*)$"), "- [x] $1\n      done 1a2b3c4") }
            office.edit(office.keporkak, "README.md") { "$it changed\n" }

            office.send("teamlead", """{"kind": "merge", "stage": "KP/P3/S3.1", "subject": "merge S3.1"}""").status shouldBe Status.DELIVERED
            office.observedOf("merge")["git.stray_changes"] shouldBe "1"
            office.office.state.machine("stage", "KP/P3/S3.1")?.state shouldBe "frozen"
            office.journal.shouldBeEmpty()
        }
        FactsOffice(beside = walk, states = mapOf("KP/P3/S3.1" to "merged")).use { office ->
            office.edit(office.project, "tasks-p3-s3.1-facts.md") { it.replace("live: Panda", "live: none") }

            office.send("teamlead", """{"kind": "stage-exit", "stage": "KP/P3/S3.1", "subject": "S3.1 exits"}""").status shouldBe Status.DELIVERED
            office.observedOf("stage-exit").filterKeys { it.startsWith("status.") } shouldBe mapOf("status.entry_present" to "true", "status.entries" to "2")
            office.journal.shouldBeEmpty()
        }
        FactsOffice(beside = walk, states = mapOf("KP/P3/S3.1" to "exited")).use { office ->
            office.edit(office.project, "plan.md") { it.replace("S3.1[S3.1 Facts]", "S3.1[S3.1 Facts — exited]") }

            office.send("teamlead", """{"kind": "phase-exit-request", "stage": "KP/P3/S3.1", "subject": "P3 exits", "args": {"attest": ["floors_checked"]}}""") should
                refusedBy("phase-exit-request", """{"fact":"log.count","kind":"retro-report","scope":"phase","where":{"mode":"phase-retro"},"gt":0}""")
            office.observedOf("phase-exit-request").filterKeys { !it.startsWith("log.") } shouldBe
                mapOf("plan.last_stage_exited" to "true", "plan.chart_fresh" to "true", "status.entries" to "2")
            office.journal.shouldBeEmpty()
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
})
