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
