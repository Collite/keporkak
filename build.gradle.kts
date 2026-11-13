import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
    configurations.configureEach { resolutionStrategy { failOnDynamicVersions(); failOnChangingVersions() } }
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
