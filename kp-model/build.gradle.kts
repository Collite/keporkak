plugins { alias(libs.plugins.kotlin.serialization) }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.acp)
    testImplementation(libs.mcp)
    testRuntimeOnly(libs.junit.platform.launcher)
}
// The construction and dependency fences' probes (EngineBoundarySpec) build kp-engine's sources in a fixture: those
// sources, the engine's build file and the construction check are inputs of these tests, so a change to Decision runs
// the probes again without --rerun (issue #14). The fixture is a copy of the build's Gradle files, its version catalog
// and its build support: those are inputs too (S2.8 T2).
tasks.named<Test>("test") {
    inputs.dir(rootProject.file("kp-engine/src/main")).withPropertyName("engineSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("kp-engine/build.gradle.kts")).withPropertyName("engineBuild").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("build-support")).withPropertyName("buildSupport").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("gradle")).withPropertyName("gradleFolder").withPathSensitivity(PathSensitivity.RELATIVE)
    for (file in listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew")) {
        inputs.files(rootProject.file(file)).withPropertyName("build_" + file.replace(".", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
