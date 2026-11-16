package kp.driver.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.AcpExpectedError
import com.agentclientprotocol.protocol.JsonRpcException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonNull
import kp.driver.Answer
import kp.driver.Answerer
import kp.driver.Cancel
import kp.driver.Explained
import kp.driver.OfferedOption
import kp.driver.PermissionRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kp.driver.Listener
import kp.driver.ObservedFact
import kp.driver.Offers
import kp.driver.Outcome
import kp.driver.SessionSpec
import kp.driver.SessionToken
import kp.driver.TurnEnd
import kp.driver.Usage
import kp.model.SeatId
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * One seat's session in one agent process: the token minted for it, the connection, what the agent offers, and the
 * seat's one collector. During a turn the SDK delivers updates only on the prompt's stream, and between turns only
 * through [operations] (spike `06`, SDK note), so the order between the two is lost there. The collector therefore
 * takes the session's updates and the prompt's answer from the [wire], in the order they arrived, together with the
 * driver's own lines, through one channel; one coroutine writes them. The SDK's stream only drives the turn. When the
 * session ends — its process gone or its protocol closed — its token is reported dead, once.
 */
internal class SeatSession(
    val seat: SeatId,
    val spec: SessionSpec,
    val token: SessionToken,
    val transcript: Transcript,
    private val clock: Clock,
    private val listener: Listener,
    private val answerer: Answerer,
    private val scope: CoroutineScope,
) {
    lateinit var launched: Launched
    lateinit var session: ClientSession
    @Volatile var handedOut = false
    private val ended = AtomicBoolean(false)
    private val turnInFlight = AtomicBoolean(false)
    private val events = Channel<Item>(Channel.UNLIMITED)
    @Volatile private var turn: Turn? = null
    @Volatile private var loading: JsonElement? = null
    @Volatile private var replaying = false
    val observations = Observations(seat, clock)
    private val inputs = ConcurrentHashMap<String, JsonElement>()
    private val names = ConcurrentHashMap<String, String>()
    private val asking = ConcurrentHashMap.newKeySet<Deferred<Explained>>()

    val live: Boolean get() = !ended.get()

    init {
        scope.launch { for (item in events) take(item) }
    }

    /** What the agent asks of the client: permission requests (T5). Updates are taken from the [wire]. */
    val operations = object : ClientSessionOperations {
        override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?) =
            permission(toolCall, permissions)

        // The wire tap has taken it already, in order.
        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {}
    }

    /**
     * One permission request, handed whole — the tool, its input, the options offered — to the caller's answerer.
     * Its input is the request's own, or else the one the tool call carried on the wire; the tool's name is the one
     * the agent gave on the wire, beside the protocol's fields (S2.2). A request still waiting for its answer when
     * the turn is cancelled is answered `cancelled`. The answer's reason, if the answerer gives one, goes to the
     * transcript with it: a denial by a compiled rule names its border.
     */
    private suspend fun permission(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>): RequestPermissionResponse {
        val id = toolCall.toolCallId.value
        val input = toolCall.rawInput?.takeUnless { it == JsonNull } ?: inputs[id]
        val offered = permissions.map { OfferedOption(it.optionId.value, it.name, Lines.wire(it.kind, PermissionOptionKind.serializer())) }
        val request = PermissionRequest(seat, id, toolCall.title ?: "", toolCall.kind?.let { Lines.wire(it, ToolKind.serializer()) }, input, offered, names[id])
        val asked = scope.async { answerer.explained(request) }
        asking += asked
        val answer = try {
            asked.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            note("permission $id: the answerer failed: ${e.message ?: e.javaClass.simpleName}")
            Explained(Answer.DENY)
        } finally {
            asking -= asked
        }
        val asks = "permission $id: ${request.tool}${input?.let { " $it" } ?: ""}"
        if (answer == null) {
            note("$asks → cancelled: the turn was cancelled")
            return RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
        }
        val choice = Permissions.choose(answer.answer, offered)
        note("$asks → ${choice.words}${answer.because?.let { " ($it)" } ?: ""}")
        return RequestPermissionResponse(choice.option?.let { RequestPermissionOutcome.Selected(PermissionOptionId(it.id)) } ?: RequestPermissionOutcome.Cancelled)
    }

    /** The tap: what the collector takes from the wire, and the prompt's request id. */
    val wire = object : Wire {
        override fun incoming(line: String) {
            observations.heard(clock.instant())
            runCatching {
                val message = ACPJson.parseToJsonElement(line).jsonObject
                val method = (message["method"] as? JsonPrimitive)?.contentOrNull
                val id = message["id"]
                when {
                    method == "session/update" -> {
                        val update = message.getValue("params").jsonObject.getValue("update").jsonObject
                        val input = update["rawInput"]?.takeUnless { it == JsonNull }
                        val toolCallId = (update["toolCallId"] as? JsonPrimitive)?.contentOrNull
                        if (input != null && toolCallId != null) inputs[toolCallId] = input
                        if (toolCallId != null) toolName(update)?.let { names[toolCallId] = it }
                        events.trySend(Item.Update(update, clock.instant(), replay = replaying))
                    }
                    method == "session/request_permission" -> {
                        val toolCall = message.getValue("params").jsonObject.getValue("toolCall").jsonObject
                        val toolCallId = (toolCall["toolCallId"] as? JsonPrimitive)?.contentOrNull
                        if (toolCallId != null) toolName(toolCall)?.let { names[toolCallId] = it }
                    }
                    method == null && id != null && id == loading -> {
                        replaying = false
                        loading = null
                    }
                    method == null && id != null && id == turn?.requestId -> {
                        turn?.answered?.set(true)
                        events.trySend(Item.Answer(message, clock.instant()))
                    }
                }
            }
        }

        override fun outgoing(line: String) {
            runCatching {
                val message = ACPJson.parseToJsonElement(line).jsonObject
                when ((message["method"] as? JsonPrimitive)?.contentOrNull) {
                    "session/prompt" -> turn?.requestId = message["id"]
                    "session/load" -> {
                        loading = message["id"]
                        replaying = true
                    }
                }
            }
        }
    }

    /** The tool's name as the agent gives it beside the protocol's fields: `name`, or `_meta.claudeCode.toolName` (S2.2 probe). */
    private fun toolName(toolCall: JsonObject): String? =
        (toolCall["name"] as? JsonPrimitive)?.contentOrNull
            ?: (((toolCall["_meta"] as? JsonObject)?.get("claudeCode") as? JsonObject)?.get("toolName") as? JsonPrimitive)?.contentOrNull

    /** One turn (T4). A prompt while a turn is in flight fails; it is never queued. */
    suspend fun prompt(text: String): Outcome<TurnEnd> {
        if (!live) return Outcome.Failed("the session of seat ${seat.value} has ended")
        if (!turnInFlight.compareAndSet(false, true)) return Outcome.Failed("a turn is in flight for seat ${seat.value}; the prompt is not queued")
        val current = Turn().also { turn = it }
        try {
            note("prompt: $text")
            observations.turnStarted(clock.instant())
            try {
                session.prompt(listOf(ContentBlock.Text(text))).collect { }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (!current.answered.get()) return turnFailed("the protocol closed during the turn")
            } catch (e: Exception) {
                if (!current.answered.get()) return turnFailed("the turn failed: ${e.message ?: e.javaClass.simpleName}")
            }
            return current.ended.await()
        } finally {
            turn = null
            turnInFlight.set(false)
        }
    }

    /**
     * Asks the agent to end the turn in flight; the turn then ends with the stop reason the agent gives. With no turn
     * in flight there is nothing to cancel: nothing is sent, and the result says so (§4.4).
     */
    suspend fun cancel(): Outcome<Cancel> {
        if (!turnInFlight.get()) {
            note("cancel: no turn in flight, nothing sent")
            return Outcome.Ok(Cancel.NOTHING_IN_FLIGHT)
        }
        note("cancel sent")
        asking.forEach { it.cancel() }
        return try {
            session.cancel()
            Outcome.Ok(Cancel.SENT)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            Outcome.Failed("the cancel could not be sent: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun turnFailed(reason: String): Outcome.Failed {
        observations.turnEnded(null, null, clock.instant())
        note("turn failed: $reason")
        return Outcome.Failed(transcript.hidden(reason))
    }

    /** The prompt's answer as it came: its stop reason and usage, or the agent's error. */
    private fun answer(message: JsonObject): Outcome<TurnEnd> {
        val error = message["error"] as? JsonObject
        if (error != null) return Outcome.Failed("the agent answered the prompt with an error: ${(error["message"] as? JsonPrimitive)?.contentOrNull ?: error}")
        return Outcome.Ok(turnEnd(ACPJson.decodeFromJsonElement(PromptResponse.serializer(), message.getValue("result"))))
    }

    @OptIn(UnstableApi::class)
    private fun turnEnd(response: PromptResponse) = TurnEnd(
        stopReason = Lines.wire(response.stopReason, StopReason.serializer()),
        usage = response.usage?.let { Usage(it.inputTokens, it.outputTokens, it.totalTokens, it.thoughtTokens, it.cachedReadTokens, it.cachedWriteTokens) },
    )

    /** A line of the driver's own, in order with the agent's; returns when it is written. */
    suspend fun note(text: String) {
        val written = CompletableDeferred<Unit>()
        events.send(Item.Line(text, clock.instant(), written))
        written.await()
    }

    /** The collector's step: one item, in the order it was sent. */
    private fun take(item: Item) {
        try {
            when (item) {
                is Item.Update -> {
                    val update = runCatching { ACPJson.decodeFromJsonElement(SessionUpdate.serializer(), item.update) }.getOrNull()
                    if (!item.replay) update?.let { observations.update(it, item.at) }
                    val line = update?.let(Lines::of) ?: "update not understood: ${item.update}"
                    transcript.write(if (item.replay) "replay $line" else line, item.at)
                }
                is Item.Line -> transcript.write(item.text, item.at)
                is Item.Answer -> {
                    val end = runCatching { answer(item.message) }.getOrElse { Outcome.Failed("the prompt's answer is not understood: ${it.message}") }
                    observations.turnEnded((end as? Outcome.Ok)?.value?.stopReason, (end as? Outcome.Ok)?.value?.usage, item.at)
                    transcript.write(
                        when (end) {
                            is Outcome.Ok -> Lines.turnEnded(end.value.stopReason, end.value.usage)
                            is Outcome.Failed -> "turn failed: ${end.reason}"
                        },
                        item.at,
                    )
                    turn?.ended?.complete(if (end is Outcome.Failed) Outcome.Failed(transcript.hidden(end.reason)) else end)
                }
            }
        } finally {
            item.written?.complete(Unit)
        }
    }

    /** The protocol closed: the fact is reported at once, and the session ends. */
    fun protocolClosed() {
        observations.closed(clock.instant())?.let(::report)
        end("the protocol closed")
    }

    /** The process ended: the fact is reported at once, and the session ends. */
    fun processGone(exitCode: Int) {
        observations.gone(exitCode, clock.instant())?.let(::report)
        end("the agent's process ended with exit code $exitCode")
    }

    private fun report(fact: ObservedFact) {
        listener.observed(fact)
        events.trySend(Item.Line("observed ${fact.fact} ${fact.value}: ${fact.evidence}", clock.instant()))
    }

    private fun end(why: String) {
        if (!ended.compareAndSet(false, true) || !handedOut) return
        events.trySend(Item.Line("session ended: $why; its token is dead", clock.instant()))
        listener.tokenDead(seat, token)
    }

    /**
     * Mode `default`, then model, then effort, each set and read back (§10). Mode and effort must be among the values
     * the agent offers, and read back as set. The model is a full id, which the agent need not list (§9, Amendment
     * 11): it is set as given, and must read back as one of the values the agent offers other than `default` — as
     * itself, when the agent lists it.
     */
    @OptIn(UnstableApi::class)
    suspend fun configure(): Outcome<Settings> {
        for ((what, wanted) in listOf(MODE to DEFAULT_MODE, MODEL to spec.model, EFFORT to spec.effort)) {
            val offered = offered(what)
            if (what != MODEL && wanted !in offered) return Outcome.Failed("the agent does not offer $what $wanted (offered: ${list(offered)})")
            val option = option(what)
            try {
                when {
                    option != null -> session.setConfigOption(option.id, SessionConfigOptionValue.of(wanted))
                    what == MODE -> session.setMode(SessionModeId(wanted))
                    what == MODEL -> session.setModel(ModelId(wanted))
                    else -> return Outcome.Failed("the agent offers no way to set the $what")
                }
            } catch (e: Exception) {
                // The agent answered with an error: the SDK throws one of these two for it, by the error's code.
                if (e !is AcpExpectedError && e !is JsonRpcException) throw e
                return Outcome.Failed("the agent does not accept $what $wanted: ${e.message} (offered: ${list(offered)})")
            }
            note("$what set: $wanted")
        }
        val settings = Settings(current(MODE), current(MODEL), current(EFFORT))
        if (settings.mode != DEFAULT_MODE || settings.effort != spec.effort) {
            return Outcome.Failed("the agent reports mode ${settings.mode}, effort ${settings.effort} after they were set to $DEFAULT_MODE, ${spec.effort}")
        }
        val models = offered(MODEL)
        val readBack = settings.model
        if (readBack == null || readBack == DEFAULT_MODEL || readBack !in models || (spec.model in models && readBack != spec.model)) {
            return Outcome.Failed("the agent reads model ${spec.model} back as $readBack (offered: ${list(models)})")
        }
        return Outcome.Ok(settings)
    }

    private fun list(values: List<String>) = values.joinToString(", ").ifEmpty { "none" }

    /** What the agent offers now, by the values it accepts. */
    fun offers() = Offers(modes = offered(MODE), models = offered(MODEL), efforts = offered(EFFORT))

    @OptIn(UnstableApi::class)
    private fun offered(what: String): List<String> {
        val fromOption = option(what)?.let(::values).orEmpty()
        val fromMethod = when (what) {
            MODE -> if (session.modesSupported) session.availableModes.map { it.id.value } else emptyList()
            MODEL -> if (session.modelsSupported) session.availableModels.map { it.modelId.value } else emptyList()
            else -> emptyList()
        }
        return (fromOption + fromMethod).distinct()
    }

    @OptIn(UnstableApi::class)
    private fun current(what: String): String? = option(what)?.currentValue?.value ?: when (what) {
        MODE -> if (session.modesSupported) session.currentMode.value.value else null
        MODEL -> if (session.modelsSupported) session.currentModel.value.value else null
        else -> null
    }

    @OptIn(UnstableApi::class)
    private fun option(what: String): SessionConfigOption.Select? {
        if (!session.configOptionsSupported) return null
        val category = CATEGORIES.getValue(what)
        return session.configOptions.value.filterIsInstance<SessionConfigOption.Select>().firstOrNull { it.category == category }
    }

    private fun values(option: SessionConfigOption.Select): List<String> = when (val options = option.options) {
        is SessionConfigSelectOptions.Flat -> options.options.map { it.value.value }
        is SessionConfigSelectOptions.Grouped -> options.groups.flatMap { group -> group.options.map { it.value.value } }
    }

    data class Settings(val mode: String?, val model: String?, val effort: String?)

    /** What the collector takes: an update or the prompt's answer from the wire, or a line of the driver's own. */
    private sealed interface Item {
        val at: Instant
        val written: CompletableDeferred<Unit>? get() = null

        class Update(val update: JsonElement, override val at: Instant, val replay: Boolean = false) : Item
        class Answer(val message: JsonObject, override val at: Instant) : Item
        class Line(val text: String, override val at: Instant, override val written: CompletableDeferred<Unit>? = null) : Item
    }

    /** The turn in flight: its request id on the wire, whether the agent answered it, and its end as written. */
    private class Turn {
        @Volatile var requestId: JsonElement? = null
        val answered = AtomicBoolean(false)
        val ended = CompletableDeferred<Outcome<TurnEnd>>()
    }

    companion object {
        const val DEFAULT_MODE = "default"
        /** The model value that names no model: the agent's own choice, which moves with the agent (§9). */
        const val DEFAULT_MODEL = "default"
        private const val MODE = "mode"
        private const val MODEL = "model"
        private const val EFFORT = "effort"
        private val CATEGORIES = mapOf(
            MODE to SessionConfigOptionCategory.MODE,
            MODEL to SessionConfigOptionCategory.MODEL,
            EFFORT to SessionConfigOptionCategory.THOUGHT_LEVEL,
        )
    }
}
