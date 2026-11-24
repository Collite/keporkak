package kp.driver.acp

import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.serialization.json.JsonPrimitive
import kp.driver.ObservedFact
import kp.driver.Outcome
import kp.driver.SeatFacts
import kp.driver.Usage
import kp.driver.UsageWeights
import kp.driver.UsageWindow
import kp.model.SeatId
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What the driver has observed of one seat's session, and the facts it answers from it (T6), named as
 * `rules/vocabulary.yaml` names them. Durations are computed at the moment they are asked for, from the injected
 * clock. Nothing here is compared with a limit: values are reported as they are, for the rules to judge.
 *
 * - `seat.silent_for`: while a turn is in flight, the time since the agent last sent anything (or since the prompt,
 *   if later); zero when no turn is in flight.
 * - `seat.tool_call_open_for`: the time since the oldest tool call still open was opened; zero when none is open. A
 *   tool call is open from `pending` or `in_progress` until `completed` or `failed`; a turn that ends `cancelled`
 *   and a process that ends close every one (the client marks them cancelled — the protocol's own rule).
 * - `seat.context_used`: the fraction of the context window the agent last reported in use.
 * - `seat.usage`: tokens, each turn counted by its four parts weighted (contracts.md §4.4, Amendment 44) with the weights
 *   in force when it ended — `turn`, the count of the last turn that ended; `session`, the sum over this session's turns.
 *   A part the agent did not report counts zero, and the evidence says so.
 */
internal class Observations(private val seat: SeatId, private val clock: Clock, private val weights: UsageWeights.Source = UsageWeights.Source { null }) {
    private var pid: Long? = null
    private var gone: Pair<Int, Instant>? = null
    private var closedAt: Instant? = null
    private var turnSince: Instant? = null
    private var lastHeard: Instant? = null
    private val open = LinkedHashMap<String, Instant>()
    private var context: Triple<Long, Long, Instant>? = null
    private var lastTurn: Counted? = null
    private var sessionTokens = 0L
    private var turnsEnded = 0
    private var turnsWithUsage = 0
    private var turnsUncounted = 0

    /** A turn that ended: its usage as the agent reported it, its count — null when no weights were in force — and when. */
    private class Counted(val usage: Usage?, val count: Long?, val weights: UsageWeights?, val at: Instant)

    @Synchronized fun started(pid: Long) { this.pid = pid }

    @Synchronized fun heard(at: Instant) { lastHeard = at }

    @Synchronized fun turnStarted(at: Instant) { turnSince = at }

    @Synchronized fun turnEnded(stopReason: String?, usage: Usage?, at: Instant) {
        turnSince = null
        if (stopReason == null) return
        if (stopReason == CANCELLED) open.clear()
        turnsEnded++
        val weights = weights.weights()
        val count = weights?.count(usage)
        lastTurn = Counted(usage, count, weights, at)
        if (usage != null) turnsWithUsage++
        if (count != null) sessionTokens += count else turnsUncounted++
    }

    @Synchronized fun update(update: SessionUpdate, at: Instant) {
        when (update) {
            is SessionUpdate.ToolCall -> tool(update.toolCallId.value, update.status ?: ToolCallStatus.PENDING, at)
            is SessionUpdate.ToolCallUpdate -> update.status?.let { tool(update.toolCallId.value, it, at) }
            is SessionUpdate.UsageUpdate -> context = Triple(update.used, update.size, at)
            else -> {}
        }
    }

    private fun tool(id: String, status: ToolCallStatus, at: Instant) {
        when (status) {
            ToolCallStatus.PENDING, ToolCallStatus.IN_PROGRESS -> open.putIfAbsent(id, at)
            ToolCallStatus.COMPLETED, ToolCallStatus.FAILED -> open.remove(id)
        }
    }

    /** The process ended: the fact, the first time only. */
    @Synchronized fun gone(exitCode: Int, at: Instant): ObservedFact? {
        if (gone != null) return null
        gone = exitCode to at
        turnSince = null
        open.clear()
        return processGone()
    }

    /** The protocol closed: the fact, the first time only. */
    @Synchronized fun closed(at: Instant): ObservedFact? {
        if (closedAt != null) return null
        closedAt = at
        return protocolClosed()
    }

    @Synchronized fun observe(fact: String, window: UsageWindow?): Outcome<ObservedFact> = when (fact) {
        SeatFacts.PROCESS_GONE -> Outcome.Ok(processGone())
        SeatFacts.PROTOCOL_CLOSED -> Outcome.Ok(protocolClosed())
        SeatFacts.SILENT_FOR -> Outcome.Ok(silentFor())
        SeatFacts.TOOL_CALL_OPEN_FOR -> Outcome.Ok(toolCallOpenFor())
        SeatFacts.CONTEXT_USED -> contextUsed()
        SeatFacts.USAGE -> when (window) {
            null -> Outcome.Failed("${SeatFacts.USAGE} needs a window: ${UsageWindow.entries.joinToString(" or ") { it.word }}")
            UsageWindow.TURN -> turnUsage()
            UsageWindow.SESSION -> Outcome.Ok(fact(SeatFacts.USAGE, JsonPrimitive(sessionTokens), "$turnsEnded turns ended in this session, $turnsWithUsage with usage reported, " +
                "each counted by its parts weighted" + (turnsUncounted.takeUnless { it == 0 }?.let { "; $it not counted: no weights were in force" } ?: "")))
        }
        else -> Outcome.Failed("the driver observes no fact named $fact")
    }

    private fun processGone() = gone?.let { (code, at) -> fact(SeatFacts.PROCESS_GONE, JsonPrimitive(true), "the agent's process ended with exit code $code at $at") }
        ?: fact(SeatFacts.PROCESS_GONE, JsonPrimitive(false), "the agent's process ${pid ?: ""} is running".replace("  ", " "))

    private fun protocolClosed() = closedAt?.let { fact(SeatFacts.PROTOCOL_CLOSED, JsonPrimitive(true), "the protocol closed at $it") }
        ?: fact(SeatFacts.PROTOCOL_CLOSED, JsonPrimitive(false), "the protocol is open")

    private fun silentFor(): ObservedFact {
        val since = turnSince ?: return fact(SeatFacts.SILENT_FOR, duration(Duration.ZERO), "no turn is in flight")
        val heard = listOfNotNull(since, lastHeard).max()
        return fact(SeatFacts.SILENT_FOR, duration(Duration.between(heard, clock.instant())), "a turn is in flight since $since; the agent last sent something at ${lastHeard ?: "no time yet"}")
    }

    private fun toolCallOpenFor(): ObservedFact {
        val (id, since) = open.entries.minByOrNull { it.value }?.toPair() ?: return fact(SeatFacts.TOOL_CALL_OPEN_FOR, duration(Duration.ZERO), "no tool call is open")
        return fact(SeatFacts.TOOL_CALL_OPEN_FOR, duration(Duration.between(since, clock.instant())), "tool call $id is open since $since (${open.size} open)")
    }

    private fun contextUsed(): Outcome<ObservedFact> {
        val (used, size, at) = context ?: return Outcome.Failed("the agent has reported no context usage yet")
        if (size <= 0) return Outcome.Failed("the agent reported a context size of $size")
        return Outcome.Ok(fact(SeatFacts.CONTEXT_USED, JsonPrimitive(used.toDouble() / size), "$used of $size at $at"))
    }

    private fun turnUsage(): Outcome<ObservedFact> {
        val turn = lastTurn ?: return Outcome.Failed("no turn has ended in this session")
        val count = turn.count ?: return Outcome.Failed("the last turn, ended at ${turn.at}, is not counted: no weights were in force")
        return Outcome.Ok(fact(SeatFacts.USAGE, JsonPrimitive(count), "the last turn ended at ${turn.at}: ${UsageWeights.parts(turn.usage)}; weighted $count by ${turn.weights}"))
    }

    private fun fact(name: String, value: JsonPrimitive, evidence: String) = ObservedFact(name, seat, value, evidence)

    companion object {
        private const val CANCELLED = "cancelled"

        /** A duration in the rules' own form: seconds, e.g. `0s`, `12.5s` (kp-rules reads `15m`, `4h`, `30s`). */
        fun duration(value: Duration): JsonPrimitive = JsonPrimitive(BigDecimal.valueOf(value.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString() + "s")
    }
}
