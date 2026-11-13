package kp.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

val root = File(System.getProperty("kp.root"))
fun validMessage(): JsonObject = Json.parseToJsonElement("""{"v":1,"seq":1,"at":"2026-11-13T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"started","subject":"Skeleton","created":"2026-11-13T12:00:00Z","by":"hand:coder"}""").jsonObject
fun assertRecordRoundTrips(examples: List<String>) {
    examples.forEach {
        val source = JsonObject(Json.parseToJsonElement(it).jsonObject + mapOf("v" to JsonPrimitive(1), "seq" to JsonPrimitive(2), "at" to JsonPrimitive("2026-11-13T12:00:01Z")))
        Json.encodeToJsonElement(Record.serializer(), Json.decodeFromJsonElement(Record.serializer(), source)) shouldBe source
    }
}
data class Example(val name: String, val schema: String, val accepted: Boolean, val value: JsonElement)
fun sharedExamples(): List<Example> = Json.parseToJsonElement(File(root, "kp-model/src/test/resources/conformance.json").readText()).jsonArray.map {
    val o = it.jsonObject
    Example(o.getValue("name").jsonPrimitive.content, o.getValue("schema").jsonPrimitive.content, o.getValue("accepted").jsonPrimitive.boolean, o.getValue("value"))
}
private val oracle: JsonObject by lazy {
    val result = runCommand(listOf(File(root, "build/python/bin/python").path, "tests/schema_oracle.py"))
    check(result.exitCode == 0) { result.output }
    Json.parseToJsonElement(result.output).jsonObject
}
fun schemaAccepts(example: Example) = oracle.getValue(example.name).jsonPrimitive.boolean
fun modelAccepts(example: Example) = runCatching {
    when (example.schema) { "message" -> Message.read(example.value.jsonObject); else -> Record.read(example.value.jsonObject) }
}.isSuccess
data class CommandResult(val exitCode: Int, val output: String)
fun runCommand(command: List<String>, directory: File = root): CommandResult {
    val process = ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return CommandResult(process.waitFor(), output)
}
fun engineDependencyProbe(notation: String): CommandResult {
    val fixture = engineFixture()
    val init = File(fixture, "build/probes/dependency.init.gradle").apply { parentFile.mkdirs() }
    init.writeText("gradle.projectsEvaluated { rootProject.project(':kp-engine').dependencies.add('implementation', '$notation') }")
    return runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-I", init.path, ":kp-engine:build"), fixture)
}

private val fixtureDirectory: File by lazy {
    val directory = File(root, "build/engine-fixture").apply { mkdirs() }
    listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat").forEach {
        File(root, it).copyTo(File(directory, it), overwrite = true)
    }
    File(root, "gradle").copyRecursively(File(directory, "gradle"), overwrite = true)
    File(directory, "gradlew").setExecutable(true)
    File(root, "build-support").copyRecursively(File(directory, "build-support"), overwrite = true)
    root.listFiles()!!.filter { it.isDirectory && it.name.startsWith("kp-") }.forEach { module ->
        val target = File(directory, module.name).apply { mkdirs() }
        File(module, "build.gradle.kts").copyTo(File(target, "build.gradle.kts"), overwrite = true)
        val source = File(module, "src/main")
        if (source.exists()) source.copyRecursively(File(target, "src/main"), overwrite = true)
    }
    directory
}
fun engineFixture(): File = fixtureDirectory

fun assertSharedExamplesConform(examples: List<Example>) {
    require(examples.isNotEmpty()) { "shared examples must not be empty" }
    examples.forEach { example ->
        io.kotest.assertions.withClue(example.name) {
            schemaAccepts(example) shouldBe example.accepted
            modelAccepts(example) shouldBe example.accepted
        }
    }
}
