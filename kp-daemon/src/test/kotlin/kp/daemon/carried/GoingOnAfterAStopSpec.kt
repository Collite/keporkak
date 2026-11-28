package kp.daemon.carried

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kp.daemon.NoSeats
import kp.daemon.Office
import kp.daemon.OnTheOffice
import kp.daemon.Walk
import kp.engine.Datum
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Message
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes

/**
 * S3.5 T1, T4 — going on after a stop (`OnTheOffice.course`, `Player.resume`; contracts.md §12, Amendment 46): a rehearsal
 * on the office stopped after any step of the walk and started again checks the steps the log holds, sends none of them
 * again, plays on from the first it does not hold, and ends as the uncut run; on a log that went another way than the
 * walk — another kind or sender at a step, a scripted step sent with other args, a step refused where the walk expects
 * it delivered, a later scripted step while an earlier step is missing — it is refused, naming the step (review-039 B2).
 */
class GoingOnAfterAStopSpec : StringSpec({
    "stopped after any step and started again, it plays on from the first step the log does not hold and ends as the uncut run".config(timeout = 30.minutes) {
        val walk = Walk.read(FIRST_CARRIED_STAGE)
        val sent = walk.steps.filter { it.who in setOf("teamlead", "pool") && it.text("by") == null }.map { "${it.who} ${it.kind}" }
        val runs = Executors.newFixedThreadPool(PARALLEL)
        val endings = (1 until walk.steps.size).map { cut ->
            runs.submit<String> {
                Carried().use { carried -> runCatching {
                    carried.play(cutAfter = cut) shouldBe null
                    val held = when (val course = OnTheOffice.course(kp.daemon.Script(walk, "TOY", onTheOffice = true), carried.lines())) {
                        is OnTheOffice.Course.Kept -> course.steps.size
                        is OnTheOffice.Course.Otherwise -> return@runCatching "after step $cut: the log went another way — ${course.why}"
                    }
                    val passed = carried.play()
                    val log = carried.lines()
                    // Each step the walk sends is on the log once: a step the log held was checked, not sent again.
                    val script = log.filterIsInstance<Message>().filter { it.by == "script:first-carried-stage" }.map { "${it.from.value} ${it.kind}" }
                    val resumed = carried.said.firstOrNull { it.startsWith("the log holds the walk's first") }
                    when {
                        passed != true -> "after step $cut: ${carried.said.lastOrNull { it.startsWith("the walk stops here") || it.startsWith("the rehearsal stopped") }}"
                        held < cut -> "after step $cut: the log held $held steps"
                        resumed == null -> "after step $cut: the second run did not take the walk up"
                        script != sent -> "after step $cut: the walk's messages on the log are $script"
                        carried.office.github.merged()?.second != "kp-merger[bot]" -> "after step $cut: no merge as kp-merger"
                        else -> "after step $cut: same end"
                    }
                }.getOrElse { "after step $cut: ${it.message?.lines()?.take(3)?.joinToString(" / ")} — said last: ${carried.said.takeLast(4).joinToString(" / ")}" } }
            }
        }.map { it.get() }
        runs.shutdown()
        println("the restart: the walk stopped after each of its ${walk.steps.size - 1} first steps and started again —\n" + endings.joinToString("\n"))
        endings.filterNot { it.endsWith(": same end") }.shouldBeEmpty()
    }

    "on a log that went another way than the walk it is refused, naming the step" {
        Carried().use { carried ->
            carried.play(cutAfter = 1) shouldBe null
            // Bora declined the proposal on the service, by hand, before the rehearsal went on.
            val proposal = carried.lines().filterIsInstance<Message>().single { it.kind == "assignment-proposal" }.id.value
            carried.byHand(Sender.Bora(signed = true), """{"kind": "decline", "stage": "$CARRIED", "subject": "decline", "re": "$proposal", "args": {}}""")
            val before = carried.lines().size

            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message!! shouldStartWith
                "the office's log went another way than walk first-carried-stage at step 2 (approval · bora · approval): the log holds decline "
            carried.lines().size shouldBe before
        }
    }

    "on a log where a scripted step was sent with other args it is refused, naming the step" {
        Carried().use { carried ->
            carried.play(cutAfter = 3) shouldBe null
            // The TeamLead's session-request, as a script of the same walk sent it — for another seat than the walk's — and
            // delivered: the slots were open.
            carried.byHand(Sender.Script("teamlead", "first-carried-stage"),
                """{"kind": "session-request", "stage": "$CARRIED", "subject": "a seat", "args": {"action": "add", "role": "coder", "level": "senior S", "seat": "coder-2"}}""",
                facts = FactSource { fact, _ -> if (fact.name == "budget.slot_open") Datum.Bool(true) else null })
            val request = carried.lines().filterIsInstance<Message>().last { it.kind == "session-request" }.id.value
            carried.lines().any { it is kp.model.Delivered && it.id == request } shouldBe true
            val before = carried.lines().size

            val refusal = shouldThrow<OnTheOffice.Refusal> { carried.start() }.message!!
            refusal shouldStartWith "the office's log went another way than walk first-carried-stage at step 4 (coder-asked · teamlead · session-request): " +
                "the log holds session-request $request with the args "
            refusal shouldContain "\"seat\":\"coder-2\""
            refusal shouldContain "where the walk sends "
            carried.lines().size shouldBe before
        }
    }

    "on a log where a scripted step was refused, while the walk expects it delivered, it is refused, naming the step" {
        Carried().use { carried ->
            // The walk's own proposal, sent while the stage before it had not exited: T-01's guard refuses it.
            carried.byHand(Sender.Script("teamlead", "first-carried-stage"),
                """{"kind": "assignment-proposal", "stage": "$CARRIED", "subject": "a proposal", "args": {"role": "coder", "level": "senior S", "seat": "coder-1"}}""",
                facts = FactSource { fact, _ -> if (fact.name == "plan.predecessors_exited") Datum.Bool(false) else null })
            val before = carried.lines().size

            val refusal = shouldThrow<OnTheOffice.Refusal> { carried.start() }.message!!
            refusal shouldStartWith "the office's log went another way than walk first-carried-stage at step 1 (proposal · teamlead · assignment-proposal): the log holds assignment-proposal "
            refusal shouldEndWith " refused where the walk expects it delivered"
            carried.lines().size shouldBe before
        }
    }

    "on a log that holds a later scripted step while an earlier step is missing it is refused, naming the step" {
        Carried().use { carried ->
            carried.play(cutAfter = 1) shouldBe null
            // The walk's own session-request, sent before Bora approved the proposal.
            carried.byHand(Sender.Script("teamlead", "first-carried-stage"),
                """{"kind": "session-request", "stage": "$CARRIED", "subject": "a seat", "args": {"action": "add", "role": "coder", "level": "senior S", "seat": "coder-1"}}""")
            val before = carried.lines().size

            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message!! shouldStartWith
                "the office's log went another way than walk first-carried-stage at step 2 (approval · bora · approval) is not on the log, yet the log holds the walk's later session-request "
            carried.lines().size shouldBe before
        }
    }
}) {
    private companion object {
        /** The cuts run three at a time: each is its own fixture office, ports and socket. */
        const val PARALLEL = 3
    }
}

/** [json] submitted as [sender] on the fixture office's log, by hand, on the service as it ran before — its facts from [facts]. */
private fun Carried.byHand(sender: Sender, json: String, facts: FactSource = FactSource { _, _ -> null }) {
    val office = Office.start(FIXTURE_OFFICE, office.project, office.root, facts, Operations { _, _ -> }, NoSeats, Clock { Instant.now() })
    office.submit(sender, Json.parseToJsonElement(json) as JsonObject)
    office.close()
}
