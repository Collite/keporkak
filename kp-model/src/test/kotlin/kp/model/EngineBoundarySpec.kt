package kp.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

private fun constructorProbe(module: String, language: String, source: String): CommandResult {
    val fixture = engineFixture()
    val folder = File(fixture, "build/probes/$module-$language").apply { mkdirs() }
    File(folder, if (language == "java") "Counterfeit.java" else "Counterfeit.kt").writeText(source)
    val init = File(folder, "probe.init.gradle")
    val addDependency = if (module == "kp-engine") "" else "p.dependencies.add('implementation', rootProject.project(':kp-engine'))"
    val sourceSet = if (language == "java") "p.sourceSets.main.java" else "p.kotlin.sourceSets.main.kotlin"
    init.writeText("gradle.projectsEvaluated { def p = rootProject.project(':$module'); $addDependency; $sourceSet.srcDir('${folder.path}') }")
    return runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-I", init.path, ":$module:compile${if (language == "java") "Java" else "Kotlin"}"), fixture)
}
/** Adds [notation] to the engine's [configuration] in the fixture build and runs the engine's dependency fence. */
private fun engineFence(configuration: String, notation: String): CommandResult {
    val fixture = engineFixture()
    val init = File(fixture, "build/probes/fence-$configuration.init.gradle").apply { parentFile.mkdirs() }
    init.writeText("gradle.projectsEvaluated { rootProject.project(':kp-engine').dependencies.add('$configuration', '$notation') }")
    return runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-I", init.path, ":kp-engine:checkEngineDependencies"), fixture)
}
class EngineBoundarySpec : StringSpec({
    "the engine's tests may carry the test framework" {
        val result = engineFence("testImplementation", "io.kotest:kotest-assertions-core:6.2.6")
        result.exitCode shouldBe 0
    }
    "a test dependency outside the test framework fails the engine fence" {
        val result = engineFence("testImplementation", "org.example:forbidden:1.0")
        result.exitCode shouldBe 1
        result.output.contains("kp-engine dependency is forbidden: org.example:forbidden") shouldBe true
    }
    "the test framework on the engine's production classpath fails the engine fence" {
        val result = engineFence("implementation", "io.kotest:kotest-runner-junit5:6.2.6")
        result.exitCode shouldBe 1
        result.output.contains("kp-engine dependency is forbidden: io.kotest:kotest-runner-junit5") shouldBe true
    }
    "a Kotlin module outside the engine cannot construct a decision, though it can read one" {
        val reads = constructorProbe("kp-log", "kotlin", "package probe; fun reads(decision: kp.engine.Decision) = decision.outcome")
        val result = constructorProbe("kp-log", "kotlin", "package probe; fun counterfeit() = kp.engine.Decision()")
        reads.exitCode shouldBe 0
        result.exitCode shouldBe 1
        result.output.contains(Regex("""e: \S*Counterfeit\.kt:1:""")) shouldBe true
    }
    "a Java module outside the engine cannot construct a decision, though it can read one" {
        val reads = constructorProbe("kp-log", "java", "package probe; public class Counterfeit { public Object reads(kp.engine.Decision decision) { return decision.getOutcome(); } }")
        val result = constructorProbe("kp-log", "java", "package probe; public class Counterfeit { public Object make() { return new kp.engine.Decision(); } }")
        reads.exitCode shouldBe 0
        result.exitCode shouldBe 1
        result.output.contains(Regex("""Counterfeit\.java:1: error:""")) shouldBe true
    }
    "the probes' inputs: a change to the engine's sources, its build file or the construction check runs kp-model's tests again" {
        val fixture = engineFixture()
        val init = File(fixture, "build/probes/inputs.init.gradle").apply { parentFile.mkdirs() }
        init.writeText("gradle.projectsEvaluated { def p = rootProject.project(':kp-model'); p.tasks.register('printTestInputs') { " +
            "doLast { p.tasks.named('test').get().inputs.files.files.each { println('INPUT ' + it.path) } } } }")
        val result = runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-q", "-I", init.path, ":kp-model:printTestInputs"), fixture)
        val inputs = result.output.lines().filter { it.startsWith("INPUT ") }.map { it.removePrefix("INPUT ") }
        result.exitCode shouldBe 0
        inputs.contains(File(fixture, "kp-engine/src/main/kotlin/kp/engine/Decision.kt").path) shouldBe true
        inputs.contains(File(fixture, "kp-engine/build.gradle.kts").path) shouldBe true
        inputs.contains(File(fixture, "build-support/check_decision_construction.py").path) shouldBe true
    }
    "a Java module outside the engine cannot call the decision factory" {
        val result = constructorProbe("kp-log", "java", "package probe; public class Counterfeit { public Object make() { return kp.engine.Decision.Companion.create(); } }")
        result.exitCode shouldBe 1
        result.output.contains("Decision construction outside kp-engine is forbidden") shouldBe true
    }
    "suppressing Kotlin visibility cannot bypass the decision construction fence" {
        val source = "@file:Suppress(\"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\")\npackage probe; fun counterfeit() = kp.engine.Decision.create()"
        val result = constructorProbe("kp-log", "kotlin", source)
        result.exitCode shouldBe 1
        result.output.contains("Decision construction outside kp-engine is forbidden") shouldBe true
    }
    "the engine itself can construct a decision" {
        val result = constructorProbe("kp-engine", "kotlin", "package kp.engine; fun decisionInside() = Decision.create()")
        result.exitCode shouldBe 0
    }
    "an unapproved project dependency also fails the engine fence" {
        val fixture = engineFixture()
        val init = File(fixture, "build/probes/project.init.gradle").apply { parentFile.mkdirs() }
        init.writeText("gradle.projectsEvaluated { rootProject.project(':kp-engine').dependencies.add('implementation', rootProject.project(':kp-log')) }")
        val result = runCommand(listOf("./gradlew", "--console=plain", "--max-workers=1", "-I", init.path, ":kp-engine:checkEngineDependencies"), fixture)
        result.exitCode shouldBe 1
        result.output.contains("kp-engine dependency is forbidden: :kp-log") shouldBe true
    }
})
