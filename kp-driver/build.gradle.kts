dependencies {
    implementation(project(":kp-model"))
    implementation(libs.serialization.json)
    // The ACP client (architecture.md §5, §6). It brings kotlinx-coroutines as part of its API.
    implementation(libs.acp)
    // The SDK logs through kotlin-logging, which needs an SLF4J API at run time; no provider: its log goes nowhere.
    runtimeOnly(libs.slf4j.api)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    // The specs read rules/vocabulary.yaml to hold the driver's fact names to it (S2.1 DONE).
    testImplementation(libs.kaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The specs start the scripted fake agent as a child JVM on the test classpath (S2.1 T1).
tasks.named<Test>("test") {
    val classpath = sourceSets["test"].runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dkp.driver.fake.classpath=${classpath.asPath}") })
}
