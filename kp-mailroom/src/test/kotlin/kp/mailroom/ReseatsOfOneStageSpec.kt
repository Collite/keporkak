package kp.mailroom

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.model.Message
import kp.model.Op

/**
 * KP/P3/S3.3 T7 — D-107, contracts.md §4.4 Amendment 39: a re-seat's `ok` is the word that the inbox was replayed. The
 * seam is the mailroom's own entries, with the robots on and master's rules, its performer keeping each request until
 * the spec answers it: two seats of one stage die, and their re-seats are judged in the order the spec chooses. A seat
 * being re-seated is busy — its session is replaced, then its replay's turn runs — until the spec says the turn ended.
 */
class ReseatsOfOneStageSpec : FreeSpec({
    "two seats of one stage re-seated come back whichever re-seat is judged first" - {
        for ((first, second) in listOf("reviewer-1" to "coder-1", "coder-1" to "reviewer-1")) {
            "$first's first, then $second's" {
                val office = twoSeatsGone()

                office.reseated(first)
                office.reseated(second)

                val state = office.mailroom.state
                state.machine("seat", "coder-1")!!.state shouldBe "ready"
                state.machine("seat", "reviewer-1")!!.state shouldBe "ready"
                state.stage(STAGE) shouldBe "building"
                office.reSeated().map { it.args!!.text("seat") } shouldContainExactly listOf(first, second)
                state.counter(STAGE, "seat_gone_events") shouldBe 2L
            }
        }
    }

    "a stage paused by one seat's death comes back when that seat's re-seat ends ok with a message that reached it meanwhile" {
        val office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z").apply {
            operations.keeping = true
            seats.busy += "coder-1"
            changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
        }
        office.mailroom.state.stage(STAGE) shouldBe "paused"

        // While its re-seat runs, the seat's context is reported past the threshold: a `compact` reaches it, unread.
        office.changed("seat.context_used", 0.8, stage = STAGE, params = mapOf("seat" to "coder-1"))
        val compact = office.readBack().lines.filterIsInstance<Message>().single { it.kind == "compact" }
        office.mailroom.state.unread("coder-1") shouldBe listOf(compact.id.value)

        office.reseated("coder-1")

        office.reSeated().map { it.args!!.text("seat") } shouldBe listOf("coder-1")
        office.mailroom.state.machine("seat", "coder-1")!!.state shouldBe "ready"
        office.mailroom.state.stage(STAGE) shouldBe "building"
    }

    "a message that reaches a seat while its re-seat runs stays unread through the re-seat's ok, and wakes the seat when the replay's turn ends" {
        val office = twoSeatsGone()

        // The reviewer's re-seat ends first: its `re-seated` is copied to the coder, whose own re-seat still runs.
        office.reseated("reviewer-1")
        val copy = office.reSeated().single { it.args!!.text("seat") == "reviewer-1" }
        office.mailroom.state.unread("coder-1") shouldBe listOf(copy.id.value)

        office.reseated("coder-1")
        office.mailroom.state.machine("seat", "coder-1")!!.state shouldBe "ready"
        office.mailroom.state.unread("coder-1").first() shouldBe copy.id.value
        office.wakesOf("coder-1") shouldBe emptyList()

        // The replay's turn ends: the seat is idle with the copy unread, and is woken with it, the oldest — then, its turn
        // ending at once, with its own `re-seated`'s copy, the next.
        val own = office.reSeated().single { it.args!!.text("seat") == "coder-1" }
        office.seats.busy -= "coder-1"
        office.mailroom.idle("coder-1")
        office.mailroom.settle()
        office.wakesOf("coder-1").map { it.cause } shouldBe listOf(copy.id.value, own.id.value)
        office.mailroom.state.unread("coder-1") shouldBe emptyList()
    }
})

/** S2.1 building, coder-1 and reviewer-1 ready; both their processes die, and both re-seats are requested and kept. */
private fun twoSeatsGone(): Office = Office(walk = "H-2-prime", seed = "building", robots = true, at = "2026-11-13T22:00:00Z").apply {
    operations.keeping = true
    seats.busy += listOf("coder-1", "reviewer-1")
    for (seat in listOf("coder-1", "reviewer-1")) changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to seat))
    readBack().records<Op>().filter { it.op == "seat.reseat" && it.phase == "requested" }.map { it.with!!.text("seat") } shouldBe listOf("coder-1", "reviewer-1")
    mailroom.state.stage(STAGE) shouldBe "paused"
}

/** [seat]'s kept re-seat ends `ok`, as the seats answer it: the seat and its session. */
private fun Office.reseated(seat: String) {
    operations.answer("seat.reseat", OperationResult.Ok(Datum.Fields(mapOf("seat" to Datum.Text(seat), "session" to Datum.Text("s-$seat")))), seat)
    mailroom.settle()
}

/** The `re-seated` messages on the log, in order. */
private fun Office.reSeated(): List<Message> = readBack().lines.filterIsInstance<Message>().filter { it.kind == "re-seated" }

/** The wakes of [seat] requested on the log. */
private fun Office.wakesOf(seat: String): List<Op> =
    readBack().records<Op>().filter { it.op == "seat.wake" && it.phase == "requested" && it.with?.text("seat") == seat }

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
