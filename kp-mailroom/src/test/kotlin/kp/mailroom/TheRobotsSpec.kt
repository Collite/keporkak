package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kp.model.Held
import kp.model.Op
import kp.model.Refused
import kp.model.plain
import kp.rules.Loaded
import kp.rules.Rules
import kotlin.time.Duration.Companion.minutes

/** H-2′'s three stages (design/11-c2-converged.md §3): the tail has passed; S2.2 is proposed tonight; S2.3 is in review. */
private const val TAIL = STAGE
private const val S22 = "KP/P2/S2.2"
private const val S23 = "KP/P2/S2.3"

/** Three stages QA works for (design/13-qa-options.md §1): two certifications, one tail to deploy. */
private const val LIVE_A = "KP/P3/S3.1"
private const val LIVE_B = "KP/P3/S3.2"
private const val TAIL_C = "KP/P3/S3.3"

/** The messages delivered to QA, in the order they were handed over. */
private fun Office.handedToQa(): List<String> = readBack().records<Delivered>().filter { "qa" in it.to }.map { it.id }

/**
 * S1.4 T1 — the robots. The seam: the mailroom of S1.3 with the robots host switched on, a clock the spec sets and
 * facts faked from a walk; what happened is read back from the log. After every spec, each office's log is checked
 * whole — and no line of it is `by: robot:` a rule that is not in `robots/` (Office.everyLogIsWhole).
 */
class TheRobotsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "H-2′ passes hour by hour: the queue, away, a queued approval, a held classification, a held merge, an anomaly stop, and the digest when Bora returns" {
        val night = Office(walk = "H-2-prime", seed = "passed", others = mapOf(S23 to "in_review"), robots = true,
            seats = listOf("coder-1", "coder-2", "reviewer-1"), seatStates = mapOf("coder-2" to "busy"), at = "2026-11-13T22:00:00Z")
        night.facts.set(S23, "seat.name", mapOf("coder" to "coder-2", "reviewer" to "reviewer-1", "reviewer-1" to "reviewer-1", "pool" to "pool-1"))

        night.at("22:30").bora("""{"kind": "queue-approval", "args": {"stages": ["$S22", "$S23"]}}""")
        night.at("22:31").bora("""{"kind": "away"}""")
        night.at("02:10").send("teamlead", """{"kind": "assignment-proposal", "stage": "$S22", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}""")
        night.at("02:20").send("teamlead", """{"kind": "assign", "stage": "$S22", "args": {"seat": "coder-1"}}""")
        night.at("02:30").send("coder-1", """{"kind": "started", "stage": "$S22"}""")
        night.at("03:50").send("coder-1", """{"kind": "review-request", "stage": "$S22", "args": {"attest": ["done_lines_hold"]}}""")
        night.at("04:00").send("teamlead", """{"kind": "review-bind", "stage": "$S22", "args": {"reviewer": "reviewer-1"}}""")
        night.at("04:40").send("reviewer-1", """{"kind": "classify-request", "stage": "$S22", "args": {"question": "blocker or follow-up?", "default": "follow-up"}}""")
        // The tail's head is reviewed and green, as H-2′'s stage `tail` holds it (D-108).
        night.facts.set(TAIL, "pr.review_on", "approved")
        night.facts.set(TAIL, "ci.state", "green")
        night.at("05:20").send("teamlead", """{"kind": "merge", "stage": "$TAIL"}""")
        val firstFix = night.at("05:40").send("reviewer-1", """{"kind": "verdict", "stage": "$S23",
            "args": {"verdict": "FIX FIRST", "sha": "head-1", "blockers": [{"id": "B1"}], "follow_ups": []}}""")
        night.facts.set(S23, "pr.head", "head-2"); night.facts.set(S23, "pr.commits_since", 1); night.facts.set(S23, "pr.blocker_responses", listOf("B1"))
        night.at("05:50").send("coder-2", """{"kind": "repair-done", "stage": "$S23", "args": {"verdict": "${firstFix.id}"}}""")
        night.at("06:10").send("reviewer-1", """{"kind": "re-verdict", "stage": "$S23",
            "args": {"verdict": "FIX FIRST", "sha": "head-2", "blockers": [{"id": "B1"}], "reviewed": ["B1"], "new_findings": [], "follow_ups": []}}""")
        night.at("07:25").command(signed = false)
        val stillAway = night.mailroom.state.presence("bora")
        night.at("07:30").command(signed = true)

