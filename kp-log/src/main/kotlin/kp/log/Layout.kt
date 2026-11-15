package kp.log

import kp.model.EffortId
import kp.model.Envelope
import kp.model.OfficeId
import kp.model.StageId
import kp.model.Transition
import kp.model.parseLine
import kp.model.utc
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Where contracts.md §3 puts each line, and how a file is taken apart into lines. */
internal object Layout {
    private val stageId = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\\.[0-9]+)$")
    private val effortId = Regex("^[A-Z][A-Z0-9-]*$")
    private val stageFileName = Regex("^p[0-9]+-s[0-9]+\\.[0-9]+\\.jsonl$")
    private val stageFileParts = Regex("^p([0-9]+)-s([0-9]+\\.[0-9]+)\\.jsonl$")

    fun mail(project: Path): Path = project.resolve("mail")

    /** `mail/<effort>/p<n>-s<n.m>.jsonl` */
    fun stageFile(project: Path, stage: StageId): Path {
        val (effort, phase, number) = stageId.matchEntire(stage.value)!!.destructured
        return mail(project).resolve(effort).resolve("p$phase-s$number.jsonl")
    }

    /** `mail/_office/<office>-<yyyy>-<mm>.jsonl`, for the month of [at] in UTC. */
    fun officeFile(project: Path, office: OfficeId, at: String): Path =
        mail(project).resolve("_office").resolve("${office.value}-${month(at)}.jsonl")

    fun month(at: String): String = utc(at).let { "%04d-%02d".format(it.year, it.monthValue) }

    fun stageFiles(project: Path): List<Path> = mail(project).children { it.isDirectory() && effortId.matches(it.name) }
        .flatMap { effort -> effort.children { stageFileName.matches(it.name) } }

    /** This office's files, oldest month first. */
    fun officeFiles(project: Path, office: OfficeId): List<Path> {
        val name = Regex("^${Regex.escape(office.value)}-([0-9]{4}-[0-9]{2})\\.jsonl$")
        return mail(project).resolve("_office").children { name.matches(it.name) }
    }

    fun officeFileMonth(file: Path): String = file.name.removeSuffix(".jsonl").takeLast(7)

    fun handFiles(project: Path, effort: EffortId): List<Path> =
        mail(project).resolve(effort.value).resolve("_hand").children { it.name.endsWith(".jsonl") }

    /**
     * A stage file ends with its own stage's `exited` or `closed` and then takes no more lines (§3, Rotation). A stage
     * transition whose subject is another stage closes nothing (issue #10).
     */
    fun closes(entry: Envelope, stage: String): Boolean =
        entry is Transition && entry.machine == "stage" && entry.subject == stage && entry.to in setOf("exited", "closed")

    /** The stage whose file [file] is: `mail/<effort>/p<n>-s<n.m>.jsonl` is `<effort>/P<n>/S<n.m>`. */
    fun stageOf(file: Path): String {
        val (phase, number) = stageFileParts.matchEntire(file.name)!!.destructured
        return "${file.parent.name}/P$phase/S$number"
    }

    private fun Path.children(keep: (Path) -> Boolean): List<Path> =
        if (!isDirectory()) emptyList() else listDirectoryEntries().filter(keep).sortedBy { it.name }
}

/** A file taken apart: every line that reads as a message or record, and what does not. */
internal class ParsedFile(val file: Path, val lines: List<LogLine>, val problems: List<Problem>, val count: Int) {
    val last: LogLine? get() = lines.lastOrNull()?.takeIf { it.number == count }

    companion object {
        fun of(file: Path): ParsedFile {
            val bytes = Files.readAllBytes(file)
            val lines = mutableListOf<LogLine>()
            val problems = mutableListOf<Problem>()
            var start = 0
            var number = 0
            while (start < bytes.size) {
                number++
                val end = bytes.indexOf('\n'.code.toByte(), start)
                if (end < 0) {
                    problems += Torn(file, number)
                    break
                }
                try {
                    val entry = parseLine(utf8(bytes, start, end))
                    entry.seq.longValueExact()
                    lines += LogLine(file, number, entry)
                } catch (error: Exception) {
                    problems += Unparseable(file, number, error.message ?: error.javaClass.simpleName)
                }
                start = end + 1
            }
            return ParsedFile(file, lines, problems + backwards(lines), number)
        }

        /** The writer appends in `seq` order, so in one file each line's `seq` is above the one before it (§2.2). */
        private fun backwards(lines: List<LogLine>): List<Problem> =
            lines.zipWithNext().filter { (before, line) -> line.seq < before.seq }.map { (before, line) -> Backwards(line.file, line.number, line.seq, before.seq) }

        private fun ByteArray.indexOf(byte: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == byte } ?: -1

        private fun utf8(bytes: ByteArray, from: Int, to: Int): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
    }
}

internal val LogLine.seq: Long get() = entry.seq.longValueExact()
