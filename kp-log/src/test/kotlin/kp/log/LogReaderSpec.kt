package kp.log

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.model.EffortId
import kp.model.OfficeId
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

private val panda = OfficeId("panda")
private fun fact(seq: Int, at: String) = """{"v":1,"seq":$seq,"at":"$at","event":"fact","fact":"bora.present","subject":"bora","value":true}"""
private fun exited(seq: Int, at: String) = """{"v":1,"seq":$seq,"at":"$at","event":"transition","machine":"stage","subject":"KP/P0/S0.3","from":"merged","to":"exited","cause":"m-panda-000001"}"""
private fun Path.file(name: String, vararg lines: String): Path =
    resolve(name).also { it.parent.createDirectories(); it.writeText(lines.joinToString("") { line -> line + "\n" }) }
private fun LogRead.seqs() = lines.map { it.entry.seq.toInt() }
private fun LogRead.texts() = problems.map { it.toString() }

class LogReaderSpec : StringSpec({
    "open stage files and the office's files since the oldest open stage are read, merged in seq order; a closed stage is skipped" {
        val project = throwawayProject()
        project.file("mail/_office/panda-2026-09.jsonl", fact(1, "2026-09-30T10:00:00Z"))
        project.file("mail/KP/p0-s0.3.jsonl", fact(2, "2026-10-01T10:00:00Z"), fact(5, "2026-10-03T10:00:00Z"), exited(9, "2026-11-02T10:00:00Z"))
        project.file("mail/_office/panda-2026-10.jsonl", fact(3, "2026-10-02T10:00:00Z"), fact(6, "2026-10-04T10:00:00Z"))
        project.file("mail/KP/p1-s1.1.jsonl", fact(4, "2026-10-02T11:00:00Z"), fact(8, "2026-11-01T10:00:00Z"))
        project.file("mail/TOY/p0-s0.1.jsonl", fact(7, "2026-11-01T09:00:00Z"))
        project.file("mail/_office/panda-2026-11.jsonl", fact(10, "2026-11-03T10:00:00Z"))
        project.file("mail/_office/other-2026-11.jsonl", fact(1, "2026-11-03T10:00:00Z"))

        val read = LogReader(project, panda).read()

        read.texts() shouldBe emptyList()
        read.seqs() shouldBe listOf(3, 4, 6, 7, 8, 10)
    }
    "with no open stage, the office's current file is read" {
        val project = throwawayProject()
        project.file("mail/KP/p0-s0.3.jsonl", fact(1, "2026-10-01T10:00:00Z"), exited(2, "2026-10-02T10:00:00Z"))
        project.file("mail/_office/panda-2026-10.jsonl", fact(3, "2026-10-03T10:00:00Z"))
        project.file("mail/_office/panda-2026-11.jsonl", fact(4, "2026-11-01T10:00:00Z"))

        LogReader(project, panda).read().seqs() shouldBe listOf(4)
    }
    "a line that is not a message or record is reported with its path and line, and the lines around it are read" {
        val project = throwawayProject()
        val file = project.file("mail/KP/p1-s1.1.jsonl", fact(1, "2026-11-01T10:00:00Z"), """{"v":1,"seq":2,"at":"2026-11-01T10:00:01Z","event":"nonsense"}""", "{broken", fact(4, "2026-11-01T10:00:03Z"))

        val read = LogReader(project, panda).read()

        read.seqs() shouldBe listOf(1, 4)
        read.problems.filterIsInstance<Unparseable>().map { it.line } shouldBe listOf(2, 3)
        read.texts().last() shouldBe "seq jumps from 1 to 4"
        read.texts().first().startsWith("$file:2: not a message or record") shouldBe true
    }
    "a gap in seq that no file accounts for is reported" {
        val project = throwawayProject()
        project.file("mail/KP/p1-s1.1.jsonl", fact(1, "2026-11-01T10:00:00Z"), fact(4, "2026-11-01T10:00:03Z"))
        project.file("mail/_office/panda-2026-11.jsonl", fact(2, "2026-11-01T10:00:01Z"))

        LogReader(project, panda).read().texts() shouldBe listOf("seq jumps from 2 to 4")
    }
    "a repeated seq is reported where it is used again" {
        val project = throwawayProject()
        project.file("mail/KP/p1-s1.1.jsonl", fact(1, "2026-11-01T10:00:00Z"), fact(2, "2026-11-01T10:00:01Z"))
        val office = project.file("mail/_office/panda-2026-11.jsonl", fact(2, "2026-11-01T10:00:02Z"), fact(3, "2026-11-01T10:00:03Z"))

        val read = LogReader(project, panda).read()

        read.seqs() shouldBe listOf(1, 2, 2, 3)
        read.texts() shouldBe listOf("$office:1: seq 2 is used again")
    }
    "a line repeating the seq of a closed stage's line is reported too" {
        val project = throwawayProject()
        project.file("mail/KP/p0-s0.3.jsonl", fact(1, "2026-11-01T10:00:00Z"), exited(2, "2026-11-01T10:00:01Z"))
        val open = project.file("mail/KP/p1-s1.1.jsonl", fact(2, "2026-11-01T10:00:02Z"))

        LogReader(project, panda).read().texts() shouldBe listOf("$open:1: seq 2 is used again")
    }
    "reading changes no file" {
        val project = throwawayProject()
        val file = project.file("mail/KP/p1-s1.1.jsonl", fact(1, "2026-11-01T10:00:00Z"), "{broken", fact(3, "2026-11-01T10:00:00Z"))
        val before = file.readBytes()

        LogReader(project, panda).read()

        file.readBytes() shouldBe before
    }
    "hand logs are read only on request, each file in its own seq from 1" {
        val project = throwawayProject()
        project.file("mail/KP/_hand/p1-s1.1.jsonl", fact(1, "2026-11-01T10:00:00Z"), fact(2, "2026-11-01T10:00:01Z"))
        val office = project.file("mail/KP/_hand/office-panda-2026-11.jsonl", fact(2, "2026-11-01T10:00:02Z"))

        LogReader(project, panda).read().lines shouldBe emptyList()
        val hand = LogReader(project, panda).readHand(EffortId("KP"))

        hand.lines.map { "${it.file.fileName}:${it.number}" } shouldBe listOf("office-panda-2026-11.jsonl:1", "p1-s1.1.jsonl:1", "p1-s1.1.jsonl:2")
        hand.texts() shouldBe listOf("$office: seq jumps from 0 to 2")
    }
})
