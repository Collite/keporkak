dependencies {
    implementation(project(":kp-model"))
    implementation(libs.kaml)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The specs run the Python scripts — the rules' check, the walker, the Python suite — and probe the build on a copy of
// its Gradle files, its version catalog and its build support: a change to any of them runs them again (S2.8 T2). The
// rules, robots, pool and personas are every test task's inputs (the root convention). Python's own caches are none:
// the Python suite writes scripts/__pycache__ when it imports a script.
tasks.named<Test>("test") {
    for (folder in listOf("scripts", "gradle", "build-support")) {
        inputs.files(rootProject.fileTree(folder) { exclude("**/__pycache__/**") }).withPropertyName("checkout_" + folder.replace("-", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    for (file in listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew")) {
        inputs.files(rootProject.file(file)).withPropertyName("build_" + file.replace(".", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // The zero-spec probe (RulesLoadSupport.gradleCheckWithoutSpecs) builds a copy of every module but its build output:
    // what it copies is an input too, so its result is no stale one (S2.9 T7; issue #43).
    rootDir.listFiles { file -> file.isDirectory && file.name.startsWith("kp-") }!!.sortedBy { it.name }.forEach { module ->
        inputs.files(rootProject.fileTree(module) { exclude("build/**") }).withPropertyName("module_" + module.name.replace("-", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
