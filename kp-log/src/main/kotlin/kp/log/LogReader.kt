package kp.log

import kp.model.EffortId
import kp.model.OfficeId
import java.nio.file.Path

/** Reads the log back (contracts.md §3); it never writes. */
class LogReader(private val project: Path, private val office: OfficeId) {
    fun read(): LogRead = TODO("T3")
    fun readHand(effort: EffortId): LogRead = TODO("T3")
}
