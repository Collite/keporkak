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

// `just handshake` (S2.1 T8): the driver against the real, pinned adapter. A source set of its own, outside the
// driver's main source; `check` compiles it and never runs it, and CI never runs it — it spends tokens.
val handshake: SourceSet = sourceSets.create("handshake") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations["handshakeImplementation"].extendsFrom(configurations["implementation"])
configurations["handshakeRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
dependencies { "handshakeImplementation"(libs.kaml) }
tasks.named("check") { dependsOn(handshake.classesTaskName) }
tasks.register("handshakeClasspath") {
    description = "Writes the handshake's run-time classpath to build/handshake.classpath for `just handshake`."
    dependsOn(handshake.classesTaskName)
    val out = layout.buildDirectory.file("handshake.classpath")
    val classpath = handshake.runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
