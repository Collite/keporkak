package kp.log

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories

val root = File(System.getProperty("kp.root"))

/** An empty project checkout under the build directory; Gradle's clean removes it. */
fun throwawayProject(): Path = Files.createTempDirectory(root.resolve("build").toPath().createDirectories(), "project-")

/** A copy of the recorded-stage fixture, so a spec can never change the fixture itself. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
fun recordedStage(): Path = throwawayProject().also {
    root.resolve("kp-log/src/test/resources/fixtures/recorded-stage").toPath().copyToRecursively(it, followLinks = false, overwrite = false)
}

data class CommandResult(val exitCode: Int, val output: String)

fun run(vararg command: String): CommandResult {
    val process = ProcessBuilder(*command).directory(root).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    return CommandResult(process.waitFor(), output)
}
