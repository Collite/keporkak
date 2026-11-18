plugins {
    // The scripted fake agent and the source scan are test fixtures (S2.4): the daemon's specs and `just rehearse
    // <walk> --seats fake` start the same fake agent the driver's specs do.
    `java-test-fixtures`
}

dependencies {
    implementation(project(":kp-model"))
    implementation(libs.serialization.json)
    // The ACP client (architecture.md §5, §6). It brings kotlinx-coroutines as part of its API.
    implementation(libs.acp)
    // The SDK logs through kotlin-logging, which needs an SLF4J API at run time; no provider: its log goes nowhere.
    runtimeOnly(libs.slf4j.api)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    // The packaging data, the personas' and the profiles' frontmatter (S2.2 T2); the specs also read rules/vocabulary.yaml.
    implementation(libs.kaml)
    testRuntimeOnly(libs.junit.platform.launcher)
    testFixturesImplementation(libs.serialization.json)
}

// The specs start the scripted fake agent as a child JVM on the test classpath (S2.1 T1). They also read the
// checkout's personas, renders, skills, packaging data, profiles and hooks (S2.2): a change there runs them again.
tasks.named<Test>("test") {
    val classpath = sourceSets["test"].runtimeClasspath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dkp.driver.fake.classpath=${classpath.asPath}") })
    for (folder in listOf("agents", "renders", "skills", "docs/profiles", "hooks")) {
        inputs.files(rootProject.fileTree(folder)).withPropertyName("checkout_" + folder.replace("/", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
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

// `just rehearse <walk> --seats fake` (S2.4 T8): the fake agent's run-time classpath, written to build/fake.classpath.
tasks.register("fakeClasspath") {
    description = "Writes the fake agent's run-time classpath to build/fake.classpath for `just rehearse <walk> --seats fake`."
    val fixtures = sourceSets["testFixtures"]
    dependsOn(fixtures.classesTaskName)
    val out = layout.buildDirectory.file("fake.classpath")
    val classpath = fixtures.runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
