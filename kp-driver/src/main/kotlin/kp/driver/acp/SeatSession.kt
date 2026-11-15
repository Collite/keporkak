package kp.driver.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement
import kp.driver.Listener
import kp.driver.Offers
import kp.driver.Outcome
import kp.driver.SessionSpec
import kp.driver.SessionToken
import kp.model.SeatId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One seat's session in one agent process: the token minted for it, the connection, and what the agent offers.
 * When the session ends — its process gone or its protocol closed — its token is reported dead, once.
 */
internal class SeatSession(
    val seat: SeatId,
    val spec: SessionSpec,
    val token: SessionToken,
    val transcript: Transcript,
    private val listener: Listener,
) {
    lateinit var launched: Launched
    lateinit var session: ClientSession
    @Volatile var handedOut = false
    private val ended = AtomicBoolean(false)

    val live: Boolean get() = !ended.get()

    /** The operations the SDK calls for this session: what the agent sends without being asked. */
    val operations = object : ClientSessionOperations {
        override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?) =
            RequestPermissionResponse(RequestPermissionOutcome.Cancelled)

        override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {}
    }

    fun protocolClosed() = end("the protocol closed")

    fun processGone(exitCode: Int) = end("the agent's process ended with exit code $exitCode")

    private fun end(why: String) {
        if (!ended.compareAndSet(false, true) || !handedOut) return
        transcript.write("session ended: $why; its token is dead")
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
            transcript.write("$what set: $wanted")
        }
        val settings = Settings(current(MODE), current(MODEL), current(EFFORT))
        val wanted = Settings(DEFAULT_MODE, spec.model, spec.effort)
        if (settings != wanted) return Outcome.Failed("the agent reports mode ${settings.mode}, model ${settings.model}, effort ${settings.effort} after they were set to $DEFAULT_MODE, ${spec.model}, ${spec.effort}")
        return Outcome.Ok(settings)
    }

    /** What the agent offers now, by the values it accepts. */
    @OptIn(UnstableApi::class)
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
