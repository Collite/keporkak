dependencies {
    implementation(project(":kp-model"))
    implementation(libs.serialization.json)
    // The specs of S1.1 observe the log and the fold together (architecture.md §2: a test-only edge).
    testImplementation(project(":kp-engine"))
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
