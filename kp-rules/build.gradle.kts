dependencies {
    implementation(project(":kp-model"))
    implementation(libs.kaml)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The specs run the Python scripts — the rules' check, the walker, the Python suite — and probe the build on a copy of
// its Gradle files, its version catalog and its build support: a change to any of them runs them again (S2.8 T2). The
// rules, robots, pool and personas are every test task's inputs (the root convention).
tasks.named<Test>("test") {
    for (folder in listOf("scripts", "gradle", "build-support")) {
        inputs.files(rootProject.fileTree(folder)).withPropertyName("checkout_" + folder.replace("-", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    for (file in listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew")) {
        inputs.files(rootProject.file(file)).withPropertyName("build_" + file.replace(".", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
