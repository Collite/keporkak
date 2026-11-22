package kp.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.model.SeatId
import kotlin.io.path.writeText

/**
 * S3.7 T5 — a secret handed to a seat while its session runs — a GitHub token (contracts.md §4.4, Amendment 35) — is
 * hidden in its transcript from then on. The seam: the driver and S2.1's fake agent, whose shell prints a file the spec
 * writes the secret into; the transcript the driver writes.
 */
class HiddenWhenHandedSpec : StringSpec({
    "a secret the driver is told while a seat's session runs is in no line of its transcript after: at the start, across the cut, after it" {
        Office().use { office ->
            val secret = "ghs_" + "Q7".repeat(18)
            val file = office.root.resolve("handed.txt").also { it.writeText("$secret ${" ".repeat(1955)}$secret $secret\n") }
            val agent = office.agent("""{"turns": [[{"do": "shell", "id": "s1", "command": "cat '$file'"}, {"do": "end"}]]}""")
            val driver = office.driver()
            driver.start(office.session("coder-1", agent)).ok()

            driver.hide(SeatId("coder-1"), secret)
            driver.prompt(SeatId("coder-1"), "print it").ok()

            office.transcript("coder-1").single { " tool s1 completed" in it } shouldContain "→ ‹hidden› "
            office.transcript("coder-1").filter { line -> secret.windowed(12).any { it in line } } shouldBe emptyList()
        }
    }

    "a secret is hidden only in its own seat's transcript" {
        Office().use { office ->
            val secret = "ghs_" + "Z9".repeat(18)
            val file = office.root.resolve("handed.txt").also { it.writeText("$secret\n") }
            val script = """{"turns": [[{"do": "shell", "id": "s1", "command": "cat '$file'"}, {"do": "end"}]]}"""
            val driver = office.driver()
            driver.start(office.session("coder-1", office.agent(script))).ok()
            driver.start(office.session("coder-2", office.agent(script))).ok()

            driver.hide(SeatId("coder-1"), secret)
            driver.prompt(SeatId("coder-1"), "print it").ok()
            driver.prompt(SeatId("coder-2"), "print it").ok()

            office.transcript("coder-1").single { " tool s1 completed" in it } shouldContain "→ ‹hidden›"
            office.transcript("coder-2").single { " tool s1 completed" in it } shouldContain "→ $secret"
        }
    }
})
