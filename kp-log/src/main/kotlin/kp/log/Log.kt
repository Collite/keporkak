package kp.log

import kp.model.Envelope
import kp.model.Voided
import java.nio.file.Path

/** One line of a log file: where it is and what it holds (a message or a record). */
data class LogLine(val file: Path, val number: Int, val entry: Envelope) {
    /** The stage whose file holds the line (`mail/<effort>/p<n>-s<n.m>.jsonl`, contracts.md §3); null for the office's file or a hand log. */
    val stage: String? get() = Layout.stageOfOrNull(file)
}

/**
 * The lines that count: all but those a `voided` record names after its judgement's first — they count for nothing,
 * and the judgement the record opens carries that first line again (contracts.md §2.2, §3, Amendment 23).
 */
fun List<LogLine>.counted(): List<LogLine> {
    val voided = mapNotNull { it.entry as? Voided }.map { (it.first + 1)..it.last }
    return if (voided.isEmpty()) this else filterNot { line -> voided.any { line.entry.seq.toLong() in it } }
}

/** What a read found: the lines in `seq` order, and every problem — reported, never repaired. */
data class LogRead(val lines: List<LogLine>, val problems: List<Problem>)

/** Something wrong in the log. Its text names the file and line a person opens. */
sealed interface Problem

data class Torn(val file: Path, val line: Int) : Problem {
    override fun toString() = "$file:$line: the last line is torn (no line end)"
}

data class Unparseable(val file: Path, val line: Int, val reason: String) : Problem {
    override fun toString() = "$file:$line: not a message or record of contracts.md §2 ($reason)"
}

/** Lines `after + 1 … next - 1` are in no file read. [file] is set for a hand log, whose `seq` runs per file. */
data class Gap(val after: Long, val next: Long, val file: Path? = null) : Problem {
    override fun toString() = (file?.let { "$it: " } ?: "") + "seq jumps from $after to $next"
}

/** A line whose `seq` is below that of the line before it in the same file: lines were swapped (issue #10). */
data class Backwards(val file: Path, val line: Int, val seq: Long, val after: Long) : Problem {
    override fun toString() = "$file:$line: seq $seq comes after $after — it goes backwards"
}

data class Repeat(val seq: Long, val file: Path, val line: Int) : Problem {
    override fun toString() = "$file:$line: seq $seq is used again"
}

/** The log would not take a line, or a writer could not start. Nothing was written. */
class LogRefusal(message: String) : RuntimeException(message)
