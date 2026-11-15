package kp.driver

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kp.model.SeatId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.time.Duration.Companion.seconds

/**
 * S2.1 DONE — neither token is in any transcript, evidence file or spec output. A seat lives a whole life — start,
 * a turn in which the agent itself repeats both its Claude token and the session token it was given, a permission,
 * a cancel, a kill, a load under a fresh token and the same again — while this JVM's output is captured. Then
 * every transcript, every fake agent's record, every fact, every result and the captured output are searched.
 * (The handshake's evidence file is searched by the handshake itself, T8.)
 */
class NoTokenSpec : StringSpec({
    "neither token is in any transcript, evidence or spec output" {
        Office().use { office ->
            val output = ByteArrayOutputStream()
            val (out, err) = System.out to System.err
            System.setOut(PrintStream(output, true)); System.setErr(PrintStream(output, true))
            val said = mutableListOf<String>()
            val seat = SeatId("coder-1")
            try {
                val first = office.agent("""{"onEof": "linger", "turns": [
                    [{"do": "leak"}, {"do": "ask", "id": "p1", "title": "env", "input": {"command": "env"}, "options": ["allow_once", "reject_once"]}, {"do": "end"}],
                    [{"do": "run", "seconds": 300}, {"do": "end"}]]}""")
                val again = office.agent("""{"turns": [[{"do": "leak"}, {"do": "recall"}, {"do": "end"}]]}""")
                val driver = office.driver(answerer = { said += it.toString(); Answer.ALLOW })
                val started = driver.start(office.session("coder-1", first)).ok()
                said += started.toString()
                said += driver.prompt(seat, "show me your secrets").toString()
                val long = async { driver.prompt(seat, "wait") }
                eventually(10.seconds) { first.received("child") shouldHaveSize 1 }
                said += driver.cancel(seat).toString()
                said += long.await().toString()
                said += driver.kill(seat, Duration.ofMillis(200)).toString()
                val loaded = driver.load(office.session("coder-1", again), started.sessionId).ok()
                said += loaded.toString()
                said += driver.prompt(seat, "again").toString()
                said += SeatFacts.names.map { driver.observe(seat, it, UsageWindow.SESSION).toString() }
                said += office.facts.map { it.toString() }
                val secrets = listOf(office.claudeToken, started.token.value, loaded.token.value)
                val transcripts = Files.walk(office.seats.resolve("_transcripts")).use { it.filter { f -> f.isRegularFile() }.toList() }
                val records = office.records.toList()
                records.filter { it.isRegularFile() } shouldHaveSize 2
                office.transcript("coder-1").count { it.contains("my token is ${Transcript_HIDDEN} and my headers are Bearer $Transcript_HIDDEN") } shouldBe 3 // the first turn, its replay, the turn after the load

                val found = secrets.indices.flatMap { i ->
                    (transcripts + records).filter { it.readText().contains(secrets[i]) }.map { "secret $i in ${it.fileName}" } +
                        said.filter { it.contains(secrets[i]) }.map { "secret $i in a result" } +
                        listOfNotNull(if (output.toString().contains(secrets[i])) "secret $i in the output" else null)
                }
                found shouldBe emptyList()
            } finally {
                System.setOut(out); System.setErr(err)
            }
        }
    }
})

private const val Transcript_HIDDEN = "‹hidden›"
