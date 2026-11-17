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

// The specs judge under master's rules, robots, pool files and personas: a change there runs them again.
tasks.named<Test>("test") {
    for (folder in listOf("rules", "robots", "pool", "agents")) {
        inputs.files(rootProject.fileTree(folder)).withPropertyName("checkout_$folder").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
