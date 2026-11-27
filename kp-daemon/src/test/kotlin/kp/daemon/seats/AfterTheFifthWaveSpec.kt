package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.mailroom.Status
import kp.model.Read
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

/**
 * KP/P3/S3.15 T1 — after the fifth wave's reviews: a role's seat (contracts.md §4.4, Amendments 47 and 48; §2.2). Bora
 * reads these. The seam: S3.14's daemon composed as the service composes it — the project's, GitHub's and the pool's
 * fact sources, no walk's table — with S2.1's fake agent as every seat's process, its seats started as the rules start
 * them ([ServiceOffice]). A start fails as the seats fail one: the stage's worktree is not there.
 */
class AfterTheFifthWaveSpec : StringSpec({
    "a message to a role whose seat's start was requested and failed waits for that seat and is read when it is started; with a second seat started for the role meanwhile, the role is the second's — in one stage, and with a seat of the same role in another" {
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

            // The worktree is made, and coder-2 is started: it reads the grant.
            office.worktree(S32).createDirectories()
            office.started("coder-2", "coder", S32, level = "junior")
            office.until("coder-2 read the grant at its start") { grant.id in office.read("coder-2") }
            office.prompts("coder-2").map { it.second }.any { grant.id in it } shouldBe true

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
        }
    }
})

private const val S31 = "KP/P3/S3.1"
private const val S32 = "KP/P3/S3.2"
private const val S34 = "KP/P3/S3.4"

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

/** The ids of the messages [seat] has read, by the log's `read` records. */
private fun ServiceOffice.read(seat: String): List<String> = lines().filterIsInstance<Read>().filter { it.seat == seat }.flatMap { it.ids }
