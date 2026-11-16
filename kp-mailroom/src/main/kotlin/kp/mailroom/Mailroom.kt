package kp.mailroom

import kotlinx.serialization.json.JsonObject
import kp.engine.Datum
import kp.engine.State
import kp.model.Message
import kp.model.OfficeId
import kp.rules.Problem
import java.nio.file.Path
import java.time.Instant

/** Who a message is submitted in the name of: the mailroom sets `from`, `by` and `signed` from it, never from the input. */
sealed interface Sender {
    /** A seat, as its session token names it (contracts.md §5). */
    data class Seat(val name: String) : Sender
    /** Bora's command line; [signed] when it came over a root peer (contracts.md §6) — handed in by the caller. */
    data class Bora(val signed: Boolean) : Sender
}

/** The status of a submitted message (contracts.md §5). */
enum class Status { DELIVERED, HELD, REFUSED }

/** What the sender is answered once the message's lines are on disk (contracts.md §5's result). */
data class Answer(
    val status: Status,
    val id: String,
    val transition: String? = null,
    val rule: String? = null,
    val requirement: String? = null,
    val failed: Datum? = null,
    val refusalsToday: Long? = null,
)

/** A reload's outcome: the new set is in force from the next message, or nothing changed and these are the problems. */
sealed interface Reload {
    data class Accepted(val hash: String) : Reload
    data class Rejected(val problems: List<Problem>) : Reload
}

/** What a seat's inbox returns (contracts.md §5): its messages, and whether more are left. */
data class Inbox(val messages: List<Message>, val more: Boolean)

/** The mailroom would not start: the log or the rules are not fit to work from. Nothing was written. */
class MailroomRefusal(message: String) : RuntimeException(message)

/**
 * The loop (architecture.md §3): one message from receipt to the log and on to its addressees. It collects the facts
 * the engine names, asks the engine, writes the lines and only then acts — delivers, performs, answers. It decides
 * nothing; its state is a cache of the log.
 */
class Mailroom private constructor() : AutoCloseable {
    /** The fold of the log as the mailroom works from it. */
    val state: State get() = TODO()

    /** Carries one message submitted in [sender]'s name; [input] holds what a sender supplies (contracts.md §2.1, §5). */
    fun submit(sender: Sender, input: JsonObject): Answer = TODO()

    /** A seat's inbox (contracts.md §5): returning a message marks it read for the seat. */
    fun inbox(seat: String, unread: Boolean = true, limit: Int? = null): Inbox = TODO()

    /** Fires every deadline the clock has passed. */
    fun tick(): Unit = TODO()

    /** The next deadline of an open wait, for the process to set its timer. */
    fun nextDeadline(): Instant? = TODO()

    /** Reads the rule texts again; a valid set is in force from the next message, an invalid one changes nothing. */
    fun reload(): Reload = TODO()

    override fun close(): Unit = TODO()

    companion object {
        /** Opens the log, folds it, puts the rules in force and records what start-up records (contracts.md §2.2, §4.4). */
        fun start(project: Path, office: OfficeId, rules: RuleTexts, facts: FactSource, operations: Operations, deliveries: Deliveries, clock: Clock): Mailroom =
            TODO()
    }
}
