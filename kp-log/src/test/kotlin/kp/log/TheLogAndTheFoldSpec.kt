package kp.log

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kp.engine.MachineState
import kp.engine.State
import kp.engine.Wait
import kp.engine.fold
import kp.model.EffortId
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P1/S1.1 — the log and the fold. One spec per behaviour the stage list names (T1), observed at the seam the list
 * gives: `kp-log`'s append and read on a throwaway directory, and the engine's `fold` on what was read.
 * The recorded stage is `src/test/resources/fixtures/recorded-stage/` — a stage file and an office file, 24 lines.
 */
class TheLogAndTheFoldSpec : StringSpec({
    "a line appended is read back as the same message or record, in sequence order across a stage's file and the office's file" {
        val project = throwawayProject()
        val assign = json("""{"v":1,"at":"2026-11-14T09:00:00Z","id":"m-panda-000101","thread":"m-panda-000101","from":"teamlead","to":"coder-1","kind":"assign","stage":"KP/P1/S1.1","subject":"assign KP/P1/S1.1","created":"2026-11-14T09:00:00Z","by":"seat:teamlead"}""")
        val delivered = json("""{"v":1,"at":"2026-11-14T09:00:00Z","event":"delivered","id":"m-panda-000101","to":["coder-1"],"class":"A"}""")
        val seatBusy = json("""{"v":1,"at":"2026-11-14T09:00:01Z","event":"transition","machine":"seat","subject":"coder-1","from":"ready","to":"busy","cause":"m-panda-000101"}""")

        LogWriter.open(project, OfficeId("panda")).use { log ->
            log.append(assign)
            log.append(delivered, StageId("KP/P1/S1.1"))
            log.append(seatBusy)
        }
        val read = LogReader(project, OfficeId("panda")).read()

        read.problems shouldBe emptyList()
        read.lines.map { "${it.entry.seq} ${it.file.fileName}" } shouldBe listOf("1 p1-s1.1.jsonl", "2 p1-s1.1.jsonl", "3 panda-2026-11.jsonl")
        read.lines.map { JsonObject(it.entry.json - "seq") } shouldBe listOf(assign, delivered, seatBusy)
    }

    "what the Python recorder writes, the Kotlin log reads" {
        val project = throwawayProject()

        val recorder = run("just", "record", "started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P1/S1.1", "--project", "$project")
        val read = LogReader(project, OfficeId("panda")).readHand(EffortId("KP"))

        recorder.exitCode shouldBe 0
        read.problems shouldBe emptyList()
        val written = project.resolve("mail/KP/_hand/p1-s1.1.jsonl").readText()
        read.lines.single().entry.json shouldBe Json.parseToJsonElement(written)
        (read.lines.single().entry as Message).by shouldBe "hand:coder"
    }

    "folding a recorded stage gives its state, its counters, its open deadlines and what each seat has not read" {
        val read = LogReader(recordedStage(), OfficeId("panda")).read()

        val state = fold(read.lines.map { it.entry })

        read.problems shouldBe emptyList()
        state shouldBe State(
            seq = 24,
            at = "2026-11-14T16:00:00Z",
            rules = "5f0c6a2d9b1e4f8a7c3d2e1b0a9f8e7d6c5b4a3928170f6e5d4c3b2a19087f6e",
            machines = mapOf(
                // building → ready_for_review at 15:00, then T-74's re-arm moved it to itself at 16:00
                "stage" to mapOf("KP/P1/S1.1" to MachineState(state = "ready_for_review", previous = "building", since = "2026-11-14T16:00:00Z", cause = "m-panda-000105", rule = "T-74")),
                "seat" to mapOf("coder-1" to MachineState(state = "busy", previous = "ready", since = "2026-11-14T09:00:01Z", cause = "m-panda-000101", rule = null)),
                "presence" to mapOf("bora" to MachineState(state = "present", previous = "absent", since = "2026-11-14T08:30:00Z", cause = "bora.present", rule = null)),
            ),
            counters = mapOf("KP/P1/S1.1" to mapOf("stops" to 1L, "resumes" to 1L, "review_requests" to 1L, "review_request_expiries" to 1L)),
            // every message on the log was delivered
            pending = emptyMap(),
            // the assign's and the stop's waits ended with their replies; the mailroom's nudge does not end the review-request's
            deadlines = mapOf(
                "m-panda-000105" to Wait(kind = "review-request", stage = "KP/P1/S1.1", from = "coder-1", to = "teamlead", deadline = "2026-11-14T17:00:00Z", expiries = 1),
            ),
            held = emptyMap(),
            tickets = emptyMap(),
            unread = mapOf(
                "coder-1" to listOf("m-panda-000104"),
                "teamlead" to listOf("m-panda-000102", "m-panda-000105", "m-panda-000106"),
            ),
            // assign + started; stop + triage; review-request + nudge
            hops = mapOf("m-panda-000101" to 2, "m-panda-000103" to 2, "m-panda-000105" to 2),
            refusals = emptyMap(),
        )
    }

    "folding the same records twice, or a prefix and then the rest, gives the same state" {
        val lines = LogReader(recordedStage(), OfficeId("panda")).read().lines.map { it.entry }

        val whole = fold(lines)

        fold(lines, whole) shouldBe whole
        val everyCut = (0..lines.size).map { cut -> fold(lines.drop(cut), fold(lines.take(cut))) }
        everyCut.toSet() shouldBe setOf(whole)
    }

    "a file whose last line is torn is reported with its path and line, and nothing is repaired" {
        val project = throwawayProject()
        val file = project.resolve("mail/KP/p1-s1.1.jsonl").also { it.parent.createDirectories() }
        file.writeText(
            """{"v":1,"seq":1,"at":"2026-11-14T09:00:00Z","id":"m-panda-000101","thread":"m-panda-000101","from":"teamlead","to":"coder-1","kind":"assign","stage":"KP/P1/S1.1","subject":"assign KP/P1/S1.1","created":"2026-11-14T09:00:00Z","by":"seat:teamlead"}""" + "\n" +
                """{"v":1,"seq":2,"at":"2026-11-14T09:00:00Z","event":"delivered","id":"m-panda-000101","to":["cod""",
        )
        val before = file.readBytes()

        val read = LogReader(project, OfficeId("panda")).read()

        read.problems.map { it.toString() } shouldBe listOf("$file:2: the last line is torn (no line end)")
        read.lines.map { it.entry.seq.toInt() } shouldBe listOf(1)
        file.readBytes() shouldBe before
    }
})

private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
