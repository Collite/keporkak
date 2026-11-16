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
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
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

/** A channel that does what the real one does, and notes each write and force once it returns, by the path it names. */
private class Watched(private val real: FileChannel, private val name: String, private val events: MutableList<String>) : FileChannel() {
    override fun write(src: ByteBuffer): Int = real.write(src).also { events += "write $name" }
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.write(srcs, offset, length).also { events += "write $name" }
    override fun write(src: ByteBuffer, position: Long): Int = real.write(src, position).also { events += "write $name" }
    override fun force(metaData: Boolean) = real.force(metaData).also { events += "force($metaData) $name" }
    override fun read(dst: ByteBuffer): Int = real.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
    override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
    override fun position(): Long = real.position()
    override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
    override fun size(): Long = real.size()
    override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
    override fun implCloseChannel() = real.close()
}

/** A writer on [project] whose every write and sync lands in [events], in the order they returned. */
private fun watchedWriter(project: Path, events: MutableList<String>) =
    LogWriter.open(project, panda, Channels { path, options -> Watched(FileChannel.open(path, *options), project.relativize(path).toString().ifEmpty { "." }, events) })

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
    "the writer says which seq is the last on disk, so the next line's is known before it is written" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.last shouldBe 0L
            log.append(message("m-panda-000001"))
            log.append(record(), StageId("TOY/P0/S0.1"))
            log.last shouldBe 2L
        }
        LogWriter.open(project, panda).use { it.last shouldBe 2L }
    }
    "the writer says whether a stage's file is closed, now or after a restart" {
        val project = throwawayProject()
        LogWriter.open(project, panda).use { log ->
            log.isClosed(stage) shouldBe false
            log.append(stageMove("exited"), stage)
            log.isClosed(stage) shouldBe true
            log.isClosed(StageId("KP/P1/S1.2")) shouldBe false
        }
        LogWriter.open(project, panda).use { it.isClosed(stage) shouldBe true }
    }
    "a line is on disk when append returns: it is written, then its file is forced" {
        val project = throwawayProject()
        val events = mutableListOf<String>()
        watchedWriter(project, events).use { log ->
            log.append(message("m-panda-000001"))
            events.clear()

            log.append(message("m-panda-000002"))

            events shouldBe listOf("write mail/KP/p1-s1.1.jsonl", "force(true) mail/KP/p1-s1.1.jsonl")
        }
    }
    "a file append creates is on disk when append returns: each new directory, and the file, are forced into their parent" {
        val project = throwawayProject()
        val events = mutableListOf<String>()
        watchedWriter(project, events).use { log ->
            events.clear()

            log.append(message("m-panda-000001"))

            events shouldBe listOf(
                "force(true) mail", // the new directory KP, in mail
                "write mail/KP/p1-s1.1.jsonl",
                "force(true) mail/KP/p1-s1.1.jsonl",
                "force(true) mail/KP", // the new file, in KP
            )
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
    "a stage's file is closed by that stage's own exit only: another stage's exit appended to it closes nothing, now or after a restart" {
        val project = throwawayProject()
        val otherExits = json("""{"v":1,"at":"2026-11-14T10:00:00Z","event":"transition","machine":"stage","subject":"KP/P1/S1.2","from":"merged","to":"exited","cause":"m-panda-000009"}""")
        LogWriter.open(project, panda).use { log ->
            log.append(otherExits, stage)
            log.append(record(), stage)
            log.append(otherExits, stage)
        }
        LogWriter.open(project, panda).use { log -> log.append(message("m-panda-000004")) }
        project.resolve("mail/KP/p1-s1.1.jsonl").seqs() shouldBe listOf(1, 2, 3, 4)
        LogReader(project, panda).read().lines.map { it.entry.seq.toInt() } shouldBe listOf(1, 2, 3, 4)
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
