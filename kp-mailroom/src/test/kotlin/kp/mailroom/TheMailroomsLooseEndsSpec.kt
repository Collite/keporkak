package kp.mailroom

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.model.Delivered
import kp.model.Message
import kp.model.Op
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import kp.model.plain
import kotlin.io.path.readLines
import kotlin.time.Duration.Companion.hours

/**
 * KP/P1/S1.8 — the mailroom's loose ends. One spec per behaviour the stage list names (T1), at S1.3's seam: a message
 * submitted in a sender's name and the clock — facts, operations and seats faked, the log in a throwaway project,
 * master's rules — and what happened read back from the log. `Office` is in MailroomSupport.kt.
 */
class TheMailroomsLooseEndsSpec : FreeSpec({
    afterTest { Office.checkAndCloseAll() }

    "an expiry's nudge reaches the party its rule names, sent by the expiry of its kind — whether or not a transition fired" {
        val counting = Office(seed = "building")
        counting.play("review-request")
        val paused = Office(seed = "building")
        paused.play("review-request")
        paused.facts.set("seat.process_gone", true)
        paused.send("pool-1", """{"kind": "seat-gone", "stage": "$STAGE", "subject": "coder-1 is gone", "args": {"seat": "coder-1"}}""")

        counting.clock.advance(1.hours)
        counting.mailroom.tick()
        paused.clock.advance(1.hours)
        paused.mailroom.tick()

        counting.nudges() shouldBe listOf("expiry:review-request → teamlead, board: delivered")
        paused.nudges() shouldBe listOf("expiry:review-request → teamlead, board: delivered")
        counting.readBack().fired() shouldContain "T-74"
        paused.readBack().fired() shouldNotContain "T-74"
        paused.readBack().namedButNotFired() shouldBe emptyList()
    }

    "a count over a phase includes the stages that have exited, before and after a restart" {
        val office = Office(others = mapOf("KP/P2/S2.2" to "merged", "KP/P2/S2.3" to "merged"))
        // No rule outside the robots (S1.4) counts stage-exit over a phase: this office's rules have phase-exit-request read that count first.
        office.texts.edit("rules/message-kinds.yaml", "    - fact: plan.last_stage_exited\n",
            "    - {fact: log.count, kind: stage-exit, scope: phase, gte: 0}\n    - fact: plan.last_stage_exited\n")
        office.mailroom.reload()
        office.readyToExit()
        office.send("teamlead", """{"kind": "stage-exit", "stage": "KP/P2/S2.2", "subject": "S2.2 exits"}""")
        office.send("teamlead", """{"kind": "stage-exit", "stage": "KP/P2/S2.3", "subject": "S2.3 exits"}""")

        val before = office.stageExitsCountedOverThePhase()
        office.restart()
        val after = office.stageExitsCountedOverThePhase()

        listOf(office.stage("KP/P2/S2.2"), office.stage("KP/P2/S2.3")) shouldBe listOf("exited", "exited")
        listOf(before, after) shouldBe listOf("2", "2")
    }

    "a message about a stage whose file is closed is judged and written to the office's file" {
        val office = Office(seed = "merged")
        office.readyToExit()
        office.send("teamlead", """{"kind": "stage-exit", "stage": "$STAGE", "subject": "S2.1 exits"}""")
        val stageFile = office.project.resolve("mail/KP/p2-s2.1.jsonl").readLines()

        val report = office.send("retro-1", """{"kind": "retro-report", "stage": "$STAGE", "subject": "the stage's retro",
            "args": {"mode": "stage-retro", "report": "project:retro/s2.1.md", "candidates": []}}""")

        report.status shouldBe Status.DELIVERED
        office.project.resolve("mail/KP/p2-s2.1.jsonl").readLines() shouldBe stageFile
        office.officeFile().filter { it.plain()["id"] == report.id }.map { it::class.simpleName } shouldBe listOf("Message", "Delivered")
    }

    "a submission that cannot be addressed is written and refused, and its sender is told" {
        val office = Office(seed = "building")

        val report = office.send("qa-panda", """{"kind": "qa-report", "stage": "$STAGE", "subject": "a report that answers nothing",
            "args": {"outcome": "pass", "fixture": "fixture:qa/s2.1", "command": "just qa", "counts": {}, "sha": "head-1"}}""")

        listOf(report.status, report.requirement) shouldBe listOf(Status.REFUSED, "to: {re: from}")
        office.readBack().message(report.id).to.value shouldBe "mailroom"
        office.readBack().records<Refused>().single().id shouldBe report.id
    }

    "an invalid reload changes nothing and asks for Bora to be told, with its problems" {
        val office = Office()
        val inForce = office.mailroom.state.rules

        office.texts.edit("rules/engagement.yaml", "caps:", "caps: [")
        val rejected = office.mailroom.reload() as Reload.Rejected

        office.mailroom.state.rules shouldBe inForce
        office.readBack().rules().size shouldBe 1
        office.readBack().records<Op>().map { "${it.op} ${it.phase} ${it.cause}" } shouldBe listOf("notify.bora requested rules", "notify.bora ok rules")
        office.operations.requested.single().with shouldBe Datum.Fields(mapOf("problems" to Datum.Items(rejected.problems.map { Datum.Text("$it") })))
        rejected.problems.map { it.file }.toSet() shouldBe setOf("rules/engagement.yaml")
    }
})

/** Each nudge as `<by> → <to>: delivered | refused`. */
private fun Office.nudges(): List<String> {
    val log = readBack()
    return log.sent("nudge").map { nudge ->
        val delivered = log.records<Delivered>().singleOrNull { it.id == nudge.id.value }
        "${nudge.by} → ${delivered?.to?.joinToString() ?: nudge.to.value}: ${if (delivered != null) "delivered" else "refused"}"
    }
}

/** The facts a stage's exit reads that the H-1′ walk leaves elsewhere: STATUS has its entry, and no Found line is untriaged. */
private fun Office.readyToExit() {
    facts.set("status.entry_present", true)
    facts.set("status.entries", 5)
    facts.set("list.found_untriaged", 0)
}

/** A phase-exit-request about H-1′'s stage, judged; what its judgement observed of `log.count` of stage-exit over the phase. */
private fun Office.stageExitsCountedOverThePhase(): String? {
    val request = send("teamlead", """{"kind": "phase-exit-request", "stage": "$STAGE", "subject": "P2 exits"}""")
    val record = readBack().lines.single { it !is Message && it.plain()["id"] == request.id }
    return (record.plain()["observed"] as Map<*, *>)["log.count(kind=stage-exit, scope=phase)"]?.toString()
}

/** The office's file for November 2026, line by line. */
private fun Office.officeFile() = project.resolve("mail/_office/panda-2026-11.jsonl").readLines().map(::parseLine)

/** The transitions that fired: the rule of each `transition` record. */
private fun ReadBack.fired(): List<String> = records<Transition>().mapNotNull { it.rule }

/**
 * Each transition id a line names — a message's `by` or subject, a record's `rule` — that no `transition` record says
 * fired. A message's `args` are the rules' own words and are not read here.
 */
private fun ReadBack.namedButNotFired(): List<String> {
    val transition = Regex("T-[0-9]+")
    val named = messages.flatMap { transition.findAll(it.by + " " + it.subject).map { match -> match.value }.toList() } +
        lines.filter { it !is Message && it !is Transition }.mapNotNull { (it.plain()["rule"] as? String)?.takeIf(transition::matches) }
    return named.distinct() - fired().toSet()
}
