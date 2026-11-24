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
    // The seats' specs (S2.4): S2.1's fake agent as every seat's process.
    testImplementation(testFixtures(project(":kp-driver")))
    // GitHub's facts (S3.2): the responses recorded from Collite/kp-toy and GitHub's App endpoints, on one local server.
    testImplementation(testFixtures(project(":kp-facts")))
    testImplementation(testFixtures(project(":kp-ops")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("kp.daemon.MainKt")
    applicationName = "kp-daemon"
}

// The specs run `scripts/kp` as a subprocess against a rehearsal of a walk: a change to it runs them again. The walks,
// rules, robots, pool and personas are every test task's inputs (the root convention, S2.8).
fun Test.readsTheCheckout() {
    inputs.files(rootProject.file("scripts/kp")).withPropertyName("kp_client").withPathSensitivity(PathSensitivity.RELATIVE)
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
    // TheOrdersSpec runs the Python walker, `scripts/check-rules.py --trace`, beside each order: a change to it alone runs
    // the heroes again, as kp-engine's and kp-mailroom's specs (S3.10 T5).
    inputs.files(rootProject.file("scripts/check-rules.py")).withPropertyName("walker").withPathSensitivity(PathSensitivity.RELATIVE)
    val report = layout.buildDirectory.file("hero/report.txt")
    outputs.file(report).withPropertyName("report")
    doFirst { delete(report) }
}

tasks.named<Test>("test") {
    // DaemonProcessSpec starts the daemon through the launcher `just install` installs.
    dependsOn("installDist")
    readsTheCheckout()
}

// The seats' specs and the heroes start S2.1's fake agent as a child JVM on the test classpath (S2.4); the seats' homes
// are built from the checkout's renders, skills, profiles, hooks and the seat's two programs (S3.7).
tasks.withType<Test>().configureEach {
    val classpath = sourceSets["test"].runtimeClasspath
    jvmArgumentProviders.add(WorktreePaths("kp.driver.fake.classpath") { classpath.asPath }) // the test classpath: an input already
    for (folder in listOf("renders", "skills", "docs/profiles", "hooks", "seat")) {
        inputs.files(rootProject.fileTree(folder)).withPropertyName("seats_" + folder.replace("/", "_")).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

/**
 * A system property a test reads that names paths of this worktree (S2.9 T7): the paths are no input of the test, so
 * its build-cache key is no worktree's — what they hold is declared beside, by path relative to the checkout.
 */
class WorktreePaths(@get:Input val name: String, @get:Internal val paths: () -> String) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-D$name=${paths()}")
}
