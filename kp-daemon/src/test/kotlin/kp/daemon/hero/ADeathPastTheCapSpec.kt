package kp.daemon.hero

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.daemon.WalkFailure
import kp.daemon.FakeSeating
import kp.engine.Datum
import java.nio.file.Files

/**
 * KP/P3/S3.4 T1 — a death past the cap, and what may be retired (D-118, approved; D-124). The seam: the daemon as a hero
 * plays each walk of the rule edits — the real loader, log, engine, mailroom and robots on a throwaway project — once per
 * order of a death's two reports, each step checked as it is played, and `scripts/check-rules.py --trace` gives the
 * walker's end of each order beside it. A request's expiry, which no walk can play, is played on the daemon's clock.
 */
class ADeathPastTheCapSpec : FreeSpec({
    extension(HeroReport) // what `just hero` prints

    /** [walk] in every order, each step as its file says, each order ending as the walker's: the ends, by stage and seat. */
    fun ends(walk: String): List<Pair<Map<String, String>, Map<String, String>>> {
        val orders = Orders.of(walk).also(HeroReport::orders)
        orders.played.filter { it.end != it.walkers } shouldBe emptyList()
        return orders.played.map { it.end.stages to it.end.seats }.distinct()
    }

    "a death past the cap: the stage paused, the seat gone, one ruling-request per death" - {
        "one seat: three deaths re-seated, the fourth asks Bora; his reseat brings seat and stage back, a fifth asks again, his recut ends the stage from paused" {
            ends("the-fourth-death") shouldBe listOf(mapOf("S9.9" to "recut") to mapOf("coder-1" to "retired"))
        }
        "two seats of a stage: the second dies while it is paused (T-82) and asks again; Bora rules on both requests, each step as the walker steps it" {
            ends("a-second-seat-dies-past-the-cap") shouldBe listOf(mapOf("S9.10" to "building") to mapOf("coder-1" to "ready", "reviewer-1" to "ready"))
        }
        "Bora's recut ends the stage from an active state (T-85), and its seat is then retired" {
            ends("a-recut-while-the-stage-is-not-paused") shouldBe listOf(mapOf("S9.11" to "recut") to mapOf("coder-1" to "retired"))
        }
        "a request that expires unanswered moves nothing: the stage stays paused and the seat gone; Bora is told" {
            Hero("a-request-past-the-cap-expires").use { hero ->
                hero.rest()
                // The hero's clock stands at the walk's start; the request's 24 hours pass. Its expiry's T-32 leaves
                // `ruling_pending` only: the stage, paused, does not move — its thread does, and Bora is told.
                hero.at("2026-11-18T09:01:00Z") shouldBe listOf("ruling-request's deadline passed, reported to bora · the ruling-request's thread asked→expired")
                hero.office.state.stage(hero.stage()) shouldBe "paused"
                hero.office.state.machine("seat", "coder-1")?.state shouldBe "gone"
            }
        }
    }

    "with fake seats: one coder killed four times — three re-seats by POOL-1, then the stage paused with one ticket in `kp inbox`; `kp rule <ticket> reseat seat=<seat>` and it goes on; killed a fifth time, `kp rule <ticket> recut` and it is recut" {
        // The walk names no level for its coder, which a seat needs to start: a rehearsal's seeds read it where an assignment
        // names it, a step's `args` — here the first step's, an event's, which no judgement reads.
        val level = Datum.Fields(mapOf("role" to Datum.Text("coder"), "level" to Datum.Text("senior S")))
        Hero("the-fourth-death", amend = { walk -> walk.withSteps(listOf(walk.steps.first().with("args", level)) + walk.steps.drop(1)) }, fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = "kp-no-tmux-here", panes = "kp-s3.4",
            evidence = Files.createTempDirectory("kp-s3.4-scratch-"))).use { hero ->
            hero.through(15) // the fourth death, its seat-gone and its ruling-request
            hero.office.state.stage(hero.stage()) shouldBe "paused"
            hero.kp("inbox").text.lines().filter { it.startsWith("#") }.map { it.substringAfter(" · ").substringBefore(" · from") } shouldBe
                listOf("B · ruling-request · ${hero.stage()}")
            hero.rest()
            hero.office.state.stage(hero.stage()) shouldBe "recut"
        }
    }

    "with fake seats, the player names the rule that fired on a seat's end and takes no other for it: POOL-1 named where POOL-11 fired fails the step" {
        val level = Datum.Fields(mapOf("role" to Datum.Text("coder"), "level" to Datum.Text("senior S")))
        // The fourth death's step as the walk has it — its sends seat-gone and the ruling-request — but naming POOL-1, the
        // rule under the cap, where POOL-11 or its twin POOL-13 fires.
        val underTheCap = Datum.Fields(mapOf("rules" to Datum.Items(listOf(Datum.Text("POOL-1"))),
            "sends" to Datum.Items(listOf(Datum.Text("seat-gone"), Datum.Text("ruling-request")))))
        Hero("the-fourth-death", amend = { walk ->
            walk.withSteps(walk.steps.mapIndexed { at, step -> if (at == 0) step.with("args", level) else if (at == 12) step.with("expect", underTheCap) else step })
        }, fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = "kp-no-tmux-here", panes = "kp-s3.4",
            evidence = Files.createTempDirectory("kp-s3.4-scratch-"))).use { hero ->
            hero.through(12)
            shouldThrow<WalkFailure> { hero.through(13) }.message shouldContain "expected rules [\"POOL-1\"]"
        }
    }

    "a daemon stopped after any line of the fourth death and started again ends where the uncut run does — a cut between a past-cap seat-gone and its ruling-request among them" {
        val restarts = Restarts.afterEveryLineOf("the-fourth-death").also(HeroReport::restarts)

        restarts.otherwise.map { "after line ${it.seq} (${it.told}): ${it.ending}" } shouldBe emptyList()
        // A cut is told by its line: a message as `<kind> <from>→<to>`. A seat-gone past the cap is the one whose next message is
        // the Pool Manager's ruling-request; the cuts from it up to that request, each ending as the uncut run.
        val message = Regex("^[a-z-]+ [a-z0-9-]+→[a-z0-9-]+$")
        val between = restarts.cuts.indices.filter { at ->
            restarts.cuts[at].told == "seat-gone pool→teamlead" && restarts.cuts.drop(at + 1).firstOrNull { message.matches(it.told) }?.told == "ruling-request pool→bora"
        }.map { at -> restarts.cuts.drop(at).takeWhile { it.told != "ruling-request pool→bora" } }
        between.size shouldBe 2 // the fourth death's and the fifth's
        between.flatten().filterNot { it.endsAsUninterrupted }.map { "after line ${it.seq} (${it.told}): ${it.ending}" } shouldBe emptyList()
    }

    "retire reaches a seat that will not work, its stage neither active nor paused" - {
        "a seat that never worked, ready, its stage planned (SEAT-44)" {
            ends("a-seat-that-never-worked-is-retired") shouldBe listOf(mapOf("S9.8" to "planned") to mapOf("coder-1" to "retired"))
        }
        "a provisioned seat, its seat.start failed (SEAT-45), retired on a session-request to remove it (POOL-8)" {
            ends("a-provisioned-seat-is-retired") shouldBe listOf(mapOf("S9.12" to "planned") to mapOf("coder-1" to "retired"))
        }
        "a seat re-seated in its running stage reads ready, and is not retired" {
            ends("a-re-seated-seat-in-its-stage-is-not-retired") shouldBe listOf(mapOf("S9.7" to "building") to mapOf("coder-1" to "ready"))
        }
    }
})
