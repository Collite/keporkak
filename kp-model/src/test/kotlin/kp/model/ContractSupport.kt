package kp.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

val root = File(System.getProperty("kp.root"))
fun validMessage(): JsonObject = Json.parseToJsonElement("""{"v":1,"seq":1,"at":"2026-11-13T12:00:00Z","id":"m-panda-000001","thread":"m-panda-000001","from":"coder-1","to":"teamlead","kind":"started","subject":"Skeleton","created":"2026-11-13T12:00:00Z","by":"hand:coder"}""").jsonObject
fun assertRecordRoundTrips(examples: List<String>) {
    examples.forEach {
        val source = JsonObject(Json.parseToJsonElement(it).jsonObject + mapOf("v" to JsonPrimitive(1), "seq" to JsonPrimitive(2), "at" to JsonPrimitive("2026-11-13T12:00:01Z")))
        Record.read(source).json shouldBe source
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
fun runCommand(command: List<String>): CommandResult {
    val process = ProcessBuilder(command).directory(root).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return CommandResult(process.waitFor(), output)
}
fun engineDependencyProbe(notation: String): CommandResult {
    val init = File(root, "build/probes/dependency.init.gradle").apply { parentFile.mkdirs() }
    init.writeText("gradle.projectsEvaluated { rootProject.project(':kp-engine').dependencies.add('implementation', '$notation') }")
    return runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-I", init.path, ":kp-engine:checkEngineDependencies"))
}
