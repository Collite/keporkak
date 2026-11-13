plugins { alias(libs.plugins.kotlin.serialization) }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.acp)
    testImplementation(libs.mcp)
    testRuntimeOnly(libs.junit.platform.launcher)
}
