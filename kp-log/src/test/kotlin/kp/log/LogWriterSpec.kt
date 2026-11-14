package kp.log

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kp.model.OfficeId
import kp.model.StageId
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val panda = OfficeId("panda")
private val stage = StageId("KP/P1/S1.1")
private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
private fun message(id: String, stage: String? = "KP/P1/S1.1", by: String = "seat:teamlead", at: String = "2026-11-14T09:00:00Z") = json(
    """{"v":1,"at":"$at","id":"$id","thread":"$id","from":"teamlead","to":"coder-1","kind":"assign",""" +
        (if (stage == null) "" else """"stage":"$stage",""") +
        """"subject":"assign","created":"$at","by":"$by"}""",
)
private fun record(at: String = "2026-11-14T09:00:00Z") = json("""{"v":1,"at":"$at","event":"fact","fact":"bora.present","subject":"bora","value":true}""")
private fun stageMove(to: String) = json("""{"v":1,"at":"2026-11-14T10:00:00Z","event":"transition","machine":"stage","subject":"KP/P1/S1.1","from":"passed","to":"$to","cause":"m-panda-000009"}""")
private fun Path.seqs() = readLines().map { Json.parseToJsonElement(it).jsonObject.getValue("seq").toString().toInt() }

class LogWriterSpec : StringSpec({
    "a message goes to its stage's file, and a line of no stage to the office's file for the month of its at" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.append(message("m-panda-000001"))
            log.append(record(at = "2026-11-30T23:59:59Z"))
            log.append(record(at = "2026-12-01T00:00:00Z"))
            log.append(message("m-panda-000002", stage = null, at = "2026-12-01T00:00:01Z"))
        }
        project.resolve("mail/KP/p1-s1.1.jsonl").seqs() shouldBe listOf(1)
        project.resolve("mail/_office/panda-2026-11.jsonl").seqs() shouldBe listOf(2)
        project.resolve("mail/_office/panda-2026-12.jsonl").seqs() shouldBe listOf(3, 4)
    }
    "a record goes to the stage file it is appended for" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { it.append(record(), StageId("TOY/P0/S0.1")) }
        project.resolve("mail/TOY/p0-s0.1.jsonl").seqs() shouldBe listOf(1)
    }
    "the office's month is the month of at in UTC" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { it.append(record(at = "2026-12-01T00:30:00+01:00")) }
        project.resolve("mail/_office/panda-2026-11.jsonl").seqs() shouldBe listOf(1)
    }
    "seq continues from the highest on disk, across stage files and the office's files" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.append(message("m-panda-000001"))
            log.append(record())
            log.append(record(), StageId("TOY/P0/S0.1"))
        }
        LogWriter.open(project, panda).use { it.append(record()) }
        project.resolve("mail/_office/panda-2026-11.jsonl").seqs() shouldBe listOf(2, 4)
    }
    "a line is on disk when append returns" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.append(message("m-panda-000001"))
            project.resolve("mail/KP/p1-s1.1.jsonl").readText().endsWith("\n") shouldBe true
        }
    }
    "the log assigns seq; a draft that brings one is refused" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            shouldThrow<LogRefusal> { log.append(JsonObject(record() + ("seq" to JsonPrimitive(7)))) }.message shouldContain "seq"
        }
        project.resolve("mail/_office").exists() shouldBe false
    }
    "a line that breaks the contract is refused and nothing is written" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            shouldThrow<LogRefusal> { log.append(JsonObject(message("m-panda-000001") + ("unknown" to JsonPrimitive(true)))) }
            log.append(message("m-panda-000002"))
        }
        project.resolve("mail/KP/p1-s1.1.jsonl").seqs() shouldBe listOf(1)
    }
    "a hand-carried message is never written by the office's writer" {
        LogWriter.open(throwawayProject(), panda).use { log ->
            shouldThrow<LogRefusal> { log.append(message("m-hand-000001", by = "hand:teamlead")) }.message shouldContain "hand"
        }
    }
    "a message is appended for its own stage only" {
        LogWriter.open(throwawayProject(), panda).use { log ->
            shouldThrow<LogRefusal> { log.append(message("m-panda-000001"), StageId("TOY/P0/S0.1")) }
        }
    }
    "a stage file that ended in exited or closed takes no more lines, now or after a restart" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.append(stageMove("exited"), stage)
            shouldThrow<LogRefusal> { log.append(record(), stage) }.message shouldContain "closed"
        }
        LogWriter.open(project, panda).use { log ->
            shouldThrow<LogRefusal> { log.append(message("m-panda-000002")) }
            log.append(record(), StageId("KP/P1/S1.2"))
        }
        project.resolve("mail/KP/p1-s1.2.jsonl").seqs() shouldBe listOf(2)
    }
    "a log whose last line is torn is not written to; the writer refuses to start" {
        val project = throwawayProject()
        val file = project.resolve("mail/KP/p1-s1.1.jsonl")
        LogWriter.open(project, panda).use { it.append(message("m-panda-000001")) }
        file.writeText(file.readText() + """{"v":1,"seq":2""")
        shouldThrow<LogRefusal> { LogWriter.open(project, panda) }.message shouldContain "$file:2"
        file.readText().endsWith("""{"v":1,"seq":2""") shouldBe true
    }
    "a second writer on the same project is refused, in this process and in another" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use {
            shouldThrow<LogRefusal> { LogWriter.open(project, panda) }.message shouldContain "another writer"
        }
        val other = ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), "kp.log.HoldTheWriter", "$project").start()
        try {
            other.inputStream.bufferedReader().readLine() shouldBe "holding"
            shouldThrow<LogRefusal> { LogWriter.open(project, panda) }.message shouldContain "another writer"
        } finally {
            other.outputStream.close()
            other.waitFor(30, TimeUnit.SECONDS) shouldBe true
        }
        LogWriter.open(project, panda).use { it.append(record()) }
    }
})

private fun java(): String = ProcessHandle.current().info().command().orElse("java")

/** Holds the writer of the project named by its argument until its standard input closes. */
object HoldTheWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        LogWriter.open(Path.of(args[0]), panda).use {
            println("holding")
            System.out.flush()
            System.`in`.read()
        }
    }
}
