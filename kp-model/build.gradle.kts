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
// the probes again without --rerun (issue #14).
tasks.named<Test>("test") {
    inputs.dir(rootProject.file("kp-engine/src/main")).withPropertyName("engineSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("kp-engine/build.gradle.kts")).withPropertyName("engineBuild").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("build-support/check_decision_construction.py")).withPropertyName("constructionCheck").withPathSensitivity(PathSensitivity.RELATIVE)
}
