package kp.rules

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import java.io.File

val root = File(System.getProperty("kp.root"))

/** The texts the daemon hands to [Rules.load]: the four rules files, the robots, the pool files and the personas' modes. */
fun filesOnMaster(): Map<String, String> {
    val rules = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary").map { File(root, "rules/$it.yaml") }
    val robots = File(root, "robots").listFiles { file -> file.name.endsWith(".yaml") }!!.sorted()
    val pool = listOf("roster", "offices", "budgets").map { File(root, "pool/$it.yaml") }
    val personas = File(root, "agents").listFiles()!!.map { File(it, "AGENT.md") }.filter { it.isFile }.sorted()
    return (rules + robots + pool + personas).associate { it.relativeTo(root).path to it.readText() }
}

/** The problems [Rules.load] reports once the first [from] in [file] is replaced by [to]. */
fun problemsAfter(file: String, from: String, to: String): List<String> {
    val files = filesOnMaster()
    val text = files.getValue(file)
    check(from in text) { "'$from' is not in $file" }
    return Rules.load(files + (file to text.replaceFirst(from, to))).problems.map { it.toString() }
}

/** The number, from 1, of the one line of [text] that begins with [start]: the line a problem about it names (S3.12). */
fun lineOf(text: String, start: String): Int {
    val lines = text.lines().withIndex().filter { it.value.startsWith(start) }
    check(lines.size == 1) { "${lines.size} lines begin with '$start', not one" }
    return lines.single().index + 1
}

/** [offices] without its block `github:`, wherever the block stands: its key's line and every line under it (S3.12). */
fun withoutTheGitHubBlock(offices: String): String {
    val lines = offices.lines()
    val first = lines.indexOf("github:").also { check(it >= 0) { "no top-level 'github:'" } }
    val end = (first + 1 until lines.size).firstOrNull { lines[it].isNotEmpty() && !lines[it].startsWith(" ") } ?: lines.size
    return (lines.take(first) + lines.drop(end)).joinToString("\n")
}

data class Scenario(val name: String, val fixture: File, val index: Int, val given: YamlMap)

fun sharedFixtureScenarios(): List<Scenario> =
    File(root, "rules/fixtures").walk().filter { it.isFile && it.name.endsWith(".yaml") }.sorted().flatMap { fixture ->
        val document = Yaml.default.parseToYamlNode(fixture.readText()) as YamlMap
        val title = (document.get<YamlScalar>("name"))!!.content
        document.get<YamlList>("scenarios")!!.items.mapIndexed { index, node ->
            val scenario = node as YamlMap
            Scenario("$title / ${scenario.get<YamlScalar>("name")!!.content}", fixture, index, scenario.get<YamlMap>("given")!!)
        }
    }.toList()

/** The Python validator's failures on one scenario, as it prints them; a walk's outcome is the walker's, not the loader's. */
fun pythonFailures(scenario: Scenario): List<String> {
    val result = runCommand(listOf(File(root, "build/python/bin/python").path, "scripts/check-rules.py",
        "--fixture", scenario.fixture.path, "--scenario", scenario.index.toString()))
    return result.output.lines().filter { it.startsWith("FAIL ") }.map { it.removePrefix("FAIL ") }
        .filterNot { it.startsWith("walk ") }.sorted()
}

/** The loader's problems on the same scenario, in the validator's words: `<id>: <what>` — a robot's own problem is named by its file, as the validator names it. */
fun loaderFailures(scenario: Scenario): List<String> =
    Rules.load(fixtureFiles(scenario.given)).problems.map { listOfNotNull(it.id ?: it.file.takeIf { file -> file.startsWith("robots/") }?.substringAfterLast('/'), it.message).joinToString(": ") }.sorted()

