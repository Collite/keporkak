import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

dependencies {
    implementation(project(":kp-model"))
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/** A system property a test reads, whose value is an input of the test task. */
class SystemProperty(@get:Input val name: String, @get:Input val value: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-D$name=${value.get()}")
}

// architecture.md §2, contracts.md §4.4 (Amendment 32): `kp-facts` depends on `kp-model` and the Kotlin standard library.
// Every component its compile classpath resolves to — `module :<path>` or `library <group>:<name>` — is handed to
// KpFactsDependenciesSpec, which says so: a module added above turns it red (S3.1 T2).
fun named(id: ComponentIdentifier): String = when (id) {
    is ProjectComponentIdentifier -> "module ${id.projectPath}"
    is ModuleComponentIdentifier -> "library ${id.group}:${id.module}"
    else -> "other ${id.displayName}"
}
val compileComponents: Provider<String> = configurations.named("compileClasspath").flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
    val seen = sortedSetOf<String>()
    fun walk(component: ResolvedComponentResult) {
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { if (seen.add(named(it.selected.id))) walk(it.selected) }
    }
    walk(root)
    seen.joinToString(",")
}
tasks.named<Test>("test") {
    jvmArgumentProviders.add(SystemProperty("kp.facts.compile-classpath", compileComponents))
}
