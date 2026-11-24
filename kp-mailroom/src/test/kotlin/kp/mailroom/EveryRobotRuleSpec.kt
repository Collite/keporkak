package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.model.Held
import kp.model.Op
import kp.model.plain
import kotlin.time.Duration.Companion.minutes

/** The robot rules an office's log shows fired: a message or a hold `by: robot:<rule>`, or a wake that names its rule. */
private fun Office.fired(): Set<String> = readBack().let { log ->
    val by = (log.messages.map { it.by } + log.records<Held>().map { it.by }).filter { it.startsWith("robot:") }.map { it.removePrefix("robot:") }
    by + log.records<Op>().mapNotNull { (it.plain()["with"] as? Map<*, *>)?.get("rule") as? String }
}.toSet()

/** An office on H-2′'s facts with the robots host on, its stage in [seed], at 22:00 on H-2′'s night. */
private fun night(seed: String? = "building", counters: String = "{}", seatStates: Map<String, String> = emptyMap()) =
    Office(walk = "H-2-prime", seed = seed, counters = counters, robots = true, at = "2026-11-13T22:00:00Z", seatStates = seatStates)

/** The same, with Bora gone away: the Deputy is active. */
private fun away(seed: String? = "building") = night(seed).apply { bora("""{"kind": "away"}""") }

/**
 * S1.4's DONE — every executed robot rule on master is triggered by a spec, and each spec's name says which. A spec
 * plays what the rule's `when` describes and passes when the log shows the rule fired.
 */
class EveryRobotRuleSpec : StringSpec() {
    private val named = LinkedHashSet<String>()

    private fun rule(id: String, what: String, scenario: () -> Office) {
        named += id
        "$id — $what" { scenario().fired() shouldContain id }
    }

