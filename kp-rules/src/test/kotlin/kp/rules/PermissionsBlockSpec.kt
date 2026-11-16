package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** S2.6 T3 — `engagement.yaml:permissions`: its keys and their values, as check-rules.py reads them, for the next developer. */
class PermissionsBlockSpec : StringSpec({
    val block = "  write_inside: allow  #"

    "master's block says what a write gets inside and outside what the session may write, and what a read gets" {
        (Rules.load(filesOnMaster()) as Loaded.Valid).ruleSet.engagement.permissions shouldBe
            mapOf("write_inside" to "allow", "write_outside" to "deny", "read" to "allow", "lifetime" to "once")
    }

    "an unknown key, a missing key or a value the key does not take is reported" {
        problemsAfter("rules/engagement.yaml", block, "  inside_working_directory: allow  #") shouldContainExactly
            listOf("rules/engagement.yaml: permissions: unknown key 'inside_working_directory'", "rules/engagement.yaml: permissions: missing write_inside")
        problemsAfter("rules/engagement.yaml", block, "  write_inside: ask  #") shouldContainExactly
            listOf("rules/engagement.yaml: permissions.write_inside must be allow or deny")
        problemsAfter("rules/engagement.yaml", "  lifetime: once", "  lifetime: session") shouldContainExactly
            listOf("rules/engagement.yaml: permissions.lifetime must be once")
    }
})
