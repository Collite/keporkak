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
    // The logging spec asks SLF4J — the ACP SDK's logging — what it would write.
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("kp.daemon.MainKt")
    applicationName = "kp-daemon"
}

// The specs run `scripts/kp` as a subprocess against a rehearsal of a walk: a change to it runs them again. The walks,
// rules, robots, pool and personas are every test task's inputs (the root convention, S2.8).
fun Test.readsTheCheckout() {
    inputs.files(rootProject.file("scripts/kp")).withPropertyName("kp_client")
}

// The hero specs (S1.6) are `just hero`'s, which `just check` runs: the `test` task leaves them out, so they run once.
tasks.named<Test>("test") {
    exclude("kp/daemon/hero/**")
}

// `just hero` (architecture.md §7): H-1′, H-2′, H-3 and the refusal sweep through the real mailroom. Their report,
// kp-daemon/build/hero/report.txt, is written by the specs; a run that executes none leaves none, and the recipe fails.
tasks.register<Test>("hero") {
    description = "The acceptance specs of phase 1 (S1.6): the hero scenarios and the refusal sweep."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("kp/daemon/hero/**")
    readsTheCheckout()
    val report = layout.buildDirectory.file("hero/report.txt")
    outputs.file(report).withPropertyName("report")
    doFirst { delete(report) }
}

tasks.named<Test>("test") {
    // DaemonProcessSpec starts the daemon through the launcher `just install` installs.
    dependsOn("installDist")
    readsTheCheckout()
}
