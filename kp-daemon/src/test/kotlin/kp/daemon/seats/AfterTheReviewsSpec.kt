package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kp.daemon.seats.SeatsOffice.Companion.text
import kp.engine.Datum
import kp.engine.fold
import kp.model.Fact
import kp.model.Judged
import kp.model.Message
import kp.model.Read
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kp.daemon.checkout

/**
 * S2.9 T1 — after the reviews of S2.4 and S2.8: through S2.4's seam ([SeatsOffice]) — the daemon as a rehearsal starts
 * it, the real mailroom, seat API and driver, S2.1's fake agent as every seat's process, the world's facts and the other
 * operations from a walk's table, an injected clock. Each spec is named as its sentence in the list.
 */
class AfterTheReviewsSpec : StringSpec({
    "a seat's death is one event, whichever of its two reports comes first" {
        // coder-1's protocol closes while its process lives; coder-2's process ends while a child it leaves holds its
        // protocol open. Each does it once: a re-seated process passes the step. The rules are the checkout's and two
        // witnesses (review-023 B1): a rule on each end of a seat that acts on nothing, so that every report the
        // mailroom judges leaves its `fact` record.
        val scripts = mapOf(
            "coder-1" to """{"turns": [[{"do": "hangup", "once": "${SeatsOffice.holdPath("hung-up")}"}]]}""",
            "coder-2" to """{"turns": [[{"do": "leave", "seconds": 600, "once": "${SeatsOffice.holdPath("left")}"}]]}""",
        )
        SeatsOffice(root = witnessedRoot(), scripts = scripts).use { office ->
            office.send("principal", "triage", """{"outcome": "issue", "found": "1", "since": "m-panda-000001"}""")
            office.until("coder-1 re-seated") { "coder-1" in office.reSeated() }
            office.send("principal", "triage", """{"outcome": "issue", "found": "2", "since": "m-panda-000001"}""", stage = office.stage("S2.6"))
            office.until("coder-2 reported gone") { "coder-2" in office.seatGone() }
            office.childOf("coder-2").destroyForcibly() // its protocol closes now
            office.until("coder-2 re-seated") { "coder-2" in office.reSeated() }

            // Each death's two reports reached the mailroom, in either order...
            office.reported("coder-1") shouldBe listOf("seat.protocol_closed", "seat.process_gone")
            office.reported("coder-2") shouldBe listOf("seat.process_gone", "seat.protocol_closed")
            // ...and only the first acted: one `seat-gone`, by the rule on that end, one re-seat, one count.
            office.lines().filterIsInstance<Message>().filter { it.kind == "seat-gone" }.map { "${it.args!!.text("seat")} ${it.by}" } shouldBe
                listOf("coder-1 robot:POOL-12", "coder-2 robot:POOL-1")
            office.ops("seat.reseat").filter { it.phase == "requested" }.map { it.with!!.text("seat") } shouldBe listOf("coder-1", "coder-2")
            office.office.state.counter(office.stage(), "seat_gone_events") shouldBe 1L
            office.office.state.counter(office.stage("S2.6"), "seat_gone_events") shouldBe 1L
        }
    }

    "after the daemon is started again, every seat of a stage comes back — two seats of one stage too" {
        val project = SeatsOffice().use { office ->
            // reviewer-1 is started for S2.4, beside its coder — as S2.4's seats walk starts it.
            office.player.through("reviewer-report")
            office.until("reviewer-1 started") { office.ops("seat.start").any { it.phase == "ok" } }
            office.project
        }
        try {
            SeatsOffice(prepared = project).use { office ->
                office.until("every seat re-seated") { office.reSeated().size == 3 }

                office.ops("seat.reseat").filter { it.phase == "ok" }.map { it.result!!.text("seat") }.sortedBy { it } shouldBe listOf("coder-1", "coder-2", "reviewer-1")
                office.reSeated().sorted() shouldBe listOf("coder-1", "coder-2", "reviewer-1")
                // S2.4 lost two seats at once: two events, and it is building again.
                office.office.state.counter(office.stage(), "seat_gone_events") shouldBe 2L
                office.office.state.machine("stage", office.stage())!!.state shouldBe "building"
            }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    "a wake that fails leaves its message unread" {
        // coder-1 opens a tool call, and its protocol closes while its process lives: a seat with a tool call open is busy,
        // not gone — no rule re-seats it — and its session is dead.
        val script = """{"turns": [[{"do": "tool", "id": "t1", "title": "a long build"}, {"do": "hold", "until": "%s"}, {"do": "hangup"}]]}"""
        SeatsOffice(scripts = mapOf("coder-1" to script.format(SeatsOffice.holdPath("before-hangup")))).use { office ->
            office.send("principal", "triage", """{"outcome": "issue", "found": "1", "since": "m-panda-000001"}""")
            office.until("coder-1 in its turn, a tool call open") { office.received("coder-1").any { it.text("event") == "hold" } }
            office.clock.now = office.clock.now.plus(Duration.ofMinutes(1))
            office.release("before-hangup")
            office.until("coder-1's turn ended with its protocol") { office.seats.idle("coder-1") }

            val second = office.send("principal", "triage", """{"outcome": "issue", "found": "2", "since": "m-panda-000001"}""").id
            office.until("its wake answered") { office.ops("seat.wake").any { it.cause == second && it.phase != "requested" } }

            office.ops("seat.wake").filter { it.cause == second }.map { it.phase } shouldBe listOf("requested", "failed")
            office.lines().filterIsInstance<Read>().filter { second in it.ids } shouldBe emptyList()
            office.office.state.unread("coder-1") shouldBe listOf(second)

            // A wake that fails is no turn's end: nothing wakes the seat again for it — another message for the seat does, with that message.
            val third = office.send("principal", "triage", """{"outcome": "issue", "found": "3", "since": "m-panda-000001"}""").id
            office.until("the next wake answered") { office.ops("seat.wake").any { it.cause == third && it.phase != "requested" } }
            office.ops("seat.wake").map { "${it.phase} ${it.cause}" }.drop(2) shouldBe listOf("requested $second", "failed $second", "requested $third", "failed $third")
            office.office.state.unread("coder-1") shouldBe listOf(second, third)
        }
    }

    "two wakes of one message end apart, each by its seat" {
        // A `review-bind` goes to the reviewer and is copied to the coder: both idle, both woken.
        SeatsOffice(walk = "two-seats").use { office ->
            val bind = office.send("teamlead", "review-bind", """{"reviewer": "reviewer-1"}""").id
            office.until("both wakes answered") { office.ops("seat.wake").count { it.cause == bind && it.phase == "ok" } == 2 }

            // Each record of a wake names its seat: the request and its result alike. The two seats are woken side by side,
            // and their results come in either order (contracts.md §7, Amendment 36).
            val wakes = office.ops("seat.wake").filter { it.cause == bind }.map { "${it.phase} ${it.with?.text("seat")}" }
            wakes.take(2) shouldBe listOf("requested reviewer-1", "requested coder-1")
            wakes.drop(2) shouldContainExactlyInAnyOrder listOf("ok reviewer-1", "ok coder-1")
            // The first result ends its own seat's wake: through its judgement, the other's is still in flight.
            val first = office.ops("seat.wake").first { it.cause == bind && it.phase == "ok" }
            val judged = office.lines().first { it is Judged && it.seq > first.seq }
            fold(office.lines().filter { it.seq <= judged.seq }).operations.filter { it.op == "seat.wake" }
                .map { (it.with.fields["seat"] as Datum.Text).value } shouldBe listOf("reviewer-1", "coder-1") - first.with!!.text("seat")!!
        }
    }

    "the watch reports every `watch.every` of the injected clock, and not between" {
        // coder-1 in a turn with a tool call open; coder-2 idle, a tool call its turn opened still open. POOL-9 hears a tool
        // call open past its watch limit, 60 minutes: each report of one is on the log.
        val scripts = mapOf(
            "coder-1" to """{"turns": [[{"do": "tool", "id": "t1", "title": "a long build"}, {"do": "hold", "until": "${SeatsOffice.holdPath("watched")}"}]]}""",
            "coder-2" to """{"turns": [[{"do": "tool", "id": "t2", "title": "left open"}, {"do": "end"}]]}""",
        )
        SeatsOffice(scripts = scripts).use { office ->
            office.seated.watchEvery() shouldBe Duration.ofSeconds(30)
            val one = office.send("principal", "triage", """{"outcome": "issue", "found": "1", "since": "m-panda-000001"}""").id
            val two = office.send("principal", "triage", """{"outcome": "issue", "found": "2", "since": "m-panda-000001"}""", stage = office.stage("S2.6")).id
            office.until("coder-1 in its turn, coder-2's ended") {
                office.received("coder-1").any { it.text("event") == "hold" } && office.prompts("coder-2").any { two in it } && office.seats.idle("coder-2")
            }
            office.prompts("coder-1").single { one in it }

            office.clock.now = office.clock.now.plus(Duration.ofSeconds(61 * 60)) // 10:01:00
            office.until("the watch at 10:01:00", seconds = 10) { office.watched("coder-1").size == 1 }
            office.clock.now = office.clock.now.plus(Duration.ofSeconds(29)) // 10:01:29: no `watch.every` since the last
            Thread.sleep(1_000)
            office.clock.now = office.clock.now.plus(Duration.ofSeconds(1)) // 10:01:30
            office.until("the watch at 10:01:30", seconds = 10) { office.watched("coder-1").size == 2 }

            office.watched("coder-1") shouldBe listOf("2026-11-18T10:01:00Z", "2026-11-18T10:01:30Z")
            office.watched("coder-2") shouldBe emptyList()
        }
    }
})

/** The seats a `seat-gone` was sent for, in the log's order. */
private fun SeatsOffice.seatGone(): List<String> = lines().filterIsInstance<Message>().filter { it.kind == "seat-gone" }.map { it.args!!.text("seat")!! }

/** The seats a `re-seated` was sent for, in the log's order. */
private fun SeatsOffice.reSeated(): List<String> = lines().filterIsInstance<Message>().filter { it.kind == "re-seated" }.map { it.args!!.text("seat")!! }

/** The ends of [seat] the mailroom judged, in order: each `fact` record of one (a witness hears each). */
private fun SeatsOffice.reported(seat: String): List<String> =
    lines().filterIsInstance<Fact>().filter { it.subject == seat && it.fact in setOf("seat.process_gone", "seat.protocol_closed") }.map { it.fact }

/**
 * The checkout's rules with two witnesses beside the Pool Manager's (a spec's fixture, never the rules): each wakes the
 * pool's own seat on one end of a seat, whatever its state, so that a report the mailroom judges is a `fact` record
 * even when no rule of the checkout's acts on it.
 */
private fun witnessedRoot(): Path {
    val root = fixtureRoot { it }
    Files.delete(root.resolve("robots"))
    val robots = root.resolve("robots").createDirectories()
    checkout.resolve("robots").listDirectoryEntries("*.yaml").forEach { Files.copy(it, robots.resolve(it.name)) }
    val pool = robots.resolve("pool.yaml")
    val witnesses = listOf("seat.process_gone" to "POOL-91", "seat.protocol_closed" to "POOL-92").joinToString("") { (fact, id) ->
        "- id: $id\n  when: a spec's witness of each report of a seat's end\n  note: wake the report mode\n  'on':\n    fact: $fact\n  if: []\n  then:\n  - wake: report\n"
    }
    pool.writeText(pool.readText().replace("\nthresholds:\n", "\n$witnesses" + "thresholds:\n").also { check(it.contains("POOL-92")) { "pool.yaml has no thresholds to put the witnesses before" } })
    return root
}

/** The child [seat]'s last process left behind. */
private fun SeatsOffice.childOf(seat: String): ProcessHandle =
    received(seat).last { it.text("event") == "child" }.let { (it["pid"] as JsonPrimitive).longOrNull!! }.let { ProcessHandle.of(it).orElseThrow() }

/** When the watch reported an open tool call of [seat] that a robot heard: the `at` of each such `fact` record. */
private fun SeatsOffice.watched(seat: String): List<String> =
    lines().filterIsInstance<Fact>().filter { it.fact == "seat.tool_call_open_for" && it.subject == seat }.map { it.at.value }
