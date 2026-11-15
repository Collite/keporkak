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
import kotlinx.coroutines.CompletableDeferred
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
    scope: CoroutineScope,
) {
    lateinit var launched: Launched
    lateinit var session: ClientSession
    @Volatile var handedOut = false
    private val ended = AtomicBoolean(false)
    private val turnInFlight = AtomicBoolean(false)
    private val events = Channel<Item>(Channel.UNLIMITED)
    @Volatile private var turn: Turn? = null

    val live: Boolean get() = !ended.get()

    init {
        scope.launch { for (item in events) take(item) }
    }

    /** What the agent sends without being asked, outside a turn's stream. */
    val operations = object : ClientSessionOperations {
        override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?) =
            RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        // The wire tap has taken it already, in order.
        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {}
    }

    /** The tap: what the collector takes from the wire, and the prompt's request id. */
    val wire = object : Wire {
        override fun incoming(line: String) {
            runCatching {
                val message = ACPJson.parseToJsonElement(line).jsonObject
                val method = (message["method"] as? JsonPrimitive)?.contentOrNull
                val id = message["id"]
                when {
                    method == "session/update" -> events.trySend(Item.Update(message.getValue("params").jsonObject.getValue("update"), clock.instant()))
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
                if ((message["method"] as? JsonPrimitive)?.contentOrNull == "session/prompt") turn?.requestId = message["id"]
            }
        }
    }

    /** One turn (T4). A prompt while a turn is in flight fails; it is never queued. */
    suspend fun prompt(text: String): Outcome<TurnEnd> {
        if (!live) return Outcome.Failed("the session of seat ${seat.value} has ended")
        if (!turnInFlight.compareAndSet(false, true)) return Outcome.Failed("a turn is in flight for seat ${seat.value}; the prompt is not queued")
        val current = Turn().also { turn = it }
        try {
            note("prompt: $text")
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

    /** Asks the agent to end the turn in flight; the turn then ends with the stop reason the agent gives. */
    suspend fun cancel(): Outcome<Unit> {
        if (!turnInFlight.get()) return Outcome.Failed("no turn is in flight for seat ${seat.value}")
        note("cancel sent")
        return try {
            session.cancel()
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            Outcome.Failed("the cancel could not be sent: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun turnFailed(reason: String): Outcome.Failed {
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
                is Item.Update -> transcript.write(
                    runCatching { Lines.of(ACPJson.decodeFromJsonElement(SessionUpdate.serializer(), item.update)) }
                        .getOrElse { "update not understood: ${item.update}" },
                    item.at,
                )
                is Item.Line -> transcript.write(item.text, item.at)
                is Item.Answer -> {
                    val end = runCatching { answer(item.message) }.getOrElse { Outcome.Failed("the prompt's answer is not understood: ${it.message}") }
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

    fun protocolClosed() = end("the protocol closed")

    fun processGone(exitCode: Int) = end("the agent's process ended with exit code $exitCode")

    private fun end(why: String) {
        if (!ended.compareAndSet(false, true) || !handedOut) return
        events.trySend(Item.Line("session ended: $why; its token is dead", clock.instant()))
        listener.tokenDead(seat, token)
    }

    /** Mode `default`, then model, then effort, each set as the agent offers it and read back (§10). */
    @OptIn(UnstableApi::class)
    suspend fun configure(): Outcome<Settings> {
        for ((what, wanted) in listOf(MODE to DEFAULT_MODE, MODEL to spec.model, EFFORT to spec.effort)) {
            val offered = offered(what)
            if (wanted !in offered) return Outcome.Failed("the agent does not offer $what $wanted (offered: ${offered.joinToString(", ").ifEmpty { "none" }})")
            val option = option(what)
            when {
                option != null -> session.setConfigOption(option.id, SessionConfigOptionValue.of(wanted))
                what == MODE -> session.setMode(SessionModeId(wanted))
                what == MODEL -> session.setModel(ModelId(wanted))
                else -> return Outcome.Failed("the agent offers no way to set the $what")
            }
            note("$what set: $wanted")
        }
        val settings = Settings(current(MODE), current(MODEL), current(EFFORT))
        val wanted = Settings(DEFAULT_MODE, spec.model, spec.effort)
        if (settings != wanted) return Outcome.Failed("the agent reports mode ${settings.mode}, model ${settings.model}, effort ${settings.effort} after they were set to $DEFAULT_MODE, ${spec.model}, ${spec.effort}")
        return Outcome.Ok(settings)
    }

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

        class Update(val update: JsonElement, override val at: Instant) : Item
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
