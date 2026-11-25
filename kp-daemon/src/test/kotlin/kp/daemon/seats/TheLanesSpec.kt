package kp.daemon.seats

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kp.daemon.GitHubOperations
import kp.daemon.OFFICE_STAGE
import kp.daemon.OfficeOnDisk
import kp.engine.Datum
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations

/**
 * KP/P3/S3.11 T7 (Collite/keporkak#65, 2; contracts.md §7, Amendments 36, 42): how the service names what an operation
 * works on — its lane — through the chain it runs: the seats in front of the operations on GitHub in front of the
 * office's performers. A stage's worktree operation works on its repository's main checkout — a removal's read from the
 * stage's `worktree.create` record — the project's operations on the project checkout, and an operation on GitHub on
 * none; the seats pass every answer of the chain behind them through, the lane and what an interrupted one left.
 */
class TheLanesSpec : FreeSpec({
    "a stage's worktree operations work on its repository's main checkout, the project's on the project checkout, one on GitHub on none — named by the office through the operations on GitHub" {
        OfficeOnDisk(mapOf(OFFICE_STAGE to "merged"), worktree = true).use { office ->
            val chain = GitHubOperations.of("panda", office.operations)

            listOf("worktree.create", "worktree.remove", "project.commit_push", "register.rebuild", "git.merge", "pr.close", "issue.file", "notify.bora")
                .associateWith { chain.on(request(it)) } shouldBe mapOf(
                    "worktree.create" to "the main checkout ${office.keporkak}",
                    "worktree.remove" to "the main checkout ${office.keporkak}",
                    "project.commit_push" to "the project checkout",
                    "register.rebuild" to "the project checkout",
                    "git.merge" to null,
                    "pr.close" to null,
                    "issue.file" to null,
                    "notify.bora" to null,
                )
        }
    }

    "the seats pass the lane and what an interrupted operation left through from the chain behind them, for their own operations and every other" {
        SeatsOffice().use { office ->
            val behind = object : Operations {
                override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) = done(OperationResult.Ok())
                override fun on(request: OperationRequest): String = "the lane of ${request.operation}"
                override fun left(request: OperationRequest): String = "what ${request.operation} left"
            }
            val chain = office.seats.operations(behind)

            listOf("seat.wake", "seat.reseat", "worktree.remove", "project.commit_push").associateWith { chain.on(request(it)) to chain.left(request(it)) } shouldBe mapOf(
                "seat.wake" to ("the lane of seat.wake" to "what seat.wake left"),
                "seat.reseat" to ("the lane of seat.reseat" to "what seat.reseat left"),
                "worktree.remove" to ("the lane of worktree.remove" to "what worktree.remove left"),
                "project.commit_push" to ("the lane of project.commit_push" to "what project.commit_push left"),
            )
        }
    }
})

/** [operation] for S3.3's stage, as a rule requests it — and for coder-1 where it is a seat's. */
private fun request(operation: String) = OperationRequest(operation, "m-panda-000042", Datum.Fields(mapOf(
    "stage" to Datum.Text(OFFICE_STAGE), "seat" to Datum.Text("coder-1"),
)))