        stillAway shouldBe "absent"
        night.readBack().story() shouldBe listOf(
            "queue-approval delivered",
            "away delivered",
            "presence present→absent",
            "assignment-proposal delivered",
            "queued-approval by robot:DEP-2 delivered",
            "assign delivered",
            "started delivered",
            "review-request delivered",
            "review-bind delivered",
            "classify-request held by robot:DEP-9",
            "hold by robot:DEP-9 delivered",
            "merge held by robot:DEP-3",
            "hold by robot:DEP-3 delivered",
            "verdict delivered",
            "repair-done delivered",
            "re-verdict delivered",
            "anomaly-stop by robot:DEP-4 delivered",
            "pause by robot:POOL-5 delivered",
            "presence absent→present",
            "digest by robot:DEP-8 delivered",
            "classify-request delivered",
            "merge delivered",
        )
        listOf(night.stage(S22), night.stage(TAIL)) shouldBe listOf("in_review", "merged")
    }

    "a robot's send is judged like a seat's: outside its role's kinds, or past a cap, it is refused, and the log names the robot's rule" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        office.texts.edit("robots/deputy.yaml", "  - send: digest\n    to: bora", "  - send: stop\n    to: bora")

        val outside = office.mailroom.reload()
        office.at("22:31").bora("""{"kind": "away"}""")
        repeat(4) { office.changed("budget.vs_daily_median", 2.5, params = mapOf("subscription" to "claude")) }

        (outside as Reload.Rejected).problems.map { it.toString() } shouldContain "robots/deputy.yaml: DEP-8: role 'deputy' may not send 'stop'"
        office.readBack().story().filter { it.startsWith("anomaly-stop") } shouldBe listOf(
            "anomaly-stop by robot:DEP-6 delivered",
            "anomaly-stop by robot:DEP-6 delivered",
            "anomaly-stop by robot:DEP-6 delivered",
            "anomaly-stop by robot:DEP-6 refused",
        )
        office.readBack().records<Refused>().single() should refusedBy("anomaly-stop", "caps.max_fires_default: 3, window: stage")
    }

    "a message a robot intercepts is held, not delivered, and is delivered when what holds it ends" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z")
        office.at("22:31").bora("""{"kind": "away"}""")

        val asked = office.at("04:40").send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker or follow-up?", "default": "follow-up"}}""")
        val whileAway = office.readBack()
        office.at("07:30").command(signed = true)

        asked.status shouldBe Status.HELD
        val held = whileAway.records<Held>().single { it.id == asked.id }
        listOf(held.by, held.until, held.ticket?.toLong(), held.deadline) shouldBe listOf("robot:DEP-9", "presence", held.seq.toLong(), null)
        whileAway.records<Delivered>().none { it.id == asked.id } shouldBe true
        val delivered = office.readBack().records<Delivered>().single { it.id == asked.id }
        listOf(delivered.to, delivered.ticket?.toLong(), delivered.deadline?.value) shouldBe listOf(listOf("bora"), held.seq.toLong(), "2026-11-15T07:30:00Z")
    }

    "QA's requests are handed over one at a time, deploys first, the next only when the last job's namespace is gone" {
        val office = Office(walk = "H-3", others = mapOf(LIVE_A to "building", LIVE_B to "building", TAIL_C to "released"), robots = true,
            seats = listOf("coder-1", "coder-2", "reviewer-1"))
        office.facts.set(LIVE_A, "live.namespace_absent", false)

        val first = office.send("coder-1", """{"kind": "qa-request", "stage": "$LIVE_A", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}""")
        val second = office.send("coder-2", """{"kind": "qa-request", "stage": "$LIVE_B", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}""")
        val deploy = office.send("teamlead", """{"kind": "deploy-request", "stage": "$TAIL_C", "args": {"pin": "image-head-1", "smoke": "the stage smoke"}}""")
        val whileTheFirstRuns = office.handedToQa()
        office.clock.advance(121.minutes)
        office.mailroom.tick()
        val afterItsWaitIsOverButItsNamespaceStays = office.handedToQa()
        office.changed("live.namespace_absent", true, stage = LIVE_A)

        whileTheFirstRuns shouldBe listOf(first.id)
        afterItsWaitIsOverButItsNamespaceStays shouldBe listOf(first.id)
        office.handedToQa() shouldBe listOf(first.id, deploy.id)
        office.mailroom.state.held.keys shouldBe setOf(second.id)
    }

    "DONE — the H-3 and H-3 roll-back walks pass through the mailroom with the states the Python walker gives" {
        val certification = WalkPlayed("H-3").compared
        val rollback = WalkPlayed("H-3-rollback").compared

        certification.map { it.mailroom } shouldBe certification.map { it.python }
        rollback.map { it.mailroom } shouldBe rollback.map { it.python }
        listOf(certification.last().stages, rollback.last().stages) shouldBe listOf(mapOf("S3.2" to "exited"), mapOf("S3.2-rollback" to "tail_failed"))
    }

    "DONE — the walk that seats a coder on the reserved model ends in a refusal naming the seat machine's rule, and no level of the roster points at a reserved model" {
        val played = WalkPlayed("reserved-model").compared
        val roster = (Rules.load(Texts().read()) as Loaded.Valid).ruleSet.pool.roster

        val refused = played.last().outcome as Refused
        refused should refusedBy("SEAT-01", "{fact: roster.model_allowed, seat: {arg: seat}}")
        played.map { it.mailroom } shouldBe played.map { it.python }
        roster.reserved shouldBe mapOf("claude-fable-5-2" to listOf("principal"))
        roster.levels.values.flatMap { it.values }.filter { it.model in roster.reserved } shouldBe emptyList()
    }

    "a rule marked for the persona is never executed" {
        val office = Office(walk = "H-3", seed = "building", robots = true)
        office.facts.set("office.computer_use", "B")
        val persona = Office.robotRules("robots/qa.yaml").filterValues { it }.keys

        val request = office.send("coder-1", """{"kind": "qa-request", "stage": "$STAGE", "args": {"task": "T5", "done": "D1", "verify": "the page, in a browser"}}""")
        office.send("qa", """{"kind": "qa-report", "re": "${request.id}",
            "args": {"outcome": "pass", "fixture": "fixtures/a", "command": "fake check", "counts": {"checks": 1}, "sha": "head-1"}}""")

        persona shouldBe setOf("QA-3", "QA-4", "QA-5")
        val log = office.readBack()
        val named = log.messages.map { it.by } + log.records<Held>().map { it.by } + log.records<Op>().mapNotNull { (it.plain()["with"] as? Map<*, *>)?.get("rule") as? String }
        named.filter { name -> persona.any { name.endsWith(it) } } shouldBe emptyList()
        log.records<Delivered>().single { it.id == request.id }.to shouldBe listOf("qa")
    }
})
