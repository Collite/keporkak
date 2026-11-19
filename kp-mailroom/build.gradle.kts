dependencies {
    implementation(project(":kp-model"))
    implementation(project(":kp-rules"))
    implementation(project(":kp-engine"))
    implementation(project(":kp-log"))
    implementation(libs.serialization.json)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The specs run the Python walker, `scripts/check-rules.py --trace`, as the second opinion on a walk: a change to it runs
// them again (S2.8 T2). The rules, robots, pool and personas are every test task's inputs (the root convention).
tasks.named<Test>("test") {
    inputs.files(rootProject.file("scripts/check-rules.py")).withPropertyName("walker").withPathSensitivity(PathSensitivity.RELATIVE)
}
