import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun verifyDecisionConstruction(module: String, script: File, directories: List<File>) {
    val command = listOf("python3", script.absolutePath, "--module", module, "--classes") + directories.map { it.absolutePath }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output.trim().ifBlank { "Decision construction fence failed for $module" } }
    if (output.isNotBlank()) print(output)
}

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
    configurations.configureEach { resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() } }
    if (name != "kp-engine") {
        val module = name
        val script = rootProject.file("build-support/check_decision_construction.py")
        val classesDirectory = layout.buildDirectory.dir("classes")
        // Kotlin internal is JVM-public; inspect bytecode, including method-handle targets.
        tasks.withType<KotlinCompile>().configureEach {
            inputs.file(script)
            doLast { verifyDecisionConstruction(module, script, listOf(destinationDirectory.get().asFile)) }
        }
        tasks.withType<JavaCompile>().configureEach {
            inputs.file(script)
            doLast { verifyDecisionConstruction(module, script, listOf(destinationDirectory.get().asFile)) }
        }
        val constructionFence = tasks.register("checkDecisionConstruction") {
            group = "verification"
            dependsOn("classes", "testClasses")
            doLast { verifyDecisionConstruction(module, script, listOf(classesDirectory.get().asFile)) }
        }
        tasks.named("check") { dependsOn(constructionFence) }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("kp.root", rootDir.absolutePath)
        maxHeapSize = "768m"
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
tasks.named("build") { dependsOn(subprojects.map { "${it.path}:build" }) }
tasks.register("test") { dependsOn(subprojects.map { "${it.path}:test" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

val checkVersions = tasks.register<Exec>("checkVersions") {
    commandLine("python3", "build-support/check_versions.py")
}
tasks.named("check") { dependsOn(checkVersions) }
