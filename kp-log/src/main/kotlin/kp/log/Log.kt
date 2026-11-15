package kp.log

import kp.model.Envelope
import java.nio.file.Path

/** One line of a log file: where it is and what it holds (a message or a record). */
data class LogLine(val file: Path, val number: Int, val entry: Envelope)

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

data class Repeat(val seq: Long, val file: Path, val line: Int) : Problem {
    override fun toString() = "$file:$line: seq $seq is used again"
}

/** The log would not take a line, or a writer could not start. Nothing was written. */
class LogRefusal(message: String) : RuntimeException(message)
