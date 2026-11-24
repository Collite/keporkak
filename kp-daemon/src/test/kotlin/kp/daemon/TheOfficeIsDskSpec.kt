package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * KP/P3/S3.12 T1 — the office is dsk: the pool names two offices, and no spec reads where a block of it stands or how many
 * offices it names (Bora reads these). The seams: `scripts/check-rules.py --pool` and the daemon's load of the rules on a
 * copy of the checkout's rules, robots, pool and personas whose `pool/offices.yaml` the spec changes — the line a problem
 * names is read from that copy, as the specs that read the real pool read it ([lineOf]); and `kp status` on an office
 * whose pool is such a copy. The office check and the build's Python are `tests/test_s3_12_the_office_is_dsk.py`'s.
 */
class TheOfficeIsDskSpec : StringSpec({
    "a spec that names a line of the real pool reads that line from the file: with an office added above the block `github:`, one added below it, and a comment line added inside it, each still passes and still fails when the block is broken" {
        val above = rulesWith { withOfficeAdded(it, "mac") }
        val below = rulesWith { withOfficeAdded(withTheGitHubBlockFirst(it), "mac") }
        val inside = rulesWith { it.replace("\ngithub:\n", "\ngithub:\n  # a comment inside the block\n") }

        // Each copy moves the line the real pool gives the coder's seat.
        val real = lineOf(checkout.resolve("pool/offices.yaml"), CODER)
        lineOf(offices(above), CODER) shouldBeGreaterThan real
        lineOf(offices(below), CODER) shouldBeLessThan real
        lineOf(offices(inside), CODER) shouldBe real + 1

        // Each passes: neither check-rules.py nor the daemon's load finds a problem in it.
        bothSay(above) shouldBe emptyList()
        bothSay(below) shouldBe emptyList()
        bothSay(inside) shouldBe emptyList()

        // Each fails with the role misspelled, both naming the line its copy holds the role on.
        bothSay(misspelled(above)) shouldBe twice("pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line ${lineOf(offices(above), CODER)})")
        bothSay(misspelled(below)) shouldBe twice("pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line ${lineOf(offices(below), CODER)})")
        bothSay(misspelled(inside)) shouldBe twice("pool/offices.yaml: github: seats.codr: no persona declares the role 'codr' (line ${lineOf(offices(inside), CODER)})")
    }

    "`kp status`'s budgets line names every office of the pool with its limit: one office, two, three" {
        budgetsLine(listOf("panda"), "{total: 4, per_subscription: {claude: 4}, per_office: {panda: 4}, stages_in_flight_per_effort: 2}") shouldBe
            "budgets: 4 slots · per subscription claude 4 · per office panda 4 · 2 stages in flight per effort"
        budgetsLine(listOf("panda", "dsk"), "{total: 4, per_subscription: {claude: 4}, per_office: {panda: 4, dsk: 4}, stages_in_flight_per_effort: 2}") shouldBe
            "budgets: 4 slots · per subscription claude 4 · per office panda 4, dsk 4 · 2 stages in flight per effort"
        budgetsLine(listOf("panda", "dsk", "mac"), "{total: 6, per_subscription: {claude: 6}, per_office: {panda: 4, dsk: 4, mac: 2}, stages_in_flight_per_effort: 2}") shouldBe
            "budgets: 6 slots · per subscription claude 6 · per office panda 4, dsk 4, mac 2 · 2 stages in flight per effort"
    }
})

/** The coder's seat in the block `github:`, as master's pool writes it. */
private const val CODER = "    coder: {app: kp-coder,"

/** A copy of the checkout's rules, robots, pool and personas whose `pool/offices.yaml` is [change]d. */
private fun rulesWith(change: (String) -> String): Path = TestOffice.copyOfTheRules().also { it.resolve("pool/offices.yaml").rewrite(change) }

private fun offices(rules: Path): String = rules.resolve("pool/offices.yaml").readText()

/** A copy of [rules] whose `github.seats` misspells the role `coder`, on the same line: a role no persona declares. */
private fun misspelled(rules: Path): Path = rulesWith { offices(rules).replace(CODER, "    codr: {app: kp-coder,") }

/** What check-rules.py says of [rules], then what the daemon's load says. */
private fun bothSay(rules: Path): List<String> = checkRules(rules) + daemonLoad(rules)

private fun twice(problem: String) = listOf(problem, problem)

/** `kp status`'s budgets line on an office whose pool names [offices], each with the checkout's panda's values, and [slots]. */
private fun budgetsLine(offices: List<String>, slots: String): String {
    val rules = rulesWith { withOffices(it, offices) }
    rules.resolve("pool/budgets.yaml").rewrite { withSlots(it, slots) }
    checkRules(rules) shouldBe emptyList()
    return TestOffice(root = rules).use { office -> office.kp("status").text.lines().single { it.startsWith("budgets: ") } }
}
