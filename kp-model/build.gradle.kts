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
// the probes again without --rerun (issue #14). The fixture is a copy of the build's Gradle files, its version catalog,
// its build support and every module's main sources and build file: those are inputs too (S2.8 T2; S2.9 T7).
tasks.named<Test>("test") {
    rootDir.listFiles { file -> file.isDirectory && file.name.startsWith("kp-") }!!.sortedBy { it.name }.forEach { module ->
        inputs.files(rootProject.fileTree(module) { include("build.gradle.kts", "src/main/**") }).withPropertyName("module_" + module.name.replace("-", "_"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.files(rootProject.fileTree("build-support") { exclude("**/__pycache__/**") }).withPropertyName("buildSupport").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("gradle") { exclude("**/__pycache__/**") }).withPropertyName("gradleFolder").withPathSensitivity(PathSensitivity.RELATIVE)
    for (file in listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew")) {
        inputs.files(rootProject.file(file)).withPropertyName("build_" + file.replace(".", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