/** A scenario's rule documents, completed the way `check-rules.py --fixture` completes them; pool files and personas are master's. */
fun fixtureFiles(given: YamlMap): Map<String, String> {
    val defaults = mapOf(
        "states" to """{"planned": {}, "exited": {"terminal": true}}""",
        "counters" to "{}",
        "kinds" to """{"started": {"from": "coder", "to": "teamlead", "guard": "-", "args": {}, "class": {"default": "A"},
            "window": "stage", "max_fires": null, "deadline": null, "expiry": [], "require": []}}""",
        "caps" to "{}",
        "vocabulary" to """{"facts": {}, "operations": {}}""",
    )
    fun part(key: String): String = given.get<YamlNode>(key)?.let(::jsonOf) ?: defaults.getValue(key)
    val robots = given.get<YamlList>("robots")?.items.orEmpty()
    val secondKey = given.get<YamlNode>("second_key")?.let { """, "second_key": ${jsonOf(it)}""" }.orEmpty()
    val stopGate = given.get<YamlNode>("stop_gate")?.let { """, "stop_gate": ${jsonOf(it)}""" }.orEmpty()
    val profile = given.get<YamlNode>("profile")?.let(::jsonOf) ?: "\"supervised\""
    val profiles = given.get<YamlNode>("autonomy_profiles")?.let(::jsonOf) ?: """{"supervised": {}}"""
    val declared = given.get<YamlNode>("mailroom_declared_effects")?.let(::jsonOf) ?: "[]"
    val vocabulary = part("vocabulary").let { it.substring(0, it.lastIndexOf('}')) + """, "version": 1}""" }
    return filesOnMaster().filterKeys { it.startsWith("pool/") || it.startsWith("agents/") } + mapOf(
        "rules/stage-lifecycle.yaml" to """{"version": 1, "states": ${part("states")}, "active_states": ["planned"],
            "counters": ${part("counters")}, "transitions": ${jsonOf(given.get<YamlList>("transitions")!!)}}""",
        "rules/message-kinds.yaml" to """{"version": 1, "kinds": ${part("kinds")}}""",
        // The roles master's pool files name besides — the roster reserves a model for the principal — so that the pool,
        // which no fixture is about, raises nothing.
        "rules/engagement.yaml" to """{"version": 1, "roles": {"coder": {}, "teamlead": {}, "principal": {}}, "graduation": {"never": []},
            "mailroom_declared_effects": $declared, "caps": ${part("caps")}, "profile": $profile, "autonomy_profiles": $profiles$secondKey$stopGate}""",
        "rules/vocabulary.yaml" to vocabulary,
    ) + robots.mapIndexed { index, robot -> "robots/fixture-$index.yaml" to jsonOf(robot) }
}

/** JSON is YAML: a fixture's sub-document handed on as text. Scalars keep their content. */
fun jsonOf(node: YamlNode): String = when (node) {
    is YamlMap -> node.entries.entries.joinToString(", ", "{", "}") { (key, value) -> quote(key.content) + ": " + jsonOf(value) }
    is YamlList -> node.items.joinToString(", ", "[", "]") { jsonOf(it) }
    is YamlScalar -> if (node.content.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?|true|false"))) node.content else quote(node.content)
    is YamlNull -> "null"
    is YamlTaggedNode -> jsonOf(node.innerNode)
}

private fun quote(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

data class CommandResult(val exitCode: Int, val output: String)

fun runCommand(command: List<String>, directory: File = root): CommandResult {
    val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return CommandResult(process.waitFor(), output)
}

/** `check` of one module on a copy of the build whose module has an empty test source set. */
fun gradleCheckWithoutSpecs(module: String): CommandResult {
    val copy = File(root, "build/probes/zero-specs").apply { deleteRecursively(); mkdirs() }
    listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew").forEach { File(root, it).copyTo(File(copy, it)) }
    File(copy, "gradlew").setExecutable(true)
    listOf("gradle", "build-support").forEach { File(root, it).copyRecursively(File(copy, it)) }
    root.listFiles()!!.filter { it.isDirectory && it.name.startsWith("kp-") }.forEach { source ->
        source.walk().onEnter { it.name != "build" }.filter { it.isFile }.forEach { file ->
            file.copyTo(File(copy, file.relativeTo(root).path))
        }
    }
    File(copy, "$module/src/test").deleteRecursively()
    return runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", ":$module:check"), copy)
}

fun emptyDirectory(): File = File(root, "build/probes/empty-tests").apply { deleteRecursively(); mkdirs() }

/** The Python suite as `just test` runs it, pointed at [directory]. */
fun pythonSuiteIn(directory: File): CommandResult =
    runCommand(listOf(File(root, "build/python/bin/python").path, "scripts/run-python-tests.py", directory.path))
