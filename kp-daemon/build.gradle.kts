plugins {
    // `installDist` makes the launcher the systemd user unit starts (S1.5 T7; `just install` copies it).
    application
}

dependencies {
    implementation(project(":kp-mailroom"))
    implementation(project(":kp-facts"))
    implementation(project(":kp-ops"))
    implementation(project(":kp-driver"))
    implementation(project(":kp-seat-api"))
    implementation(project(":kp-board"))
    // The daemon wires the mailroom and re-judges logs through the engine (`kp check`, contracts.md §6).
    implementation(project(":kp-model"))
    implementation(project(":kp-rules"))
    implementation(project(":kp-engine"))
    implementation(project(":kp-log"))
    implementation(libs.serialization.json)
    // A walk of rules/walks/ is YAML: what `just rehearse` plays.
    implementation(libs.kaml)
    testImplementation(libs.kotest)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("kp.daemon.MainKt")
    applicationName = "kp-daemon"
}

// The specs run `scripts/kp` as a subprocess against a rehearsal of a walk: a change to either runs them again.
tasks.named<Test>("test") {
    inputs.files(rootProject.file("scripts/kp")).withPropertyName("kp_client")
    for (folder in listOf("rules", "robots", "pool", "agents")) {
        inputs.files(rootProject.fileTree(folder)).withPropertyName("checkout_$folder").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
