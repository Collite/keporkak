package kp.daemon.seats

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.mailroom.Status
import kp.model.Envelope
import kp.model.Fact
import kp.model.Op
import kp.model.Read
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

/**
 * KP/P3/S3.15 T1 — after the fifth wave's reviews: a role's seat (contracts.md §4.4, Amendments 47, 48 and 49; §2.2).
 * Bora reads these. The seam: S3.14's daemon composed as the service composes it — the project's, GitHub's and the
 * pool's fact sources, no walk's table — with S2.1's fake agent as every seat's process, its seats started as the rules
 * start them ([ServiceOffice]). A start fails as the seats fail one: the stage's worktree is not there. A start that
 * ends `ok` sends no prompt of its own: a seat with unread mail then is woken with the oldest, as after a turn's end.
 */
class AfterTheFifthWaveSpec : StringSpec({
    "a seat whose start ends `ok` with unread mail is woken with the oldest, once — a message to its role while its start had failed; with a second seat started for the role meanwhile, the role is the second's — in one stage, and with a seat of the same role in another" {
        ServiceOffice(STAGES, budgets = ROOM).use { office ->
            // A seat of the same role in another stage, live: S3.1's coder.
            office.started("coder-1", "coder", S31, level = "junior")

            // S3.2's coder is asked for and reported; its start fails — S3.2's worktree is not there.
            office.worktree(S32).deleteExisting()
            office.startFails("coder-2", "coder", S32)

            // The Principal's grant to S3.2's reviewer, copied to its coder: the coder's copy is coder-2's, which nothing can
            // wake; S3.1's coder is not prompted.
            val grant = office.send("principal", "grant", """{"outcome": "grant"}""", S32)
            listOf(grant.status, grant.failed?.json()) shouldBe listOf(Status.DELIVERED, null)
            office.delivered(grant.id).to shouldBe listOf("reviewer", "coder-2", "teamlead")
            office.delivered(grant.id).woke shouldBe null
            office.read("coder-2") shouldBe emptyList()
            office.prompts("coder-1").none { grant.id in it.second } shouldBe true

            // The worktree is made, and coder-2 is started: its start's `ok`, then its wake with the grant, then its `read`.
            office.worktree(S32).createDirectories()
            office.started("coder-2", "coder", S32, level = "junior")
            office.until("coder-2 read the grant at its start") { grant.id in office.read("coder-2") }
            office.prompts("coder-2").map { it.second }.any { grant.id in it } shouldBe true
            val wake = office.wakes("coder-2").single()
            wake.cause shouldBe grant.id
            inOrder(office.startOk("coder-2"), wake, office.reads("coder-2").single { grant.id in it.ids })

            // S3.4's coder fails to start; a second coder is started there meanwhile, and the role is the second's.
            office.worktree(S34).deleteExisting()
            office.startFails("coder-3", "coder", S34)
            office.worktree(S34).createDirectories()
            office.started("coder-4", "coder", S34, level = "junior")
            val second = office.send("principal", "grant", """{"outcome": "grant"}""", S34)
            office.delivered(second.id).to shouldBe listOf("reviewer", "coder-4", "teamlead")
            office.delivered(second.id).woke shouldBe listOf("coder-4")
            office.until("coder-4 prompted with the grant") { office.prompts("coder-4").any { second.id in it.second } }
            office.read("coder-3") shouldBe emptyList()

            // Once: coder-2 was woken with the grant at its start and never again; S3.1's coder, with no mail, never.
            office.wakes("coder-2").map { it.cause } shouldBe listOf(grant.id)
            office.wakes("coder-1") shouldBe emptyList()
        }
    }

    "a seat whose start ends `ok` with two messages unread is woken with the older, and with the other once that turn has ended; a start with no unread mail wakes no one" {
        ServiceOffice(mapOf(S31 to "ready_for_review", S36 to "grant_pending"), budgets = ROOM, then = mapOf(S36 to "paused")).use { office ->
            // No mail: S3.1's coder is started, and its start wakes no one.
            office.started("coder-1", "coder", S31, level = "junior")
            office.wakes("coder-1") shouldBe emptyList()

            // S3.6 is paused, and its coder's start fails. The Pool Manager's `re-seated` takes the stage back to where it was
            // (T-60) and the Principal's grant moves it on (T-25): each is copied to the coder — coder-6's, and unread.
            office.worktree(S36).deleteExisting()
            office.startFails("coder-6", "coder", S36)
            val reseated = office.send("pool", "re-seated", """{"seat": "reviewer-6"}""", S36)
            val grant = office.send("principal", "grant", """{"outcome": "grant"}""", S36)
            listOf(reseated, grant).map { listOf(it.status, it.failed?.json()) } shouldBe List(2) { listOf(Status.DELIVERED, null) }
            office.delivered(reseated.id).to shouldBe listOf("teamlead", "coder-6")
            office.delivered(grant.id).to shouldBe listOf("reviewer", "coder-6", "teamlead")
            listOf(office.delivered(reseated.id).woke, office.delivered(grant.id).woke) shouldBe listOf(null, null)
            office.read("coder-6") shouldBe emptyList()

            // coder-6 is started: woken with the older at its start's `ok`; with the grant once that turn has ended.
            office.worktree(S36).createDirectories()
            office.started("coder-6", "coder", S36, level = "junior")
            office.until("coder-6 read both") { office.read("coder-6") == listOf(reseated.id, grant.id) }
            val wakes = office.wakes("coder-6")
            wakes.map { it.cause } shouldBe listOf(reseated.id, grant.id)
            val (first, then) = office.reads("coder-6")
            inOrder(office.startOk("coder-6"), wakes[0], first, office.turnsEnded("coder-6").first(), wakes[1], then)
            office.prompts("coder-6").map { (_, text) -> listOf(reseated.id, grant.id).filter { it in text } } shouldBe listOf(listOf(reseated.id), listOf(grant.id))

            // Still no one woke S3.1's coder.
            office.wakes("coder-1") shouldBe emptyList()
            office.prompts("coder-1") shouldBe emptyList()
        }
    }

    "a message to a role passes over a retired seat: its only seat retired — the role stays as written, and nothing is woken; a retired one and a later live one — the live one" {
        val retired = listOf(SeatAt("coder-2", "coder", S32, "junior", "retired"), SeatAt("coder-3", "coder", S34, "junior", "retired"))
        ServiceOffice(mapOf(S32 to "grant_pending", S34 to "grant_pending"), budgets = ROOM, seats = retired).use { office ->
            // S3.2's only coder was started and retired: the grant's copy to the coder stays the role's, and wakes no one.
            val grant = office.send("principal", "grant", """{"outcome": "grant"}""", S32)
            listOf(grant.status, grant.failed?.json()) shouldBe listOf(Status.DELIVERED, null)
            office.delivered(grant.id).to shouldBe listOf("reviewer", "coder", "teamlead")
            office.delivered(grant.id).woke shouldBe null
            office.wakes("coder-2") shouldBe emptyList()

            // S3.4's coder-3 was retired, and coder-4 is started after it: the grant's copy is coder-4's, and wakes it.
            office.started("coder-4", "coder", S34, level = "junior")
            val second = office.send("principal", "grant", """{"outcome": "grant"}""", S34)
            office.delivered(second.id).to shouldBe listOf("reviewer", "coder-4", "teamlead")
            office.delivered(second.id).woke shouldBe listOf("coder-4")
            office.until("coder-4 prompted with the grant") { office.prompts("coder-4").any { second.id in it.second } }
            office.wakes("coder-3") shouldBe emptyList()
        }
    }
})

