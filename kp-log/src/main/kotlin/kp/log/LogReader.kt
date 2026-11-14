package kp.log

import kp.model.EffortId
import kp.model.OfficeId
import java.nio.file.Path

/**
 * Reads the log back (contracts.md §3); it opens files for reading only and never repairs one. Every problem — a torn
 * or unparseable line, a gap or a repeat in `seq` — is reported with what a person needs to find it.
 */
class LogReader(private val project: Path, private val office: OfficeId) {

    /**
     * What start-up folds: every open stage file and this office's files from the month of the oldest open stage's
     * first line (with no open stage, the office's current file), merged in `seq` order. A stage file whose last line
     * moves its stage to `exited` or `closed` is skipped; its lines' `seq` still count, so they are not a gap.
     */
    fun read(): LogRead {
        val (closed, open) = Layout.stageFiles(project).map(ParsedFile::of).partition { file -> file.last?.let { Layout.closes(it.entry) } == true }
        val officeFiles = Layout.officeFiles(project, office)
        val since = open.mapNotNull { it.lines.firstOrNull() }.minOfOrNull { Layout.month(it.entry.at.value) }
        val offices = if (since == null) listOfNotNull(officeFiles.lastOrNull()) else officeFiles.filter { Layout.officeFileMonth(it) >= since }
        val files = open + offices.map(ParsedFile::of)
        val lines = files.flatMap { it.lines }.sortedBy { it.seq }
        return LogRead(lines, files.flatMap { it.problems } + sequence(lines, closed.flatMap { it.lines }.map { it.seq }))
    }

    /** The hand logs of [effort] (`mail/<effort>/_hand/`), only when asked: file by file, each `seq` running from 1. */
    fun readHand(effort: EffortId): LogRead {
        val files = Layout.handFiles(project, effort).map(ParsedFile::of)
        return LogRead(files.flatMap { it.lines }, files.flatMap { it.problems + sequence(it.lines.sortedBy { line -> line.seq }, emptyList(), it.file) })
    }

    /** Repeats and gaps among [lines] (sorted by `seq`); [elsewhere] are the `seq` of lines that exist but were skipped. */
    private fun sequence(lines: List<LogLine>, elsewhere: List<Long>, file: Path? = null): List<Problem> {
        if (lines.isEmpty()) return emptyList()
        val skipped = elsewhere.toSet()
        val used = mutableSetOf<Long>()
        val repeats = lines.filter { !used.add(it.seq) || it.seq in skipped }.map { Repeat(it.seq, it.file, it.number) }
        val low = if (file != null) 0L else lines.first().seq
        val known = (used + skipped.filter { it >= low } + listOfNotNull(low.takeIf { file != null })).sorted()
        val gaps = known.zipWithNext().filter { (a, b) -> b > a + 1 }.map { (a, b) -> Gap(a, b, file) }
        return repeats + gaps
    }
}
