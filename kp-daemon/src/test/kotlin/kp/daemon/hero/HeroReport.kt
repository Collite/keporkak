package kp.daemon.hero

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import kp.daemon.checkout
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * What `just hero` prints (S1.6 T7): each hero scenario and how many of its steps passed, the sweep's exceptions with
 * their reasons, and last the counts — scenarios, requirements on master, swept, exceptions. Written to
 * `kp-daemon/build/hero/report.txt` once the hero specs have run; registered on the spec that runs them.
 */
object HeroReport : AfterEachListener, AfterSpecListener {
    private val file = checkout.resolve("kp-daemon/build/hero/report.txt")
    /** Each top-level spec of the file, by its sentence: its steps — or itself, when it has none — and whether each passed. */
    private val specs = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>()
    private var sweep: Sweep? = null

    fun sweep(sweep: Sweep) {
        this.sweep = sweep
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        val root = generateSequence(testCase) { it.parent }.last()
        specs.getOrPut(root.name.name) { ArrayList() } += testCase.name.name to result.isSuccess
    }

    override suspend fun afterSpec(spec: Spec) {
        file.parent.createDirectories()
        file.writeText(lines().joinToString("") { "$it\n" })
    }

    private fun lines(): List<String> {
        val scenarios = specs.filterKeys { it.startsWith("H-") }
        val passed = scenarios.count { (_, steps) -> steps.all { it.second } }
        val noRoll = scenarios.values.flatten().firstOrNull { NO_ROLL in it.first }?.second == true
        val told = specs.map { (sentence, steps) ->
            val failed = steps.filterNot { it.second }.map { it.first }
            "$sentence: " + (if (steps.size == 1 && steps.single().first == sentence) (if (failed.isEmpty()) "passed" else "FAILED")
                else "${steps.size - failed.size} of ${steps.size} steps passed") + failed.joinToString("; ", " — failed: ").takeIf { failed.isNotEmpty() }.orEmpty()
        }
        val swept = sweep?.let { it.report() } ?: listOf("the sweep did not finish")
        val counts = "$passed of ${scenarios.size} scenarios passed" + (if (noRoll) " and the no-roll exit" else ", the no-roll exit did not") +
            (sweep?.let { " · requirements on master: ${it.onMaster} · swept: ${it.swept} · exceptions: ${it.exceptions.size} (swept + exceptions = ${it.swept + it.exceptions.size})" } ?: "")
        return told + swept.dropLast(1) + counts
    }

    /** The step of H-3 that is the stage with a live DONE and nothing to roll (D-42). */
    private const val NO_ROLL = "nothing to roll"
}
