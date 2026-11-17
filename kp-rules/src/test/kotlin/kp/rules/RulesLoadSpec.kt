package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** S1.0 — The rules load. One spec per behaviour the stage list names; the seam is [Rules.load]. */
class RulesLoadSpec : StringSpec({
    "the rules, robots and pool files on master load without a problem" {
        val loaded = Rules.load(filesOnMaster())

        loaded.problems.shouldBeEmpty()
        (loaded as Loaded.Valid).ruleSet.kinds.keys shouldContain "review-request"
    }

    "a rule that names an unknown fact, operation, kind, cap, counter, attestation, state or mode is reported with its file and its id" {
        problemsAfter("rules/stage-lifecycle.yaml", "fact: plan.predecessors_exited", "fact: plan.predecesors_exited") shouldContain
            "rules/stage-lifecycle.yaml: T-01: unknown fact 'plan.predecesors_exited'"
        problemsAfter("rules/stage-lifecycle.yaml", "do: worktree.create", "do: worktree.creat") shouldContain
            "rules/stage-lifecycle.yaml: T-02: unknown operation 'worktree.creat'"
        problemsAfter("rules/stage-lifecycle.yaml", "kind: approval", "kind: aproval") shouldContain
            "rules/stage-lifecycle.yaml: T-02: unknown kind 'aproval'"
        problemsAfter("rules/message-kinds.yaml", "cap: stage_tasks_min", "cap: stage_task_min") shouldContain
            "rules/message-kinds.yaml: assignment-proposal: unknown cap 'stage_task_min'"
        problemsAfter("rules/stage-lifecycle.yaml", "count: proposals", "count: proposal") shouldContain
            "rules/stage-lifecycle.yaml: T-01: unknown counter 'proposal'"
        problemsAfter("rules/stage-lifecycle.yaml", "attest: floors_checked", "attest: floor_checked") shouldContain
            "rules/stage-lifecycle.yaml: PHASE-01: unknown attestation 'floor_checked'"
        problemsAfter("rules/stage-lifecycle.yaml", "to: proposed", "to: propsed") shouldContain
            "rules/stage-lifecycle.yaml: T-01: unknown state 'propsed' in stage"
        problemsAfter("robots/retro.yaml", "- wake: stage-retro", "- wake: stage-retr") shouldContain
            "robots/retro.yaml: RET-1: unknown own persona mode 'stage-retr'"
        // the places issue #4 found unresolved
        problemsAfter("robots/deputy.yaml", "since: queue-approval", "since: queue-aproval") shouldContain
            "robots/deputy.yaml: DEP-1: unknown kind 'queue-aproval'"
        problemsAfter("rules/stage-lifecycle.yaml", "engagement: presence.idle_hours_to_absent", "engagement: presence.idle_hour_to_absent") shouldContain
            "rules/stage-lifecycle.yaml: PRESENCE-02: unknown engagement value 'presence.idle_hour_to_absent'"
        problemsAfter("rules/stage-lifecycle.yaml", "emit: grant-request\n    to: principal", "emit: grant-request\n    to: principle") shouldContain
            "rules/stage-lifecycle.yaml: T-24: unknown destination 'principle'"
        problemsAfter("robots/pool.yaml", "to: teamlead", "to: teamleed") shouldContain
            "robots/pool.yaml: POOL-1: unknown destination 'teamleed'"
        problemsAfter("robots/pool.yaml", "robot: watchdog_silence", "robot: flap_window") shouldContain
            "robots/pool.yaml: POOL-2: unknown robot threshold 'flap_window'"
    }

    "what the Kotlin loader accepts and what it rejects, the Python validator accepts and rejects, on the shared fixtures under rules/fixtures/" {
        val scenarios = sharedFixtureScenarios()

        scenarios.size shouldBe 44
        scenarios.associate { it.name to loaderFailures(it) } shouldBe scenarios.associate { it.name to pythonFailures(it) }
    }

    "a pool file that breaks its shape is reported, and so is a roster level that points at a reserved model" {
        problemsAfter("pool/budgets.yaml", "total: 4,", "total: four,") shouldContain
            "pool/budgets.yaml: slots.total must be a positive integer"
        problemsAfter("pool/roster.yaml", "  claude-fable-5-2: [principal]", "  claude-opus-5-5: [principal]") shouldContain
            "pool/roster.yaml: level senior S points at claude-opus-5-5, which is reserved"
    }

    "a suite that executes no test fails the build" {
        val gradle = gradleCheckWithoutSpecs("kp-rules")
        gradle.exitCode shouldBe 1
        gradle.output shouldContain "kp-rules: the test task executed no spec"

        val python = pythonSuiteIn(emptyDirectory())
        python.exitCode shouldBe 1
        python.output shouldContain "the Python suite executed no test"
    }
})
