import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun verifyDecisionConstruction(module: String, script: File, directories: List<File>) {
    val command = listOf("python3", script.absolutePath, "--module", module, "--classes") + directories.map { it.absolutePath }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output.trim().ifBlank { "Decision construction fence failed for $module" } }
    if (output.isNotBlank()) print(output)
}

/** A module with main source but no spec yet, and when its first arrives; the stage that writes it removes the line. */
val specsNotYetWritten = mapOf("kp-engine" to "S1.1 or S1.2 writes its first")

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
    // A module whose test task executes no spec fails `check` (retro of P0, candidate 3), and the log says how many
    // each module ran (issue #2). Counted from the test task's own reports, so an up-to-date test task counts too; an
    // emptied test source set leaves none. A module with no main source has nothing to test yet.
    val specsModule = name
    val mainSources = fileTree("src/main") { include("**/*.kt", "**/*.java") }
    val testReports = tasks.named<Test>("test").flatMap { it.reports.junitXml.outputLocation }
    val specsRan = tasks.register("checkSpecsRan") {
        group = "verification"
        description = "Fails when the test task executed no spec; prints how many it executed."
        dependsOn("test")
        doLast {
            val suite = Regex("<testsuite [^>]*\\btests=\"(\\d+)\" skipped=\"(\\d+)\"")
            val executed = testReports.get().asFile.listFiles { file -> file.name.startsWith("TEST-") && file.name.endsWith(".xml") }.orEmpty()
                .sumOf { report -> suite.find(report.readText())?.destructured?.let { (tests, skipped) -> tests.toInt() - skipped.toInt() } ?: 0 }
            when {
                executed > 0 -> println("$specsModule: the test task executed $executed specs")
                mainSources.isEmpty -> println("$specsModule: no main source, no spec expected")
                specsModule in specsNotYetWritten -> println("$specsModule: no spec expected until ${specsNotYetWritten.getValue(specsModule)}")
                else -> throw GradleException("$specsModule: the test task executed no spec")
            }
        }
    }
    tasks.named("check") { dependsOn(specsRan) }
}
tasks.named("build") { dependsOn(subprojects.map { "${it.path}:build" }) }
tasks.register("test") { dependsOn(subprojects.map { "${it.path}:test" }) }
tasks.named("check") { dependsOn(subprojects.map { "${it.path}:check" }) }

val checkVersions = tasks.register<Exec>("checkVersions") {
    commandLine("python3", "build-support/check_versions.py")
}
tasks.named("check") { dependsOn(checkVersions) }
