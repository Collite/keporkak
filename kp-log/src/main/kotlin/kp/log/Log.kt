package kp.log

import kotlinx.serialization.json.JsonObject
import kp.model.EffortId
import kp.model.Envelope
import kp.model.OfficeId
import kp.model.StageId
import java.nio.file.Path

/** One line of a log file: where it is and what it holds (a message or a record). */
data class LogLine(val file: Path, val number: Int, val entry: Envelope)

/** What a read found: the lines in `seq` order, and every problem — reported, never repaired. */
data class LogRead(val lines: List<LogLine>, val problems: List<Problem>)

sealed interface Problem
data class Torn(val file: Path, val line: Int) : Problem {
    override fun toString() = "$file:$line: the last line is torn (no line end)"
}

/** The office's only writer of `mail/` (contracts.md §3). */
class LogWriter private constructor() : AutoCloseable {
    fun append(draft: JsonObject, stage: StageId? = null): Envelope = TODO("T2")
    override fun close() = TODO("T2")
    companion object {
        fun open(project: Path, office: OfficeId): LogWriter = TODO("T2")
    }
}

/** Reads the log back (contracts.md §3); it never writes. */
class LogReader(private val project: Path, private val office: OfficeId) {
    fun read(): LogRead = TODO("T3")
    fun readHand(effort: EffortId): LogRead = TODO("T3")
}
