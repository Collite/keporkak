dependencies {
    implementation(project(":kp-mailroom"))
    implementation(project(":kp-model"))
    implementation(libs.serialization.json)
    // The five seat tools as an MCP server over streamable HTTP (contracts.md §5), on Ktor's CIO engine — the Ktor the
    // MCP SDK resolves. Both log through SLF4J; no provider: their log goes nowhere.
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.slf4j.api)
    // The specs (S2.3 T1): a real mailroom on a throwaway log, seeded through the log's writer; facts are the engine's
    // types; an MCP client and a plain HTTP client in front of the API.
    testImplementation(project(":kp-engine"))
    testImplementation(project(":kp-log"))
    testImplementation(libs.mcp.client)
    testImplementation(libs.ktor.client.cio)
    // The specs read rules/message-kinds.yaml: no kind's name may be in the API's or the engine's source (S2.3 DONE).
    testImplementation(libs.kaml)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The specs judge under master's rules, robots, pool files and personas — every test task's inputs (the root
// convention, S2.8) — and run the stop-gate hook against the API: a change to it runs them again.
tasks.named<Test>("test") {
    inputs.files(rootProject.file("hooks/stop-gate")).withPropertyName("stopGateHook").withPathSensitivity(PathSensitivity.RELATIVE)
}

// `just handshake tools` (S2.3 T8): a seat built by S2.2's builder, on the pinned adapter, in front of this server and a
// mailroom on a throwaway log. A source set of its own: it may use kp-driver, the API's main source may not. `check`
// compiles it and never runs it, and CI never runs it — it spends tokens.
val handshake: SourceSet = sourceSets.create("handshake") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations["handshakeImplementation"].extendsFrom(configurations["implementation"])
configurations["handshakeRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])
dependencies {
    "handshakeImplementation"(project(":kp-driver"))
    "handshakeImplementation"(project(":kp-engine"))
    "handshakeImplementation"(project(":kp-log"))
    "handshakeImplementation"(libs.kaml)
}
tasks.named("check") { dependsOn(handshake.classesTaskName) }
tasks.register("handshakeClasspath") {
    description = "Writes the tools handshake's run-time classpath to build/handshake.classpath for `just handshake tools`."
    dependsOn(handshake.classesTaskName)
    val out = layout.buildDirectory.file("handshake.classpath")
    val classpath = handshake.runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
