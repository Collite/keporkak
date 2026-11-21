import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * A system property a test reads that names paths of this worktree (S2.9 T7): the paths are no input of the test, so
 * its build-cache key is no worktree's — what they hold is declared beside, by path relative to the checkout.
 */
class WorktreePaths(@get:Input val name: String, @get:Internal val paths: () -> String) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-D$name=${paths()}")
}

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
        // Kotlin internal is JVM-public; inspect bytecode, including method-handle targets. The script is an input by its
        // path in the checkout, so a compile's cached result serves every worktree of the machine (S2.8 T2).
        tasks.withType<KotlinCompile>().configureEach {
            inputs.file(script).withPropertyName("constructionCheck").withPathSensitivity(PathSensitivity.RELATIVE)
            doLast { verifyDecisionConstruction(module, script, listOf(destinationDirectory.get().asFile)) }
        }
        tasks.withType<JavaCompile>().configureEach {
            inputs.file(script).withPropertyName("constructionCheck").withPathSensitivity(PathSensitivity.RELATIVE)
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
        jvmArgumentProviders.add(WorktreePaths("kp.root") { rootDir.absolutePath })
        maxHeapSize = "768m"
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        // Every test task reads the checkout's rules, robots, pool and personas (retro of P1, candidate 5): a change to a
        // file there runs it again. A module declares beside these what else its specs read (S2.8 T2).
        for (folder in listOf("rules", "robots", "pool", "agents")) {
            inputs.files(rootProject.fileTree(folder) { exclude("**/__pycache__/**") }).withPropertyName("checkout_$folder").withPathSensitivity(PathSensitivity.RELATIVE)
        }
        // A run its filter leaves with no test fails — Gradle's `--tests` and Kotest's own filter alike. Kotest reads its
        // filter from the environment, which is no input of a task: it is made one, so a new filter runs the task again.
        val kotestFilters = listOf("kotest_filter_specs", "kotest_filter_tests")
        kotestFilters.forEach { name -> inputs.property(name, providers.environmentVariable(name).orElse("")) }
        // A spec that never ends fails by the default timeout (S3.0 T4): every module's ProjectConfig reads it from here —
        // 5m, or what KP_SPEC_TIMEOUT says; as a system property it is an input of the task, like the filter. The default
        // is written here alone (S3.8 T6): no ProjectConfig holds one of its own.
        systemProperty("kp.spec.timeout", providers.environmentVariable("KP_SPEC_TIMEOUT").orElse("5m").get())
        // The specs' temp folders (S3.0 T4): each run of a test task makes them in a folder of its own under the system's
        // temp — its JVM's `java.io.tmpdir`, and TMPDIR for the processes it starts — removed when the run ends, failed or
        // not. A path of the run is in no key of the task: it is set when the run begins.
        val specsTemp = java.util.concurrent.atomic.AtomicReference<File?>()
        doFirst {
            val folder = java.nio.file.Files.createTempDirectory("kp-specs-").toFile()
            specsTemp.set(folder)
            systemProperty("java.io.tmpdir", folder.absolutePath)
            environment("TMPDIR", folder.absolutePath)
        }
        val executed = java.util.concurrent.atomic.AtomicLong()
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) {
                    executed.set(result.testCount - result.skippedTestCount)
                    specsTemp.getAndSet(null)?.deleteRecursively()
                }
            }
            override fun beforeTest(test: TestDescriptor) {}
            override fun afterTest(test: TestDescriptor, result: TestResult) {}
        })
        doLast {
            specsTemp.getAndSet(null)?.deleteRecursively()
            if (executed.get() == 0L) {
                val filters = kotestFilters.mapNotNull { name -> System.getenv(name)?.let { "$name=$it" } }
                throw GradleException("$path executed no test" + filters.joinToString(", ", " (", ")").takeIf { filters.isNotEmpty() }.orEmpty())
            }
        }
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
