package kp.daemon.hero

import kotlinx.serialization.json.JsonPrimitive
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Fact
import kp.model.Held
import kp.model.Message
import kp.model.Op
import kp.model.Refused
import kp.model.Transition

/**
 * The log as a reader is told it (D-38): one line per thing that happened — a message and what became of it, an
 * operation's result, a fact that triggered something, a deadline that passed — with the moves it caused and the
 * operations it asked for. A stage is named as the walk names it. A seat woken with a message it was sent is told by
 * its delivery's "woke" (S2.4: a wake is an operation of its own): its wake's request and `ok` are not told again — a
 * wake that fails is.
 */
class Story(private val hero: Hero) {
    private val lines: List<Envelope> = hero.office.lines().map { it.entry }
    private val messages: Map<String, Message> = lines.filterIsInstance<Message>().associateBy { it.id.value }
    private val names: Map<String, String> = hero.script.stages.entries.associate { (name, id) -> id to name }
    private val deliveries: Map<String, Delivered> = lines.filterIsInstance<Delivered>().associateBy { it.id }

    /** What the lines with `seq` in [first]..[last] tell. */
    fun told(first: Long, last: Long): List<String> {
        val told = ArrayList<StringBuilder>()
        lines.filter { it.seq.toLong() in first..last }.filterNot(::aSeatsWake).forEach { line ->
            when (line) {
                is Message -> told += StringBuilder(head(line))
                is Delivered -> told.after(line.id, "delivered" + listOfNotNull(line.deliveryClass.takeIf { it != "A" }?.let { "class $it" }, line.ticket?.let { "ticket #$it" },
                    line.woke?.takeIf { it.isNotEmpty() }?.let { "woke ${it.joinToString()}" }).joinToString("") { ", $it" })
                is Held -> told.after(line.id, "held by ${line.by}" + (line.ticket?.let { ", ticket #$it" } ?: ""))
                is Refused -> told.after(line.id, "refused by ${line.rule}: " + (line.failed?.toString() ?: line.requirement))
                is Expired -> told += StringBuilder("${messages[line.id]?.kind ?: line.id}'s deadline passed" + (line.report?.let { ", reported to $it" } ?: ""))
                is Transition -> told.lastOrNull()?.append(" · ${moved(line)}") ?: told.add(StringBuilder(moved(line)))
                is Op -> when (line.phase) {
                    "requested" -> told.lastOrNull()?.append(" · asks ${line.op}" + mode(line)) ?: told.add(StringBuilder("asks ${line.op}"))
                    else -> told += StringBuilder("${line.op} ${line.phase}" + (line.reason?.let { " ($it)" } ?: ""))
                }
                is Fact -> told += StringBuilder("fact ${line.fact} (${line.subject})")
                else -> Unit
            }
        }
        return told.map { it.toString() }
    }

    /** A message: its kind, sender and addressee — the robot or rule that sent it, and whether Bora signed it. */
    private fun head(message: Message): String {
        val by = message.by.takeIf { it.startsWith("robot:") || it.startsWith("effect:") || it.startsWith("expiry:") || it == "mailroom" }
        val to = deliveries[message.id.value]?.to?.joinToString(", ") ?: message.to.value
        return "${message.kind} ${message.from.value}→$to" + (by?.let { " ($it)" } ?: "") + (if (message.signed) " signed" else "")
    }

    /** A record about a message: after the message's own line when it is the last told, else as a line of its own (a message let go later). */
    private fun ArrayList<StringBuilder>.after(id: String, what: String) {
        val last = lastOrNull()
        val message = messages[id]
        if (last != null && message != null && last.startsWith(head(message)) && ":" !in last.substring(head(message).length)) last.append(": $what")
        else add(StringBuilder("${message?.let(::head) ?: id} let go: $what"))
    }

    private fun moved(line: Transition): String = when (line.machine) {
        "stage" -> "${names[line.subject] ?: line.subject} ${line.from}→${line.to}"
        "presence" -> "presence ${line.from}→${line.to}"
        "flag" -> "the ${messages[line.subject]?.kind ?: line.subject}'s thread ${line.from}→${line.to}"
        else -> "${line.machine} ${line.subject.let { subject -> names.entries.fold(subject) { text, (id, name) -> text.replace(id, name) } }} ${line.from}→${line.to}"
    } + (line.counted?.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ", " (", ")") { (name, value) -> "$name $value" } ?: "")

    private fun mode(op: Op): String = (op.with?.get("mode") as? JsonPrimitive)?.content?.let { " ($it)" } ?: ""

    /**
     * A seat's wake with a message, asked or done: told by the delivery's "woke" (S2.4). A result is paired with its
     * request as the fold pairs them — the first in flight of its operation and cause.
     */
    private val seatsWakes: Set<Long> = HashSet<Long>().also { hidden ->
        val inFlight = HashMap<String, ArrayDeque<Boolean>>()
        lines.filterIsInstance<Op>().filter { it.op == SEAT_WAKE }.forEach { op ->
            if (op.phase == "requested") {
                val seats = op.with?.containsKey("seat") == true
                inFlight.getOrPut(op.cause, ::ArrayDeque).addLast(seats)
                if (seats) hidden += op.seq.toLong()
            } else if (inFlight[op.cause]?.removeFirstOrNull() == true && op.phase == "ok") hidden += op.seq.toLong()
        }
    }

    private fun aSeatsWake(line: Envelope): Boolean = line.seq.toLong() in seatsWakes

    private companion object {
        const val SEAT_WAKE = "seat.wake"
    }
}
