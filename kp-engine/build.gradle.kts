import org.gradle.api.artifacts.ProjectDependency

dependencies {
    implementation(project(":kp-model"))
    implementation(project(":kp-rules"))
}
val checkEngineDependencies = tasks.register("checkEngineDependencies") {
    group = "verification"
    description = "Reject engine dependencies outside model, rules and the Kotlin standard library."
    doLast {
        val paths = listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        paths.flatMap { configurations.getByName(it).allDependencies }.distinct().forEach { dependency ->
            val allowed = when (dependency) {
                is ProjectDependency -> dependency.path in setOf(":kp-model", ":kp-rules")
                else -> dependency.group == "org.jetbrains.kotlin" && dependency.name == "kotlin-stdlib"
            }
            val label = if (dependency is ProjectDependency) dependency.path else "${dependency.group}:${dependency.name}"
            check(allowed) { "kp-engine dependency is forbidden: $label" }
        }
    }
}
tasks.named("check") { dependsOn(checkEngineDependencies) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(checkEngineDependencies) }
tasks.withType<JavaCompile>().configureEach { dependsOn(checkEngineDependencies) }