private const val S31 = "KP/P3/S3.1"
private const val S32 = "KP/P3/S3.2"
private const val S34 = "KP/P3/S3.4"
private const val S36 = "KP/P3/S3.6"

/** The three stages: S3.1 ready for review; S3.2 and S3.4 waiting for the Principal's grant, whose `grant` is copied to the coder. */
private val STAGES = mapOf(S31 to "ready_for_review", S32 to "grant_pending", S34 to "grant_pending")

/** Master's budgets with room for eight seats and four stages of an effort in flight: a seat whose start failed holds its slot. */
private val ROOM = { budgets: String ->
    budgets.replace("stages_in_flight_per_effort: 2", "stages_in_flight_per_effort: 4")
        .replace("per_subscription: {claude: 4}", "per_subscription: {claude: 8}").replace("per_office: {panda: 4,", "per_office: {panda: 8,")
}

/** [stage]'s worktree, as the seats find it. */
private fun ServiceOffice.worktree(stage: String) = roots.worktrees.resolve(Seats.worktree(stage)!!)

/** [seat] asked for and reported as [role] on [stage], as [ServiceOffice.started] does, and its `seat.start` ended `failed`. */
private fun ServiceOffice.startFails(seat: String, role: String, stage: String) {
    val report = report(seat, role, stage, request(seat, role, stage, "junior"))
    report.status shouldBe Status.DELIVERED
    until("$seat's start failed") { ops("seat.start").any { it.phase == "failed" && it.cause == report.id } }
}

/** The log's `read` records of [seat]. */
private fun ServiceOffice.reads(seat: String): List<Read> = lines().filterIsInstance<Read>().filter { it.seat == seat }

/** The ids of the messages [seat] has read, by the log's `read` records. */
private fun ServiceOffice.read(seat: String): List<String> = reads(seat).flatMap { it.ids }

/** The `seat.wake` requests of [seat], in order. */
private fun ServiceOffice.wakes(seat: String): List<Op> =
    ops("seat.wake").filter { it.phase == "requested" && (it.with?.get("seat") as? JsonPrimitive)?.content == seat }

/** The `ok` of [seat]'s start: the one start of it that ended so. */
private fun ServiceOffice.startOk(seat: String): Op =
    ops("seat.start").single { it.phase == "ok" && (it.result?.get("seat") as? JsonPrimitive)?.content == seat }

/** The records [seat]'s turns left as they ended: `seat.usage`, window `turn` (contracts.md §4.4, Amendment 44). */
private fun ServiceOffice.turnsEnded(seat: String): List<Fact> = lines().filterIsInstance<Fact>().filter { record ->
    record.fact == "seat.usage" && (record.params?.get("seat") as? JsonPrimitive)?.content == seat && (record.params?.get("window") as? JsonPrimitive)?.content == "turn"
}

/** [records] are on the log in the order given. */
private fun inOrder(vararg records: Envelope) = withClue("in this order: " + records.joinToString(" · ") { "${it.seq} ${SeatsOffice.told(it)}" }) {
    records.map { it.seq }.zipWithNext().all { (before, after) -> before < after } shouldBe true
}
