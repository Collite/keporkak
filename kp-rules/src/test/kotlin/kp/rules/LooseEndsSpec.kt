package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** check-rules.py's failures on a copy of the repository's rules in which the first [from] of [file] is [to]. */
private fun pythonFailuresAfter(file: String, from: String, to: String): List<String> {
    val copy = File(root, "build/probes/python-copy").apply { deleteRecursively(); mkdirs() }
    listOf("scripts", "rules", "robots", "pool", "agents").forEach { File(root, it).copyRecursively(File(copy, it)) }
    File(root, "gradle/libs.versions.toml").copyTo(File(copy, "gradle/libs.versions.toml"))
    val edited = File(copy, file)
    check(from in edited.readText()) { "'$from' is not in $file" }
    edited.writeText(edited.readText().replaceFirst(from, to))
    val result = runCommand(listOf(File(root, "build/python/bin/python").path, File(copy, "scripts/check-rules.py").path), copy)
    return result.output.lines().filter { it.startsWith("FAIL ") }.map { it.removePrefix("FAIL ") }
}

private const val NOTE = "    note: D-27 a, n; master protected by GitHub branch protection\n"

/** S1.2 T8 — what S1.0 left: its Found lines at exit, review-006 and issue #12. The shared fixtures are in rules/fixtures/loose-ends.yaml. */
class LooseEndsSpec : StringSpec({
    "a block that names itself is refused when the rules load" {
        problemsAfter("rules/engagement.yaml", "  otherwise:\n    class: B", "  - holds: second_key\n  otherwise:\n    class: B") shouldContain
            "rules/engagement.yaml: second_key: names the block 'second_key' — a block never names a block"
    }
    "a quoted 'null' or '~' is reported by the loader and by check-rules.py, in the same words" {
        val line = File(root, "rules/message-kinds.yaml").readText().let { it.substring(0, it.indexOf(NOTE)).count { char -> char == '\n' } + 1 }
        val nullWords = "rules/message-kinds.yaml: the quoted scalar 'null' (line $line) would read as a number, a boolean or null — the loader resolves a scalar by its text, not its quotes"

        problemsAfter("rules/message-kinds.yaml", NOTE, "    note: 'null'\n") shouldContain nullWords
        pythonFailuresAfter("rules/message-kinds.yaml", NOTE, "    note: 'null'\n") shouldBe listOf(nullWords)
        problemsAfter("rules/message-kinds.yaml", NOTE, "    note: '~'\n") shouldContain nullWords.replace("'null'", "'~'")
        pythonFailuresAfter("rules/message-kinds.yaml", NOTE, "    note: '~'\n") shouldBe listOf(nullWords.replace("'null'", "'~'"))
    }
    "a missing roster is one problem in the loader, not a cascade" {
        Rules.load(filesOnMaster() - "pool/roster.yaml").problems.map { it.toString() } shouldBe listOf("pool/roster.yaml: missing")
    }
    "a problem of a robot as a whole names its file once" {
        problemsAfter("robots/deputy.yaml", "- merge\n- release", "- merg\n- release") shouldContain "robots/deputy.yaml: unknown intercepted kind 'merg'"
    }
    "a module whose test task executes no spec fails the build — kp-engine is exempt no longer" {
        val gradle = gradleCheckWithoutSpecs("kp-engine")

        gradle.exitCode shouldBe 1
        gradle.output shouldContain "kp-engine: the test task executed no spec"
    }
})
