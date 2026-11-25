package kp.daemon.seats

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kp.model.Envelope
import kp.model.Message
import kp.model.Op
import kp.model.Transition

/**
 * What the log says of the seats (contracts.md §2.2, §4.4): which the state holds live — the seat machine moved them to
 * `ready`, `busy` or `idle`, or a `seat.start` or `seat.reseat` ended `ok` for them, and nothing moved them to `gone` or
 * `retired` since — and, for each seat it started, in the order of their last starts, the role and the stage it was last
 * started for and the last session a start or a re-seat named. Read from the lines as they count (a voided judgement's
 * left out).
 */
internal class SeatLog(lines: List<Envelope>) {
    /**
     * A seat as the log names it: its role and stage from the request that started it, and its last session; [cause] is
     * the message whose judgement requested that start.
     */
    data class Known(val role: String?, val stage: String?, val session: String?, val cause: String? = null)

    val live: Set<String>
    val known: Map<String, Known>

    init {
        val stages = HashMap<String, String?>()
        val alive = LinkedHashSet<String>()
        val seats = LinkedHashMap<String, Known>()
        for (line in lines) {
            when (line) {
                is Message -> stages[line.id.value] = line.stage?.value
                is Transition -> if (line.machine == SEAT) when (line.to) {
                    in LIVE -> alive += line.subject
                    in DOWN -> alive -= line.subject
                }
                is Op -> {
                    val with = line.with?.text(SEAT)
                    val result = line.result?.text(SEAT) ?: with
                    when {
                        // A seat started again is known from its last start, and comes last: the order is the starts' (Amendment 47).
                        line.op == START && line.phase == REQUESTED && with != null ->
                            seats[with] = Known(line.with?.text(ROLE), stages[line.cause], seats.remove(with)?.session, line.cause)
                        line.op in SEATING && line.phase == OK && result != null -> {
                            alive += result
                            seats[result] = (seats[result] ?: Known(null, null, null)).copy(session = line.result?.text(SESSION) ?: seats[result]?.session)
                        }
                        line.op in ENDING && line.phase == OK && result != null -> alive -= result
                    }
                }
                else -> Unit
            }
        }
        live = alive
        known = seats
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val SEAT = "seat"
        const val ROLE = "role"
        const val SESSION = "session"
        const val REQUESTED = "requested"
        const val OK = "ok"
        const val START = "seat.start"
        val SEATING = setOf("seat.start", "seat.reseat")
        val ENDING = setOf("seat.kill", "seat.retire")
        val LIVE = setOf("ready", "busy", "idle")
        val DOWN = setOf("gone", "retired")
    }
}

/**
 * A seat a `session-report` being judged asks to start (contracts.md §4.4, Amendment 47): what the report names — the
 * role — its stage, and the request it answers, whose level a start reads. Before any seat is held, this is the seat.
 */
internal data class Reported(val role: String?, val stage: String?, val request: String?)

/** What [arriving] — the message being judged — names of [seat], when it is a `session-report` that asks to start it; else null. */
internal fun reported(arriving: Message?, seat: String): Reported? = arriving
    ?.takeIf { it.kind == SESSION_REPORT && (it.args?.get(REPORTED_SEAT) as? JsonPrimitive)?.contentOrNull == seat }
    ?.let { Reported((it.args?.get(REPORTED_ROLE) as? JsonPrimitive)?.contentOrNull, it.stage?.value, it.re?.value) }

private const val SESSION_REPORT = "session-report"
private const val REPORTED_SEAT = "seat"
private const val REPORTED_ROLE = "role"
