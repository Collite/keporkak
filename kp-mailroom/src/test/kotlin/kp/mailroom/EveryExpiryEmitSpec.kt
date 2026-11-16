package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.Delivered
import kp.rules.Effect
import kp.rules.Loaded
import kp.rules.Rules
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The kinds whose expiry, on master, declares an emit — each has its spec below. */
private val COVERED = setOf("qa-request", "computer-use-session", "review-request", "review-bind", "repair-done", "roll", "session-request")

/**
 * T2, T3 — every emit an expiry declares is sent `by: expiry:<kind>`, whether the expiry names a transition or not, and
 * reaches the party its rule names: the addressee check is for what a seat, a robot or Bora sends (contracts.md §2.1,
 * §4.1). Each emit of a kind's expiry is shown as `<by> → <delivered to>`; one that was refused shows `refused`.
 */
class EveryExpiryEmitSpec : StringSpec({
    afterTest {
        Office.everyLogIsWhole() shouldBe emptyList()
        Office.closeAll()
    }

    "every kind on master whose expiry declares an emit has a spec in this file" {
        val rules = (Rules.load(Texts().read()) as Loaded.Valid).ruleSet

        rules.kinds.values.filter { kind -> kind.expiry.any { it is Effect.Emit } }.map { it.name }.toSet() shouldBe COVERED
    }

    "qa-request: on expiry the asker is nudged" {
        val office = Office(seed = "building")
        office.facts.set("list.task_exists", true)
        office.facts.set("list.done_item_exists", true)
        office.facts.set("stage.live", "bp-dsk")
        office.send("coder-1", """{"kind": "qa-request", "stage": "$STAGE", "subject": "QA, please", "args": {"task": "T5", "done": "D1", "verify": "the candidate"}}""")

        office.clock.advance(2.hours)
        office.mailroom.tick()

        office.emits("qa-request") shouldBe listOf("expiry:qa-request → coder-1, board")
    }

    "computer-use-session: on expiry it is reported to Bora, and the asker is nudged" {
        val office = Office(seed = "building")
        office.facts.set("list.task_exists", true)
        office.facts.set("list.done_item_exists", true)
        office.facts.set("office.computer_use", "A")
        val session = office.send("qa-panda", """{"kind": "computer-use-session", "stage": "$STAGE", "subject": "a session", "args": {"task": "T5", "done": "D1", "purpose": "the login page"}}""")

        office.clock.advance(2.hours)
        office.mailroom.tick()

        office.emits("computer-use-session") shouldBe listOf("expiry:computer-use-session → qa-panda, board")
        office.readBack().expired(session.id).single().report shouldBe "bora"
    }

    "review-request: the first expiry nudges the teamlead" {
        val office = Office(seed = "building")
        office.play("review-request")

        office.clock.advance(1.hours)
        office.mailroom.tick()

        office.emits("review-request") shouldBe listOf("expiry:review-request → teamlead, board")
    }

    "review-bind: the first expiry nudges the teamlead" {
        val office = Office(seed = "ready_for_review")
        office.send("teamlead", """{"kind": "review-bind", "stage": "$STAGE", "subject": "bind", "args": {"reviewer": "reviewer-1"}}""")

        office.clock.advance(8.hours)
        office.mailroom.tick()

        office.emits("review-bind") shouldBe listOf("expiry:review-bind → teamlead, board")
    }

    "repair-done: the first expiry nudges the reviewer, the second the teamlead" {
        val office = Office(seed = "building")
        office.play("review-request", "review-bind", "verdict", "repair-done")

        office.clock.advance(8.hours)
        office.mailroom.tick()
        office.clock.advance(8.hours)
        office.mailroom.tick()

        office.emits("repair-done") shouldBe listOf("expiry:repair-done → reviewer-1, board", "expiry:repair-done → teamlead, board")
    }

    "roll: on expiry QA is nudged" {
        val office = Office(seed = "released")
        office.facts.set("stage.live", "bp-dsk")
        office.operations.holding = true
        office.send("teamlead", """{"kind": "deploy-request", "stage": "$STAGE", "subject": "deploy", "args": {"pin": "v1.2.0", "smoke": "the health check"}}""")
        office.send("qa-panda", """{"kind": "roll", "stage": "$STAGE", "subject": "roll forward", "args": {"operation": "forward", "pin": "v1.2.0"}}""")

        office.clock.advance(30.minutes)
        office.mailroom.tick()

        office.emits("roll") shouldBe listOf("expiry:roll → qa, board")
    }

    "session-request: on expiry the Pool Manager is nudged" {
        val office = Office()
        office.send("teamlead", """{"kind": "session-request", "subject": "a coder", "args": {"action": "add", "seat": "coder-2", "role": "coder"}}""")

        office.clock.advance(30.minutes)
        office.mailroom.tick()

        office.emits("session-request") shouldBe listOf("expiry:session-request → pool-1, board")
    }
})

/** Each message [kind]'s expiry made the mailroom send, as `<by> → <whom it was delivered to>`, or `<by> → refused`. */
private fun Office.emits(kind: String): List<String> {
    val log = readBack()
    return log.messages.filter { it.by == "expiry:$kind" }.map { emit ->
        "${emit.by} → " + (log.records<Delivered>().singleOrNull { it.id == emit.id.value }?.to?.joinToString() ?: "refused")
    }
}
