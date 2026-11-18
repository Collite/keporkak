package kp.daemon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kp.daemon.hero.Hero
import kp.engine.Datum

/**
 * S1.6 T2 — the harness: a walk's scripted seats driven through the daemon's real entry points, from a spec ([Hero]) and
 * from `just rehearse` ([Rehearsed]). For the next developer; the specs Bora reads are `hero/HeroesSpec`.
 */
class HarnessSpec : FreeSpec({
    "a step that does not leave the state its walk expects stops the play, naming the step, the expected and the actual state" {
        Hero("h1-prime", amend = { walk -> walk.withSteps(walk.steps.mapIndexed { at, step -> if (at == 4) step.with("expect", Datum.Fields(mapOf("state" to Datum.Text("stopped")))) else step }) }).use { hero ->
            val failure = shouldThrow<WalkFailure> { hero.through(5) }

            failure.message shouldBe "walk H-1-prime step 5 (coder-1 · started): expected state stopped, found building"
        }
    }

    "a step refused where its walk expects none stops the play, naming the refusal: its rule, its guard and the requirement that failed" {
        Hero("h1-prime", amend = { walk -> walk.withSteps(walk.steps.map { step -> if (step.id == "stop") step.with("args", Datum.Fields(mapOf("sc" to Datum.Text("SC-99")))) else step }) }).use { hero ->
            val failure = shouldThrow<WalkFailure> { hero.through(6) }

            failure.message shouldBe "walk H-1-prime step 6 (stop · coder-1 · stop): expected outcome delivered, found refused" +
                " — refused m-panda-000022 by stop: names a stop-condition id (SC-n) from the fence (failed: {\"arg\":\"sc\",\"in\":{\"fact\":\"list.stop_conditions\"}})"
        }
    }

    "a step a robot was to send stops the play when the robots did not send it, naming what they sent" {
        Hero("h1-prime", amend = { walk -> walk.withSteps(walk.steps.filter { it.fields["event"] == null }) }).use { hero ->
            val failure = shouldThrow<WalkFailure> { hero.through(8) }

            failure.message shouldBe "walk H-1-prime step 8 (seat-died · pool · seat-gone): robot:POOL-1 sent no seat-gone — the robots have sent nothing yet"
        }
    }

    "an operation the walk names ends when the walk reaches its step, not when the mailroom asks for it" {
        Hero("h1-prime").use { hero ->
            hero.through(2) shouldBe listOf(
                "assignment-proposal teamlead→bora: delivered, class B, ticket #5 · S2.1 planned→proposed (proposals 1) · asks notify.bora",
                "notify.bora ok",
                "approval bora→teamlead signed: delivered, class B · S2.1 proposed→assigned · asks worktree.create",
            )
            hero.through(3) shouldBe listOf("worktree.create ok · S2.1 assigned→assigned")
        }
    }

    "a rehearsal feeds the walk's facts to the daemon: the coder's seat dies, the robots take it, and the walk goes on to Bora's next step" {
        Rehearsed("h1-prime").use { office ->
            office.kp("approve", "5", root = true)

            office.rehearsal.awaitBora() shouldBe "ruling"
            office.log().story() shouldContainAll listOf("fact seat.process_gone", "seat-gone by robot:POOL-1 delivered", "re-seated by robot:POOL-14 delivered")
        }
    }
})
