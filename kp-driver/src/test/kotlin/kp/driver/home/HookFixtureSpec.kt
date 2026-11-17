package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * S1.9 T7 — the fixture that runs a hook (`Hooks.run`): a hook may answer and exit before it reads its input, as `deny`
 * does when it cannot read the seat's permissions. Its answer is still what the fixture returns — not a `Broken pipe`
 * from writing to a process that has gone, which went red once in five runs under load.
 */
class HookFixtureSpec : StringSpec({
    "a hook that has answered and gone before its input is written is still read" {
        Sources().use { sources ->
            val coder = sources.home("coder-1", "coder")
            val longer = "true # " + "x".repeat(1 shl 20) // a megabyte: more than a pipe holds, so the write outlives the hook

            Hooks.deny(coder, bash(longer), path = "/nonexistent") shouldBe "deny: jq is missing, so the seat's permissions.json cannot be read"
        }
    }
})
