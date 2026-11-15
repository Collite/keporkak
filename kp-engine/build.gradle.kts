import org.gradle.api.artifacts.ProjectDependency

dependencies {
    implementation(project(":kp-model"))
    implementation(project(":kp-rules"))
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
// architecture.md §2 (D-57): model, rules and the Kotlin standard library on the production classpath; the test
// classpath may add the test framework and nothing else.
val testFramework = setOf("io.kotest:kotest-runner-junit5", "io.kotest:kotest-assertions-core", "org.junit.platform:junit-platform-launcher")
val checkEngineDependencies = tasks.register("checkEngineDependencies") {
    group = "verification"
    description = "Reject engine dependencies outside model, rules and the Kotlin standard library (tests: and the test framework)."
    doLast {
        val production = listOf("compileClasspath", "runtimeClasspath")
        val test = listOf("testCompileClasspath", "testRuntimeClasspath")
        (production.map { it to false } + test.map { it to true }).flatMap { (path, isTest) ->
            configurations.getByName(path).allDependencies.map { it to isTest }
        }.distinct().forEach { (dependency, isTest) ->
            val label = if (dependency is ProjectDependency) dependency.path else "${dependency.group}:${dependency.name}"
            val allowed = when (dependency) {
                is ProjectDependency -> dependency.path in setOf(":kp-model", ":kp-rules")
                else -> (dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib") || (isTest && label in testFramework)
            }
            check(allowed) { "kp-engine dependency is forbidden: $label" }
        }
    }
}
tasks.named("check") { dependsOn(checkEngineDependencies) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(checkEngineDependencies) }
tasks.withType<JavaCompile>().configureEach { dependsOn(checkEngineDependencies) }