    init {
        afterTest { Office.checkAndCloseAll() }

        // ---- the Deputy (robots/deputy.yaml): active while Bora is absent

        rule("DEP-1", "a proposal to Bora while he is away, its stage in no queue of his: held, hold to the asker, the shadow woken") {
            away(seed = null).apply { send("teamlead", """{"kind": "assignment-proposal", "stage": "$STAGE", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}""") }
        }
        rule("DEP-2", "a proposal for a stage in Bora's signed queue: queued-approval to the TeamLead") {
            night(seed = null).apply {
                bora("""{"kind": "queue-approval", "args": {"stages": ["$STAGE"]}}""")
                bora("""{"kind": "away"}""")
                send("teamlead", """{"kind": "assignment-proposal", "stage": "$STAGE", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}""")
            }
        }
        rule("DEP-3", "a merge while Bora is away: held, hold to the TeamLead") {
            away(seed = "passed").apply { send("teamlead", """{"kind": "merge", "stage": "$STAGE"}""") }
        }
        rule("DEP-14", "a release while Bora is away: held, hold to the TeamLead") {
            away(seed = "merged").apply { send("teamlead", """{"kind": "release", "stage": "$STAGE"}""") }
        }
        rule("DEP-15", "a deploy-request while Bora is away: held, hold to the TeamLead") {
            away(seed = "released").apply { send("teamlead", """{"kind": "deploy-request", "stage": "$STAGE", "args": {"pin": "image-head-1", "smoke": "the stage smoke"}}""") }
        }
        rule("DEP-4", "two FIX FIRST in a row on one stage: anomaly-stop to the TeamLead") {
            night(seed = "in_review", seatStates = mapOf("coder-1" to "busy")).apply {
                bora("""{"kind": "away"}""")
                val first = send("reviewer-1", """{"kind": "verdict", "stage": "$STAGE", "args": {"verdict": "FIX FIRST", "sha": "head-1", "blockers": [{"id": "B1"}], "follow_ups": []}}""")
                facts.set(STAGE, "pr.head", "head-2"); facts.set(STAGE, "pr.commits_since", 1); facts.set(STAGE, "pr.blocker_responses", listOf("B1"))
                send("coder-1", """{"kind": "repair-done", "stage": "$STAGE", "args": {"verdict": "${first.id}"}}""")
                send("reviewer-1", """{"kind": "re-verdict", "stage": "$STAGE",
                    "args": {"verdict": "FIX FIRST", "sha": "head-2", "blockers": [{"id": "B1"}], "reviewed": ["B1"], "new_findings": [], "follow_ups": []}}""")
            }
        }
        rule("DEP-5", "two seat-gone events within an hour: anomaly-stop to the Pool Manager") {
            away().apply {
                changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
                clock.advance(10.minutes)
                changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
            }
        }
        rule("DEP-6", "consumption above twice the daily median: anomaly-stop to the Pool Manager") {
            away().apply { changed("budget.vs_daily_median", 2.5, params = mapOf("subscription" to "claude")) }
        }
        rule("DEP-7", "a stray change in a main checkout: anomaly-stop to the TeamLead") {
            away().apply { changed("git.stray_changes", 1, stage = STAGE, params = mapOf("office" to "panda")) }
        }
        rule("DEP-8", "Bora's presence turns present: the digest to Bora") {
            away().apply { command(signed = true) }
        }
        rule("DEP-9", "a classify-request to Bora while he is away: held, hold to the asker") {
            away().apply { send("reviewer-1", """{"kind": "classify-request", "stage": "$STAGE", "args": {"question": "blocker?", "default": "follow-up"}}""") }
        }
        rule("DEP-10", "a ruling-request to Bora while he is away: held, hold to the asker") {
            away().apply { send("teamlead", """{"kind": "ruling-request", "stage": "$STAGE", "args": {"default": "resume", "citation": "project:STATUS.md"}}""") }
        }
        rule("DEP-11", "a phase-exit-request to Bora while he is away: held, hold to the asker") {
            away().apply { send("teamlead", """{"kind": "phase-exit-request", "stage": "$STAGE", "args": {"attest": ["floors_checked"]}}""") }
        }
        rule("DEP-12", "a recut-notice to Bora while he is away: held, hold to the asker") {
            away().apply { send("teamlead", """{"kind": "recut-notice", "stage": "$STAGE"}""") }
        }
        rule("DEP-13", "a computer-use-session to Bora while he is away, the office's class B: held, hold to the asker") {
            away().apply {
                facts.set("office.computer_use", "B")
                send("qa", """{"kind": "computer-use-session", "stage": "$STAGE", "args": {"task": "T5", "done": "D1", "purpose": "the page in a real browser"}}""")
            }
        }

        // ---- the Pool Manager (robots/pool.yaml): always active

        rule("POOL-1", "a seat's process gone, no tool call open, under the stage's cap: seat-gone to the TeamLead") {
            night().apply { changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-11", "a seat's process gone at the stage's cap of seat-gone events: pause to the seat") {
            night(counters = """{"seat_gone_events": 3}""").apply { changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-12", "a seat's protocol closed, no tool call open, under the stage's cap: seat-gone to the TeamLead") {
            night().apply { changed("seat.protocol_closed", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-13", "a seat's protocol closed at the stage's cap of seat-gone events: pause to the seat") {
            night(counters = """{"seat_gone_events": 3}""").apply { changed("seat.protocol_closed", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-14", "a re-seat that is ok — its prompt replayed the inbox (D-107): re-seated to the TeamLead") {
            night().apply { changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-2", "a seat silent past the watchdog, no tool call open: kill to the seat") {
            night().apply { changed("seat.silent_for", "6m", stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-3", "a seat's context past the health threshold: compact to the seat") {
            night().apply { changed("seat.context_used", 0.8, stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-4", "a seat's third refusal of the day: pause to the seat") {
            night().apply { repeat(3) { send("coder-1", """{"kind": "started", "stage": "$STAGE"}""") } }
        }
        rule("POOL-5", "an anomaly-stop that names a seat: pause to that seat") {
            away().apply { changed("git.stray_changes", 1, stage = STAGE, params = mapOf("office" to "panda")) }
        }
        rule("POOL-15", "an anomaly-stop whose target is provisioning: pause to the Pool Manager") {
            away().apply { changed("budget.vs_daily_median", 2.5, params = mapOf("subscription" to "claude")) }
        }
        rule("POOL-6", "a ruling that clears the cause of a pause: resume to the seat") {
            night().apply {
                val stop = send("coder-1", """{"kind": "stop", "stage": "$STAGE", "args": {"sc": "SC-3"}}""")
                bora("""{"kind": "ruling", "re": "${stop.id}", "args": {"outcome": "cleared", "seat": "coder-1", "cause": "anomaly-stop"}}""")
            }
        }
        rule("POOL-7", "a subscription's consumption past a warning of pool/budgets.yaml: the report mode woken") {
            night().apply { changed("budget.consumption", 0.75, params = mapOf("subscription" to "claude", "window" to "subscription")) }
        }
        rule("POOL-8", "a session-request to remove an idle seat: retire to the seat") {
            night(seatStates = mapOf("coder-1" to "idle")).apply {
                send("teamlead", """{"kind": "session-request", "stage": "$STAGE", "args": {"action": "remove", "seat": "coder-1"}}""")
            }
        }
        rule("POOL-16", "seats in use above a slot limit: the provision mode woken") {
            night().apply { changed("budget.slots_over", 1, params = mapOf("dimension" to "office")) }
        }
        rule("POOL-9", "a tool call open past the watch limit: the report mode woken") {
            night().apply { changed("seat.tool_call_open_for", "61m", stage = STAGE, params = mapOf("seat" to "coder-1")) }
        }
        rule("POOL-10", "an assign whose deadline passed, its seat gone: seat-gone to the TeamLead") {
            night(seed = "assigned").apply {
                send("teamlead", """{"kind": "assign", "stage": "$STAGE", "args": {"seat": "coder-1"}}""")
                facts.set(STAGE, "seat.process_gone", true)
                clock.advance(16.minutes)
                mailroom.tick()
            }
        }

        // ---- the Retro (robots/retro.yaml): always active, wakes its own seat only

        rule("RET-1", "a stage exits after a FIX FIRST or a stop: the stage-retro mode woken") {
            night(seed = "merged", counters = """{"fix_rounds": 1}""").apply {
                facts.set("status.entry_present", true)
                facts.set("status.entries", 5)
                send("teamlead", """{"kind": "stage-exit", "stage": "$STAGE"}""")
            }
        }
        rule("RET-2", "the 5th clean exit since the last stage-retro: the stage-retro mode woken") {
            val stages = (1..5).map { "KP/P4/S4.$it" }
            Office(walk = "H-2-prime", others = stages.associateWith { "merged" }, robots = true).apply {
                facts.set("status.entry_present", true)
                facts.set("status.entries", 5)
                stages.forEach { stage -> send("teamlead", """{"kind": "stage-exit", "stage": "$stage"}""") }
            }
        }
        rule("RET-3", "the last stage of a phase exits: the phase-retro mode woken") {
            night(seed = "merged").apply {
                facts.set("status.entry_present", true)
                facts.set("status.entries", 5)
                facts.set("plan.last_stage_exited", true)
                send("teamlead", """{"kind": "stage-exit", "stage": "$STAGE"}""")
            }
        }

        "every executed robot rule on master is triggered by a spec named after it" {
            val executed = listOf("deputy", "pool", "retro", "qa").flatMap { robot -> Office.robotRules("robots/$robot.yaml").filterValues { persona -> !persona }.keys }

            named shouldBe executed.toSet()
        }
    }
}
