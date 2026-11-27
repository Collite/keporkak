package kp.daemon.carried

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kp.daemon.NoSeats
import kp.daemon.Office
import kp.daemon.OnTheOffice
import kp.daemon.Walk
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
 * walk it is refused, naming the step.
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
            val office = Office.start(FIXTURE_OFFICE, carried.office.project, carried.office.root, FactSource { _, _ -> null }, Operations { _, _ -> }, NoSeats, Clock { Instant.now() })
            office.submit(Sender.Bora(signed = true), Json.parseToJsonElement("""{"kind": "decline", "stage": "$CARRIED", "subject": "decline", "re": "$proposal", "args": {}}""") as JsonObject)
            office.close()
            val before = carried.lines().size

            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message!! shouldStartWith
                "the office's log went another way than walk first-carried-stage at step 2 (approval · bora · approval): the log holds decline "
            carried.lines().size shouldBe before
        }
    }
}) {
    private companion object {
        /** The cuts run three at a time: each is its own fixture office, ports and socket. */
        const val PARALLEL = 3
    }
}
