package kp.seatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S2.7 T6 (issue #35) — `just handshake tools`, run in a spec with the driver's fake agent in place of the pinned adapter:
 * no token is spent and the fake calls no tool, so the walk's steps about the seat fail — what this spec reads is the
 * evidence file the run names: it carries the run's throwaway log, and the 401 it says the API answered is one the run
 * observed.
 */
class HandshakeEvidenceSpec : StringSpec({
    "the evidence of a tools handshake run with the fake agent holds lines of its throwaway log, and the 401 the run observed" {
        val run = Files.createTempDirectory(root.resolve("build").toPath().createDirectories(), "handshake-")
        val script = run.resolve("agent.json").also {
            it.writeText("""{"record": "${run.resolve("agent.record.jsonl")}", "turns": [[{"do": "say", "text": "I call no tool."}, {"do": "end"}], [{"do": "say", "text": "ok"}, {"do": "end"}]]}""")
        }
        val agent = run.resolve("agent.command").also { it.writeText(fakeAgent(script).joinToString("\n")) }
        val token = run.resolve("claude-token")
        Files.createFile(token, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        token.writeText(ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } + "\n")

        val process = ProcessBuilder(java(), "-cp", System.getProperty("kp.handshake.classpath"), "kp.seatapi.handshake.ToolsHandshakeKt",
            "--root", root.absolutePath, "--office", "panda", "--agent", agent.absolutePathString(), "--token-file", token.absolutePathString())
            .redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.MINUTES) shouldBe true
        val evidence = Path.of(said.lines().last { it.startsWith("RESULT:") }.substringAfter("evidence: ").trim())
        val text = evidence.readText().also { evidence.deleteIfExists() }
        // Under the office's scratch root — panda's, `~/Dev/_scratch` — as every handshake's evidence is (S3.10 T6).
        evidence.parent shouldBe Path.of(System.getProperty("user.home"), "Dev/_scratch/handshakes")
        val log = text.substringAfter("## The throwaway log\n```\n").substringBefore("```").lines().filter(String::isNotBlank).map(::json)

        log.mapNotNull { it["kind"]?.jsonPrimitive?.content ?: it["event"]?.jsonPrimitive?.content } shouldContainAll listOf("transition", "judged", "assign", "delivered")
        log.first()["seq"]?.jsonPrimitive?.content shouldBe "1"
        text shouldContain "a call without the session token was answered 401"
    }
})

private fun java(): String = Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()

/** The driver's scripted fake agent (kp-driver's test fixtures), as a command: its classpath is the one the build hands this task. */
private fun fakeAgent(script: Path): List<String> = listOf(java(), "-Xshare:auto", "-XX:TieredStopAtLevel=1",
    "-cp", System.getProperty("kp.driver.fake.classpath") ?: error("kp.driver.fake.classpath is not set (kp-seat-api/build.gradle.kts)"),
    "kp.driver.fake.FakeAgentKt", script.absolutePathString())
