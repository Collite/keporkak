package kp.daemon.hero

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.daemon.RuleFiles
import kp.daemon.checkout
import kp.rules.Loaded
import kp.rules.Rules

/**
 * KP/P1/S1.6 — The heroes: the acceptance specs of phase 1, written to be read (D-38). The seam (T1): the daemon as S1.5
 * starts it in rehearsal — the real loader, log, engine, mailroom and robots — on a throwaway project, with the seats
 * scripted from a walk of `rules/walks/` and the facts from its table; Bora answers with the real `kp` (`sudo`
 * simulated), and the clock is injected. Each step below plays the walk's steps it names and reads what the log then
 * tells: one line per message — sender→addressees, what became of it — with the moves and operations it caused.
 * Where a walk and its scenario differ, the walk is played and the step says so (Found lines of S1.6). The restart —
 * a daemon stopped after any line of H-1′ and started again — is S1.10's (D-75).
 */
class HeroesSpec : FreeSpec({
    extension(HeroReport) // what `just hero` prints

    "H-1′ runs from the proposal to the exit, with its stop and resume, its fix round, and the coder's seat dying and coming back" - {
        val h1 by lazy { autoClose(Hero("h1-prime")) }

        "1 · the TeamLead's assignment-proposal → Bora's approval from his CLI → assign wakes coder-1 → started" {
            h1.through(5) shouldBe listOf(
                "assignment-proposal teamlead→bora: delivered, class B, ticket #${h1.ticket(h1.id("assignment-proposal"))} · S2.1 planned→proposed (proposals 1) · asks notify.bora",
                "notify.bora ok",
                "approval bora→teamlead signed: delivered, class B · S2.1 proposed→assigned · asks worktree.create",
                "worktree.create ok · S2.1 assigned→assigned",
                "assign teamlead→coder-1: delivered, woke coder-1 · seat coder-1 ready→busy",
                "started coder-1→teamlead: delivered · S2.1 assigned→building",
            )
        }
        "2 · a missing wheel → stop SC-3 → the Principal's triage resumes the stage with a stub (1 of 2)" {
            h1.through(7) shouldBe listOf(
                "stop coder-1→principal, teamlead: delivered, class C · S2.1 building→stopped (stops 1)",
                "triage principal→coder-1, teamlead: delivered, woke coder-1 · S2.1 stopped→building (resumes 1) · found S2.1#missing-wheel found→triaged",
            )
        }
        "9 · coder-1's process dies → seat-gone (1 of 3) → re-seated with the inbox replayed" {
            h1.through(11) shouldBe listOf(
                "fact seat.process_gone (coder-1)",
                "seat-gone pool→teamlead, board (robot:POOL-1): delivered · S2.1 building→paused (seat_gone_events 1) · seat coder-1 busy→gone · asks seat.reseat",
                "seat.reseat ok",
                "re-seated pool→teamlead, coder-1 (robot:POOL-14): delivered, woke coder-1 · S2.1 paused→building · seat coder-1 gone→ready",
            )
        }
        "3 · review-request → TeamLead; the mailroom fans out found-batch to the Principal" {
            h1.through(13) shouldBe listOf(
                "review-request coder-1→teamlead, principal: delivered · S2.1 building→ready_for_review (review_requests 1)",
                "found-batch mailroom→principal (effect:T-17): delivered",
            )
        }
        "8, before the merge as the rules require · the T3 Found line triaged as a stage → pin-stage, Bora cc'd → ruling-request with a default → Bora rules" {
            h1.through(17) shouldBe listOf(
                "triage principal→coder-1, teamlead: delivered, class C, woke coder-1 · found S2.1#T3-follow-up found→triaged",
                "pin-stage principal→teamlead, bora: delivered, class C",
                "ruling-request principal→bora: delivered, class B, ticket #${h1.ticket(h1.id("ruling-request"))} · asks notify.bora",
                "notify.bora ok",
                "ruling bora→principal signed: delivered, class B · the ruling-request's thread asked→ruled",
            )
        }
        "4 · review-bind by the TeamLead → FIX FIRST with B1, B2" {
            h1.through(19) shouldBe listOf(
                "review-bind teamlead→reviewer-1, coder-1: delivered, woke reviewer-1, coder-1 · S2.1 ready_for_review→in_review",
                "verdict reviewer-1→coder-1, teamlead: delivered, woke coder-1 · S2.1 in_review→fix_round (fix_rounds 1)",
            )
        }
        "5 · repair-done with two response lines → re_review → PASS" {
            h1.through(21) shouldBe listOf(
                "repair-done coder-1→reviewer-1: delivered, woke reviewer-1 · S2.1 fix_round→re_review",
                "re-verdict reviewer-1→coder-1, teamlead, principal: delivered, woke coder-1 · S2.1 re_review→passed",
            )
        }
        "6 · merge through the daemon: Bora present, second key holds, audit clean → merged; Bora cc'd (C)" {
            h1.through(23) shouldBe listOf(
                "merge teamlead→daemon, bora: delivered, class C · S2.1 passed→passed (merge_attempts 1) · asks git.merge",
                "git.merge ok · S2.1 passed→merged",
            )
        }
        "7 · rolls: none, live: none → stage-exit: REGISTER rebuilt, project committed and pushed by the daemon → exited" {
            h1.rest() shouldBe listOf(
                "stage-exit teamlead→principal, coder-1, reviewer-1, retro, board: delivered, woke coder-1, reviewer-1 · S2.1 merged→merged · asks worktree.remove",
                "worktree.remove ok · S2.1 merged→merged · asks register.rebuild",
                "register.rebuild ok · S2.1 merged→merged · asks project.commit_push",
                "project.commit_push ok · S2.1 merged→exited · seat coder-1 ready→idle · asks seat.wake (stage-retro)", // the re-seated coder goes idle at the exit (D-80)
                "seat.wake ok",
            )
            val status = h1.kp("status", h1.stage()).text.lines()
            status shouldContain "stage REHEARSAL/P2/S2.1: exited · proposals 1 · stops 1 · resumes 1 · seat_gone_events 1 · review_requests 1 · fix_rounds 1 · merge_attempts 1"
            status shouldContain "waiting: none" // each reply of the walk answers what waited for it (`re`)
            h1.kp("inbox").text shouldBe "nothing waits for Bora\n"
        }
    }

    "H-2′ runs through one night on an injected clock, and Bora's return brings one digest" - {
        val h2 by lazy { autoClose(Hero("h2-prime", startsAt = "2026-11-13T22:00:00Z")) }

        "22:30 · kp queue S2.2 S2.3, then kp away → presence absent" {
            h2.at("22:30").shouldBeEmpty()
            h2.through(2) shouldBe listOf(
                "queue-approval bora→teamlead signed: delivered, class B",
                "away bora→mailroom signed: delivered, class B · presence present→absent",
            )
        }
        "02:10 · the TeamLead's assignment-proposal for S2.2 is in the queue → the Deputy's queued-approval → assign → started" {
            h2.at("02:10").shouldBeEmpty()
            h2.through(7) shouldBe listOf(
                "assignment-proposal teamlead→bora: delivered, class B, ticket #${h2.ticket(h2.id("assignment-proposal"))} · S2.2 planned→proposed (proposals 1) · asks notify.bora",
                "queued-approval deputy→teamlead, bora (robot:DEP-2): delivered · S2.2 proposed→assigned · asks worktree.create",
                "notify.bora ok",
                "worktree.create ok · S2.2 assigned→assigned",
                "assign teamlead→coder-1: delivered, woke coder-1 · seat coder-1 ready→busy",
                "started coder-1→teamlead: delivered · S2.2 assigned→building",
            )
        }
        "03:50 · review-request → bind" {
            h2.at("03:50").shouldBeEmpty() // the walk's `started` answers the assign (`re`): its wait is closed
            h2.through(9) shouldBe listOf(
                "review-request coder-1→teamlead, principal: delivered · S2.2 building→ready_for_review (review_requests 1)",
                "review-bind teamlead→reviewer-1, coder-1: delivered, woke reviewer-1, coder-1 · S2.2 ready_for_review→in_review",
            )
        }
        "04:40 · classify-request → held by the Deputy, its shadow ruling logged; S2.2 waits in in_review" {
            h2.at("04:40").shouldBeEmpty()
            h2.through(12) shouldBe listOf(
                "classify-request reviewer-1→bora: held by robot:DEP-9, ticket #${h2.ticket(h2.id("classify-request"))} · asks notify.bora · asks seat.wake (shadow)",
                "hold deputy→reviewer-1 (robot:DEP-9): delivered, woke reviewer-1 · the classify-request's thread asked→held",
                "notify.bora ok",
                "seat.wake ok",
                "shadow-ruling deputy→ledger: delivered",
            )
        }
        "05:20 · the tail reaches passed → merge with Bora absent: the Deputy holds it, the tail waits (deadman)" {
            h2.at("05:20").shouldBeEmpty() // the walk's review-bind answers the review-request (`re`): no nudge
            h2.through(15) shouldBe listOf(
                "merge teamlead→daemon: held by robot:DEP-3, ticket #${h2.ticket(h2.id("merge"))} · asks seat.wake (shadow)",
                "hold deputy→teamlead (robot:DEP-3): delivered · the merge's thread asked→held",
                "seat.wake ok",
                "shadow-ruling deputy→ledger: delivered",
            )
        }
        "06:10 · two FIX FIRST in a row on S2.3 → anomaly-stop → coder-2's seat paused" {
            h2.at("06:10").shouldBeEmpty()
            h2.through(21) shouldBe listOf(
                "verdict reviewer-1→coder-2, teamlead: delivered, woke coder-2 · S2.3 in_review→fix_round (fix_rounds 1)",
                "repair-done coder-2→reviewer-1: delivered, woke reviewer-1 · S2.3 fix_round→re_review",
                "re-verdict reviewer-1→coder-2, teamlead, principal: delivered, class C, woke coder-2 · S2.3 re_review→fix_round (fix_rounds 2)",
                "anomaly-stop deputy→teamlead, bora (robot:DEP-4): delivered, class C",
                // coder-2's wake with the re-verdict is still in flight: the pause waits in its inbox, and wakes it next (S2.4).
                "pause pool→coder-2, teamlead (robot:POOL-5): delivered, class C · seat coder-2 busy→busy · asks seat.cancel",
                "seat.cancel ok",
            )
        }
        "07:30 · kp anything → present; one digest; the held classification and the tail's merge are let go" {
            h2.at("07:30").shouldBeEmpty()
            h2.through(23) shouldBe listOf(
                "fact bora.acted (bora) · presence absent→present",
                "digest deputy→bora (robot:DEP-8): delivered, class C",
                "classify-request reviewer-1→bora let go: delivered, class B, ticket #${h2.ticket(h2.id("classify-request"))}",
                "merge teamlead→daemon, bora let go: delivered, class C, ticket #${h2.ticket(h2.id("merge"))} · tail passed→passed (merge_attempts 1) · asks git.merge",
            )
        }
        "Bora rules the classification as the shadow did, clears the anomaly — coder-2 resumes — and the tail merges" {
            h2.rest() shouldBe listOf(
                "ruling bora→reviewer-1 signed: delivered, class B, woke reviewer-1 · the classify-request's thread held→ruled",
                "ruling bora→deputy signed: delivered, class B · the repair-done's thread asked→ruled", // the re-verdict answers the repair-done (`re`)
                // The resume's own wake of coder-2 (SEAT-37) is the seat woken with it: told by "woke" (S2.4).
                "resume pool→coder-2, teamlead (robot:POOL-6): delivered, class C, woke coder-2 · seat coder-2 busy→busy",
                "git.merge ok · tail passed→merged",
            )
            h2.log().messages("digest").size shouldBe 1
            h2.kp("inbox").text shouldBe "nothing waits for Bora\n" // the merge the Deputy held was let go, and its ticket closed (S2.7 T3)
            h2.kp("status", "REHEARSAL").text.lines().filter { it.startsWith("Bora") || it.startsWith("stage") } shouldBe listOf(
                "Bora: present",
                "stage REHEARSAL/P0/S0.1: merged · merge_attempts 1",
                "stage REHEARSAL/P2/S2.2: in_review · proposals 1 · review_requests 1",
                "stage REHEARSAL/P2/S2.3: fix_round · fix_rounds 2",
            )
        }
    }

    "H-3 runs with QA: the candidate verified before review, the roll, and the roll-back when the smoke fails; and a stage with a live DONE and nothing to roll exits" - {
        val h3 by lazy { autoClose(Hero("h3")) }

        "the coder reaches the live task T5 → qa-request naming T5 and the DONE item, one job at a time in QA's queue → qa-report pass with its fixture" {
            h3.through(5)
            h3.through(7) shouldBe listOf(
                "qa-request coder-1→qa: held by robot:QA-6",
                "qa-request coder-1→qa let go: delivered",
                "qa-report qa→coder-1, teamlead: delivered, woke coder-1 · S3.2 building→candidate_deployed",
            )
        }
        "review, PASS with a follow-up issue filed, merge, release" {
            h3.through(16) shouldBe listOf(
                "review-request coder-1→teamlead, principal: delivered · S3.2 candidate_deployed→ready_for_review (review_requests 1)",
                "review-bind teamlead→reviewer-1, coder-1: delivered, woke reviewer-1, coder-1 · S3.2 ready_for_review→in_review",
                "verdict reviewer-1→coder-1, teamlead: delivered, woke coder-1 · S3.2 in_review→passed · asks issue.file",
                "issue.file ok",
                "merge teamlead→daemon, bora: delivered, class C · S3.2 passed→passed (merge_attempts 1) · asks git.merge",
                "git.merge ok · S3.2 passed→merged",
                "release teamlead→daemon, bora: delivered, class C · S3.2 merged→merged (release_attempts 1) · asks release.build",
                "release.build ok · S3.2 merged→merged · asks tag.create",
                "tag.create ok · S3.2 merged→released",
            )
        }
        "deploy-request → QA rolls the pin, runs the smoke → deploy-done → the exit" {
            h3.rest() shouldBe listOf(
                "deploy-request teamlead→qa, bora: held by robot:QA-6",
                "deploy-request teamlead→qa, bora let go: delivered, class C",
                "roll qa→daemon, bora: delivered, class C · S3.2 released→released · asks gitops.roll",
                "gitops.roll ok · S3.2 released→released",
                "deploy-done qa→teamlead, bora: delivered, class C · S3.2 released→deployed",
                "stage-exit teamlead→principal, coder-1, reviewer-1, retro, board: delivered, woke coder-1, reviewer-1 · S3.2 deployed→deployed · asks worktree.remove",
                "worktree.remove ok · S3.2 deployed→deployed · asks register.rebuild",
                "register.rebuild ok · S3.2 deployed→deployed · asks project.commit_push",
                "project.commit_push ok · S3.2 deployed→exited · seat coder-1 busy→idle",
            )
            h3.kp("status", h3.stage()).text.lines() shouldContain "stage REHEARSAL/P3/S3.2: exited · proposals 1 · review_requests 1 · merge_attempts 1 · release_attempts 1"
            h3.kp("inbox").text shouldBe "nothing waits for Bora\n"
        }
        "had the smoke failed: QA's word alone is refused; QA rolls back to the previous pin by itself → deploy-failed → tail_failed, Bora's" {
            val rollback = autoClose(Hero("h3-rollback"))
            rollback.rest() shouldBe listOf(
                "deploy-request teamlead→qa, bora: held by robot:QA-6",
                "deploy-request teamlead→qa, bora let go: delivered, class C",
                "roll qa→daemon, bora: delivered, class C · S3.2-rollback released→released · asks gitops.roll",
                "gitops.roll ok · S3.2-rollback released→released",
                """deploy-failed qa→teamlead: refused by deploy-failed: {"counter":"rollbacks_completed","gt":0}""",
                "roll qa→daemon, bora: delivered, class C · S3.2-rollback released→released · asks gitops.revert",
                "gitops.revert ok · S3.2-rollback released→released (rollbacks_completed 1)",
                "deploy-failed qa→teamlead, bora: delivered, class C · S3.2-rollback released→tail_failed",
            )
            rollback.kp("status", rollback.stage()).text.lines() shouldContain "stage REHEARSAL/P3/S3.2: tail_failed · rollbacks_completed 1"
        }
        "a stage with a live DONE and nothing to roll: after the merge, a stale candidate head is refused, the verified one exits — no release, no deploy (D-42)" {
            val noRoll = autoClose(Hero("no-roll-live"))
            noRoll.through(13)
            noRoll.rest() shouldBe listOf(
                """stage-exit teamlead→principal: refused by T-44: {"any":[{"fact":"stage.live","eq":"none"},{"fact":"log.count","kind":"qa-report","scope":"stage","where":{"outcome":"pass","sha":{"fact":"pr.head"}},"gt":0}]}""",
                "stage-exit teamlead→principal, coder-1, reviewer-1, retro, board: delivered, woke coder-1, reviewer-1 · S3.2-no-roll-live merged→merged · asks worktree.remove",
                "worktree.remove ok · S3.2-no-roll-live merged→merged · asks register.rebuild",
                "register.rebuild ok · S3.2-no-roll-live merged→merged · asks project.commit_push",
                "project.commit_push ok · S3.2-no-roll-live merged→exited · seat coder-1 busy→idle",
            )
            noRoll.kp("status", noRoll.stage()).text.lines() shouldContain "stage REHEARSAL/P3/S3.2: exited · proposals 1 · review_requests 1 · merge_attempts 1"
        }
    }

    "for every requirement on master, a message that breaks only that requirement is refused, naming it" {
        val sweep = Sweep.ofTheRulesOnMaster().also(HeroReport::sweep)

        sweep.failures.shouldBeEmpty()
        // Every requirement of every transition and every kind on master, read from the rules — D-81's T-80 and D-85's
        // SEAT-14 and SEAT-34 among them: each is swept but those the reasons below name, each with its reason.
        val rules = (Rules.load(RuleFiles(checkout).read()) as Loaded.Valid).ruleSet
        val requirements = rules.lifecycle.machines.values.flatMap { machine -> machine.transitions.flatMap { t -> List(t.require.size) { "${t.id}#${it + 1}" } } } +
            rules.kinds.values.flatMap { kind -> List(kind.require.size) { "${kind.name}#${it + 1}" } }
        sweep.onMaster shouldBe requirements.size
        sweep.swept shouldBe (requirements - sweep.exceptions.map { it.requirement }.toSet()).size
        sweep.reasons() shouldBe listOf(
            "68 · written the same way as a requirement of its kind, which is judged first: breaking it breaks the kind's, and the refusal names the kind: " +
                "T-01#1-6 (assignment-proposal), T-02#1 (approval), T-04#1-3 (queued-approval), T-06#1 (decline), T-08#1 (started), T-09#1 (stop), T-10#1 (stop), " +
                "T-11#1 (stop), T-12#1 (stop), T-13#4 (triage), T-15#4-5 (qa-report), T-16#1-4 (review-request), T-17#1-4 (review-request), T-18#1-3 (review-bind), " +
                "T-30#1 (ruling), T-31#1 (ruling), T-35#1-5 (merge), T-40#1 (release), T-44#1,3 (stage-exit), T-47#1 (deploy-done), T-48#1,3 (stage-exit), " +
                "T-50#3 (deploy-failed), T-51#1,3 (stage-exit), T-53#1 (ruling), T-54#1 (ruling), T-55#1 (ruling), T-56#1 (ruling), T-59#1-2 (seat-gone), " +
                "T-60#1 (re-seated), T-80#1-2 (seat-gone), FLAG_THREAD-01#1 (ruling), FLAG_THREAD-02#1 (hold), FLAG_THREAD-03#1 (ruling), SEAT-03#1 (assign), SEAT-06#1-2 (seat-gone), " +
                "SEAT-07#1 (re-seated), PHASE-01#1-5 (phase-exit-request), PHASE-02#1 (approval)",
            "36 · judged on an operation's result, which no message carries: nothing is refused — a result that breaks it fires nothing: T-03#1, T-05#1, T-36#1, " +
                "T-37#1-2, T-39#1-2, T-41#1, T-42#1, T-43#1-2, T-45#1, T-46#1-2, T-49#1, T-52#1, T-58#1, T-61#1, T-62#1, T-63#1, T-64#1, T-65#1, T-66#1, T-67#1, T-68#1, " +
                "T-69#1, T-71#1, T-73#1, SEAT-02#1, SEAT-04#1, SEAT-05#1, SEAT-41#1, SEAT-42#1, SEAT-15#1, PHASE-03#1, PHASE-04#1",
            "15 · a later candidate from the same state: when none fires, the refusal names the first candidate's first failure (contracts.md §4.1): " +
                "T-14#1 (T-13), T-20#1-3 (T-19), T-23#1-2 (T-21), T-24#1-2 (T-21), T-29#1-2 (T-21), T-31#2 (T-30), T-34#1-2 (T-21), T-54#2 (T-53), T-56#2 (T-55)",
            "10 · it tells two candidates from the same state apart: breaking it fires the other: T-21#3 (T-24), T-24#3 (T-29), T-25#1 (T-26), T-26#1 (T-25), " +
                "T-29#3 (T-34), T-34#3 (T-29), T-35#6 (T-38), T-38#1 (T-35), T-70#1 (T-72), T-72#1 (T-70)",
            "5 · judged when a deadline passes, which no message carries: nothing is refused — an expiry that breaks it fires nothing: T-32#1, FLAG_THREAD-04#1-2, FLAG_THREAD-05#1-2",
            "4 · the kind's args declaration accepts no value that breaks it: what would is refused as args first: T-47#2 (smoke), T-50#1 (outcome), T-50#2 (rollback), triage#1 (outcome)",
            "2 · another machine moves on the same message from any state: it is delivered: T-59#3 (SEAT-06), T-80#3 (SEAT-06)",
            "2 · judged when a fact changes, which no message carries: nothing is refused — a fact that breaks it fires nothing: PRESENCE-02#1, PRESENCE-03#1",
            "3 · breaking it means Bora is absent, and the Deputy holds the message until he is back: held, not refused: merge#1 (DEP-3), release#2 (DEP-14), deploy-request#3 (DEP-15)",
        )
        // A case is swept only when the refusal names the requirement's owner as well as its form (review-018 B1): a
        // transition's copy of its kind's requirement is refused under the kind's name, and one of T-21's under T-21's.
        sweep.exceptions.single { it.requirement == "T-01#1" } shouldBe Sweep.Exception("T-01", 1,
            "written the same way as a requirement of its kind, which is judged first: breaking it breaks the kind's, and the refusal names the kind", "assignment-proposal")
        sweep.exceptions.single { it.requirement == "T-24#1" } shouldBe Sweep.Exception("T-24", 1,
            "a later candidate from the same state: when none fires, the refusal names the first candidate's first failure (contracts.md §4.1)", "T-21")
        sweep.swept + sweep.exceptions.size shouldBe sweep.onMaster
        // Every transition fires in a case of its own, and taking it out of a copy of the rules turns that case red: since
        // D-80, `retire` has no move of a seat that is not idle, which the kind's own requirement kept from ever firing.
        sweep.uncontrolled.shouldBeEmpty()
        sweep.removalSurvivors.shouldBeEmpty()
        // Why each removal turned its case red (issue #37): the transition is absent, or the loader refuses the copy without it.
        sweep.removals.map { it.why }.toSet() shouldBe setOf(Sweep.ABSENT, Sweep.NOT_LOADED)
        sweep.removals.filter { it.why == Sweep.NOT_LOADED }.mapNotNull { it.problem }.size shouldBe sweep.removals.count { it.why == Sweep.NOT_LOADED }
    }

    "the sweep's exceptions are held against its closed list of reasons: a requirement no world it builds breaks is a failure, not an exception for its args (issue #37)" {
        val copy = kp.daemon.TestOffice.copyOfTheRules()
        try {
            val kinds = copy.resolve("rules/message-kinds.yaml")
            val started = "    require:\n    - fact: git.branch_exists\n    expiry: []\n  # ---- building\n"
            check(kinds.toFile().readText().contains(started))
            // `thread.hops` is a fact of the log the sweep builds no world for: nothing breaks the requirement alone.
            kinds.toFile().writeText(kinds.toFile().readText().replace(started, started.replace("    expiry: []", "    - fact: thread.hops\n      lt:\n        cap: hops_per_thread\n    expiry: []")))

            val sweep = Sweep.ofTheRulesOnMaster(copy)

            sweep.failures shouldContain """started#2 {"fact":"thread.hops","lt":{"cap":"hops_per_thread"}}: no world breaks it alone: the sweep builds none for its form"""
            sweep.exceptions.filter { it.reason !in Sweep.REASONS }.shouldBeEmpty()
            sweep.exceptions.none { it.requirement == "started#2" } shouldBe true
        } finally {
            copy.toFile().deleteRecursively()
        }
    }

    "a daemon stopped after any line of H-1′ and started again ends where the uninterrupted run does — or, an operation in flight, where the rules put its failure" {
        val restarts = Restarts.afterEveryLineOf("h1-prime").also(HeroReport::restarts)

        restarts.otherwise.map { "after line ${it.seq} (${it.told}): ${it.ending}" }.shouldBeEmpty()
        restarts.cuts.filter { it.ending.startsWith(Restarts.IN_FLIGHT) }.shouldNotBeEmpty()
        // A wake is an operation of its own (S2.4, D-76, Amendment 22): each judgement that wakes seats leaves a cut with a
        // wake in flight for each seat it woke, and the start wakes each, idle with its mail, again. Every cut with only wakes
        // in flight, however many, ends as the uninterrupted run, its "same end" comparing the kinds delivered and the
        // operations in flight as well. None is left with its message unread.
        restarts.wakesInFlight.filterNot { it.endsAsUninterrupted }.map { "after line ${it.seq} (${it.told}): ${it.ending}" }.shouldBeEmpty()
        // The cuts right after such a judgement, by what the start found in flight: the seven S1.10 counted among them —
        // review-bind's with both the reviewer's wake and the coder's it copies — each held to the end above. Those two
        // wakes run side by side, each on its seat's lane (Amendment 36): which ends first is the scheduler's, and the cut
        // after the first result holds the other one's — of either seat (review-032, B1).
        val oneOfReviewBinds = setOf("seat.wake of reviewer-1 for review-bind", "seat.wake of coder-1 for review-bind")
        restarts.wakesInFlight.filter { it.told == "judged" }.map { it.flights.joinToString() }
            .map { if (it in oneOfReviewBinds) ONE_OF_REVIEW_BINDS else it } shouldBe listOf(
            "seat.wake of coder-1 for assign", // S1.10's
            "seat.wake of coder-1 for triage", // S1.10's: the resume
            "seat.wake of coder-1 for re-seated",
            "seat.wake of coder-1 for triage", // S1.10's: the found-batch's triage
            "seat.wake of reviewer-1 for review-bind, seat.wake of coder-1 for review-bind", // S1.10's: two wakes in flight
            ONE_OF_REVIEW_BINDS, // one of the two judged, the other still in flight
            "seat.wake of coder-1 for verdict", // S1.10's
            "seat.wake of reviewer-1 for repair-done", // S1.10's
            "seat.wake of coder-1 for re-verdict", // S1.10's
            "seat.wake of the retro's own seat (stage-retro) for stage-exit", // a robot's own seat, woken in a mode
        )
        restarts.report().none { "a wake in flight" in it || "stays unread" in it } shouldBe true
    }
})

/** The cut between review-bind's two wake results, as the restart case lists it: what is in flight there is either seat's wake. */
private const val ONE_OF_REVIEW_BINDS = "one of review-bind's two seat.wakes, of either seat"
