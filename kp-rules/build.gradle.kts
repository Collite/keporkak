dependencies {
    implementation(project(":kp-model"))
    testImplementation(libs.kaml)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
