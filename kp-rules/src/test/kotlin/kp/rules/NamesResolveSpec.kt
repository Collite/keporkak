package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/** T4 — every name resolves (issue #4), on master's data with its anchors written out. */
class NamesResolveSpec : StringSpec({
    "the kind in a since: resolves" {
        problemsAfterExpanded("robots/deputy.yaml", "\"since\": \"queue-approval\"", "\"since\": \"queue-aproval\"") shouldContain
            "robots/deputy.yaml: DEP-1: unknown kind 'queue-aproval'"
    }

    "a since: {wake: …} names a mode of the robot's own persona" {
        problemsAfterExpanded("robots/retro.yaml", "\"since\": {\"wake\": \"stage-retro\"}", "\"since\": {\"wake\": \"shadow\"}") shouldContain
            "robots/retro.yaml: RET-2: unknown own persona mode 'shadow'"
    }

    "an {engagement: …} path resolves in engagement.yaml" {
        problemsAfterExpanded(LIFECYCLE, "\"engagement\": \"presence.idle_hours_to_absent\"", "\"engagement\": \"presence.idle_hour_to_absent\"") shouldContain
            "rules/stage-lifecycle.yaml: PRESENCE-02: unknown engagement value 'presence.idle_hour_to_absent'"
    }

    "the to: of an emit and of a send is a role or a party" {
        problemsAfterExpanded(LIFECYCLE, "\"emit\": \"grant-request\", \"to\": \"principal\"", "\"emit\": \"grant-request\", \"to\": \"principle\"") shouldContain
            "rules/stage-lifecycle.yaml: T-24: unknown destination 'principle'"
        problemsAfterExpanded(KINDS, "\"emit\": \"nudge\", \"to\": \"teamlead\"", "\"emit\": \"nudge\", \"to\": \"teamleed\"").any { it.endsWith("unknown destination 'teamleed'") } shouldBe true
        problemsAfterExpanded("robots/pool.yaml", "\"to\": \"teamlead\"", "\"to\": \"teamleed\"") shouldContain
            "robots/pool.yaml: POOL-1: unknown destination 'teamleed'"
    }

    "a {robot: …} resolves against the thresholds of that robot only" {
        problemsAfterExpanded("robots/pool.yaml", "\"robot\": \"watchdog_silence\"", "\"robot\": \"flap_window\"") shouldContain
            "robots/pool.yaml: POOL-2: unknown robot threshold 'flap_window'"
        problemsAfterExpanded(KINDS, "\"robot\": \"context_health\"", "\"robot\": \"median_factor\"") shouldContain
            "rules/message-kinds.yaml: compact: unknown robot threshold 'median_factor'"
        problemsAfterExpanded(KINDS, "\"robot\": \"context_health\"", "\"robot\": \"watchdog_silence\"").filter { it.contains("threshold") } shouldNotContain
            "rules/message-kinds.yaml: compact: unknown robot threshold 'watchdog_silence'"
    }

    "a state compared with a machine's state, and a reserved state name, resolve" {
        problemsAfterExpanded("robots/retro.yaml", "\"eq\": \"exited\"", "\"eq\": \"exitted\"") shouldContain
            "robots/retro.yaml: RET-1: unknown state 'exitted' in stage"
        problemsAfterExpanded(LIFECYCLE, "\"from\": \"*active\"", "\"from\": \"*any\"").any { it.endsWith("unknown state '*any' in stage") } shouldBe true
    }
})
