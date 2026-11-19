package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.log.LogWriter
import kotlin.concurrent.thread
import kotlin.io.path.appendText

/** T7 — the loose ends of review-010 (issue #21): the start's own work under the mailroom's lock; a failing check closes its offices. */
class ReviewFollowUpsSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a result a performer gives from its own thread while the mailroom starts is carried once the start is done" {
        val office = Office(seed = "proposed")
        office.operations.holding = true
        val approval = office.bora("""{"kind": "approval", "stage": "$STAGE", "subject": "approved"}""")
        office.stop()
        // No rule on master performs an operation at start; this office's rules have the interrupted worktree.create ask for register.rebuild.
        office.texts.edit("rules/stage-lifecycle.yaml", "  - op: worktree.create\n    eq: ok\n  effects: []\n",
            "  - op: worktree.create\n    eq: failed\n  effects:\n  - do: register.rebuild\n    with: {}\n")
        var answering: Thread? = null
        var answeredDuringStart: Boolean? = null

        office.mailroom = Mailroom.start(office.project, panda, office.texts, office.facts, { _, done ->
            answering = thread { done(OperationResult.Ok()) }.also { it.join(300) }
            answeredDuringStart = !answering!!.isAlive
        }, office.seats, office.clock)
        answering!!.join()

        answeredDuringStart shouldBe false
        office.readBack().ops(approval.id).withoutWakes() shouldBe
            listOf("worktree.create requested", "worktree.create failed interrupted", "register.rebuild requested", "register.rebuild ok")
    }

    "a log check that fails still closes every office it checked" {
        val office = Office()
        office.project.resolve("mail/_office/panda-2026-11.jsonl").appendText(
            """{"v":1,"seq":99,"at":"2026-11-16T09:00:00Z","event":"fact","fact":"bora.present","subject":"bora","value":true}""" + "\n")

        shouldThrow<AssertionError> { Office.checkAndCloseAll() }

        Office.opened shouldBe emptyList()
        LogWriter.open(office.project, panda).close()
    }
})
