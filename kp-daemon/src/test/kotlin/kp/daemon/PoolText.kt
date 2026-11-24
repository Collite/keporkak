package kp.daemon

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.12 — `pool/offices.yaml` as text, for the specs that read the real pool: where a line stands is read from the
 * file, never written out, and a block is found by its key, not by where it stands or how many offices come before it.
 */

/** The number, from 1, of the one line of [text] that begins with [start]: the line a problem about it names. */
fun lineOf(text: String, start: String): Int {
    val lines = text.lines().withIndex().filter { it.value.startsWith(start) }
    check(lines.size == 1) { "${lines.size} lines begin with '$start', not one" }
    return lines.single().index + 1
}

/** [lineOf] in the file at [file]. */
fun lineOf(file: Path, start: String): Int = lineOf(file.readText(), start)

/** The top-level block [key] of a YAML text: its key's line and every line under it, up to the next top-level line. */
private fun blockOf(text: String, key: String): IntRange {
    val lines = text.lines()
    val first = lines.indexOf("$key:").also { check(it >= 0) { "no top-level '$key:'" } }
    val end = (first + 1 until lines.size).firstOrNull { lines[it].isNotEmpty() && !lines[it].startsWith(" ") } ?: lines.size
    return first until end
}

/** [offices] without its block `github:`, wherever the block stands. */
fun withoutTheGitHubBlock(offices: String): String {
    val lines = offices.lines()
    val block = blockOf(offices, "github")
    return (lines.take(block.first) + lines.drop(block.last + 1)).joinToString("\n")
}

/** [offices] with its block `github:` moved to stand before its block `offices:`. */
fun withTheGitHubBlockFirst(offices: String): String {
    val lines = withoutTheGitHubBlock(offices).lines()
    val github = offices.lines().slice(blockOf(offices, "github"))
    val at = lines.indexOf("offices:")
    return (lines.take(at) + github + lines.drop(at)).joinToString("\n")
}

/** The entry of the office [name] under `offices:`, as the file has it: its key's line and every line under it. */
private fun entryOf(offices: String, name: String): List<String> {
    val lines = offices.lines()
    val first = lines.indexOf("  $name:").also { check(it >= 0) { "no office '$name'" } }
    val end = (first + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].startsWith("    ") } ?: lines.size
    return lines.subList(first, end)
}

/** [offices] with an office [name] added last under `offices:`, its values the first office's. */
fun withOfficeAdded(offices: String, name: String): String {
    val lines = offices.lines()
    val map = blockOf(offices, "offices")
    val first = lines[map.first + 1].trim().removeSuffix(":")
    val added = listOf("  $name:") + entryOf(offices, first).drop(1)
    return (lines.take(map.last + 1) + added + lines.drop(map.last + 1)).joinToString("\n")
}

/** [offices] whose `offices:` holds exactly [names], each with the values of the office [like] as the file has them. */
fun withOffices(offices: String, names: List<String>, like: String = Rehearsed.OFFICE): String {
    val lines = offices.lines()
    val map = blockOf(offices, "offices")
    val template = entryOf(offices, like).drop(1)
    val entries = names.flatMap { listOf("  $it:") + template }
    return (lines.take(map.first + 1) + entries + lines.drop(map.last + 1)).joinToString("\n")
}

/** [budgets] whose slot limits are [slots], written as the file writes them: `slots: {…}` on one line. */
fun withSlots(budgets: String, slots: String): String {
    val line = Regex("(?m)^slots: \\{.*}$")
    check(line.containsMatchIn(budgets)) { "pool/budgets.yaml has no one-line 'slots: {…}'" }
    return budgets.replace(line, Regex.escapeReplacement("slots: $slots"))
}

/** Rewrites the file at [file] through [change]. */
fun Path.rewrite(change: (String) -> String): Path = also { it.writeText(change(it.readText())) }

/** `check-rules.py --pool` on the pool of [rules] — a copy of the checkout's rules, robots, pool and personas: its `FAIL` lines. */
fun checkRules(rules: Path): List<String> {
    val process = ProcessBuilder(checkout.resolve("build/python/bin/python").toString(), "scripts/check-rules.py", "--pool", rules.resolve("pool").toString())
        .directory(checkout.toFile()).redirectErrorStream(true).start()
    val lines = process.inputStream.bufferedReader().readLines()
    process.waitFor()
    return lines.filter { it.startsWith("FAIL ") }.map { it.removePrefix("FAIL ") }
}

/** The daemon's load of [rules]: the problems that keep its rules out of force. */
fun daemonLoad(rules: Path): List<String> = InForceRules.of(RuleFiles(rules).read()).second.problems.map { it.toString() }
