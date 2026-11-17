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
     * What start-up folds (contracts.md §3, Amendment 15): every stage file, closed ones too, and every one of this
     * office's files, merged in `seq` order — closing a file takes nothing out of the state.
     */
    fun read(): LogRead {
        val files = (Layout.stageFiles(project) + Layout.officeFiles(project, office)).map(ParsedFile::of)
        val lines = files.flatMap { it.lines }.sortedBy { it.seq }
        return LogRead(lines, files.flatMap { it.problems } + sequence(lines))
    }

    /** The hand logs of [effort] (`mail/<effort>/_hand/`), only when asked: file by file, each `seq` running from 1. */
    fun readHand(effort: EffortId): LogRead {
        val files = Layout.handFiles(project, effort).map(ParsedFile::of)
        return LogRead(files.flatMap { it.lines }, files.flatMap { it.problems + sequence(it.lines.sortedBy { line -> line.seq }, it.file) })
    }

    /** Repeats and gaps among [lines] (sorted by `seq`); with [file], a hand log's, whose `seq` runs from 1. */
    private fun sequence(lines: List<LogLine>, file: Path? = null): List<Problem> {
        if (lines.isEmpty()) return emptyList()
        val used = mutableSetOf<Long>()
        val repeats = lines.filter { !used.add(it.seq) }.map { Repeat(it.seq, it.file, it.number) }
        val low = if (file != null) 0L else lines.first().seq
        val known = (used + listOfNotNull(low.takeIf { file != null })).sorted()
        val gaps = known.zipWithNext().filter { (a, b) -> b > a + 1 }.map { (a, b) -> Gap(a, b, file) }
        return repeats + gaps
    }
}
