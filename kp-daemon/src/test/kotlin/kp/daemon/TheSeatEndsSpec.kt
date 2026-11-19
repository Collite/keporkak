package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.mailroom.Mailroom
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.model.Message
import kp.model.OfficeId
import kp.seatapi.Binding
import kp.seatapi.SeatApi
import kp.seatapi.Tokens
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createDirectories

/**
 * KP/P2/S2.7 — the seat ends of the engine and the mailroom: the sentences of T1 that `kp` and a rehearsal show, at the
 * mailroom's one way in as S1.3 gives it — here behind the daemon: a rehearsal of a walk (`Rehearsed`), `kp` run as a
 * subprocess against its socket, and the seat API in front of a mailroom of its own. The engine's and the mailroom's
 * other sentences are `kp-mailroom`'s (`TheSeatEndsSpec` there).
 */
class TheSeatEndsSpec : FreeSpec({
    "a held message that is refused when it is let go leaves no open ticket" {
        Rehearsed("held-then-refused").use { office ->
            office.kp("away", root = true)
            office.rehearsal.awaitBora() shouldBe null // the Deputy holds the proposal, with a ticket; then the list loses its fence
            val whileHeld = office.rehearsal.office.state.openTickets().values.map { it.item }

            office.kp("inbox", root = true) // Bora's return lets it go, and it is refused

            office.log().story().takeLast(2) shouldBe listOf("assignment-proposal refused", "nudge by mailroom delivered")
            whileHeld shouldBe listOf(office.log().messages("assignment-proposal").single().id.value)
            office.rehearsal.office.state.openTickets() shouldBe emptyMap()
            office.kp("inbox").text shouldBe "nothing waits for Bora\n"
        }
    }

    "input that is no message is answered, never thrown — to a seat, to `kp` and in a rehearsal alike" - {
        "to a seat: its tool answers an error that names the field, and nothing is written" {
            val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "no-message-")
            val mailroom = Mailroom.start(project, OfficeId("panda"), RuleFiles(checkout), FactSource { _, _ -> null },
                Operations { _, done -> done(OperationResult.Ok()) }, NoWakes, Clock { Instant.parse("2026-11-19T09:00:00Z") })
            val tokens = Tokens().apply { register(TOKEN, Binding("coder-1", "coder", "panda", "REHEARSAL/P2/S2.1", "claude-haiku-5-5", "low")) }
            val before = Log(project.resolve("mail")).lines.size
            SeatApi.start(mailroom, tokens, "127.0.0.1:0").use { api ->
                val base = "http://127.0.0.1:${api.address.port}/mcp"

                listOf("""{"subject": "no kind"}""", """{"kind": "started", "stage": "S2.1"}""", """{"kind": "started", "subject": 7}""").map { send(base, it) } shouldBe
                    listOf("kind: missing", "stage: not a stage", "subject: not text").map { "error · $it" }
            }
            mailroom.close()
            Log(project.resolve("mail")).lines.size shouldBe before
        }

        "to `kp`: it prints why and sends nothing, and the daemon goes on" {
            Rehearsed("held-then-refused").use { office ->
                val before = office.log().lines.size

                val ran = office.kp("reverse", "nowhere", "minor", root = true)

                ran.code shouldNotBe ControlSocket.FAILED
                ran.text shouldNotContain "could not carry"
                ran.text shouldStartWith "kp: "
                office.log().lines.drop(before).filterIsInstance<Message>().shouldBeEmpty()
                office.kp("status").code shouldBe 0
            }
        }

        "in a rehearsal: it prints the error, naming the step and the field, writes nothing, and the walk stops there" {
            Rehearsed("no-message").use { office ->
                office.said.filter { it.startsWith("the rehearsal stopped") }.shouldBeEmpty()
                office.said.filter { it.startsWith("the walk stops here") }.shouldNotBeEmpty().single().let {
                    it shouldContain "step 1"
                    it shouldContain "re:"
                }
                office.log().lines.filterIsInstance<Message>().shouldBeEmpty()
                office.kp("status").text.lines() shouldContain "stage REHEARSAL/P2/S2.1: assigned"
            }
        }
    }
})

private const val TOKEN = "kp-spec-no-message-0123456789abcdef"

/** Seats that are never idle: nothing is woken. */
private object NoWakes : Deliveries {
    override fun idle(seat: String) = false
    override fun wake(seat: String, message: Message) = false
}

/**
 * A seat's `send` with [arguments], posted as an MCP client posts a `tools/call` to the stateless API: `error · <the
 * tool's text>` when it is an error of the tool, else `answer · <its text>`.
 */
private fun send(base: String, arguments: String): String {
    val body = """{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "send", "arguments": $arguments}}"""
    val request = HttpRequest.newBuilder(URI.create(base)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .header("Authorization", "Bearer $TOKEN").POST(HttpRequest.BodyPublishers.ofString(body)).build()
    val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
    val json = answer.lines().map { it.removePrefix("data:").trim() }.first { it.startsWith("{") }
    val result = kotlinx.serialization.json.Json.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonObject)["result"] as kotlinx.serialization.json.JsonObject }
    val text = ((result["content"] as kotlinx.serialization.json.JsonArray).first() as kotlinx.serialization.json.JsonObject)["text"].toString().trim('"')
    val error = (result["isError"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    return (if (error) "error · " else "answer · ") + text.substringBefore(" (").substringBefore(" —").substringBefore(" of ")
}
