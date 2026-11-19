package kp.mailroom

import kp.engine.Datum
import kp.engine.FactRef
import kp.model.Message
import java.time.Instant

/**
 * The world's side of a judgement (architecture.md §2, `kp-facts`): the facts the engine names, observed. An observer
 * compares nothing with a limit; the rules do.
 */
fun interface FactSource {
    /** [fact]'s value for a judgement about [stage] (null: about no stage); null when it cannot be supplied — it is then unknown. */
    fun observe(fact: FactRef, stage: String?): Datum?
}

/** The operations the rules request (architecture.md §2, `kp-ops`). A performer retries nothing by itself (§7). */
fun interface Operations {
    /** Performs [request] and reports its result through [done] — now or later, once. */
    fun perform(request: OperationRequest, done: (OperationResult) -> Unit)
}

/** One operation, for the message whose judgement requested it ([cause]), with its resolved `with:`. */
data class OperationRequest(val operation: String, val cause: String, val with: Datum.Fields)

sealed interface OperationResult {
    /** `ok`, with what the operation gives back (`ok(seat)`, `ok(sha)` — contracts.md §4.4). */
    data class Ok(val result: Datum.Fields = Datum.Fields(emptyMap())) : OperationResult
    data class Failed(val reason: String) : OperationResult
}

/**
 * How a delivered message reaches a seat (architecture.md §2, `kp-driver`; contracts.md §4.4, Amendment 22): an idle
 * seat — a live session, no turn in flight — is prompted with it, a wake the mailroom records as the operation
 * `seat.wake`.
 */
interface Deliveries {
    /** Whether [seat] is idle: a message to it would be its next prompt. A busy seat finds it in its inbox. */
    fun idle(seat: String): Boolean

    /** Prompts [seat] with [message]; false when it could not — the message then waits in its inbox. */
    fun wake(seat: String, message: Message): Boolean

    /** The wake's result: `ok` once [seat] is prompted with [message], else `failed` with the reason. */
    fun prompt(seat: String, message: Message): OperationResult =
        if (wake(seat, message)) OperationResult.Ok() else OperationResult.Failed("$seat could not be prompted")

    /** The seats with a live session when the mailroom starts: each that is idle with unread mail is woken with the oldest. */
    fun live(): Set<String> = emptySet()
}

/** The time, in UTC — the only clock the mailroom reads (architecture.md §3: deadlines are timers on it). */
fun interface Clock {
    fun now(): Instant
}

/**
 * The texts of the rule set's files by their path in the repository — `rules/…`, `robots/…`, `pool/…`,
 * `agents/<role>/AGENT.md` — as `kp-rules` takes them. Reading the files is the daemon's.
 */
fun interface RuleTexts {
    fun read(): Map<String, String>
}
