package kp.driver.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.rpc.ACPJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kp.driver.Usage

/** The transcript's wording: one readable line per event the agent sends. */
internal object Lines {
    @OptIn(UnstableApi::class)
    fun of(update: SessionUpdate): String = when (update) {
        is SessionUpdate.UserMessageChunk -> "user: ${text(update.content)}"
        is SessionUpdate.AgentMessageChunk -> "agent: ${text(update.content)}"
        is SessionUpdate.AgentThoughtChunk -> "thought: ${text(update.content)}"
        is SessionUpdate.ToolCall -> "tool ${update.toolCallId.value} ${update.status?.let(::wire) ?: "pending"}: ${update.title}${input(update.rawInput)}"
        is SessionUpdate.ToolCallUpdate -> "tool ${update.toolCallId.value} ${update.status?.let(::wire) ?: "updated"}${update.title?.let { ": $it" } ?: ""}${input(update.rawInput)}" +
            output(update.content, update.rawOutput)
        is SessionUpdate.PlanUpdate -> "plan: ${update.entries.size} entries"
        is SessionUpdate.PlanUpdateV2 -> "plan updated"
        is SessionUpdate.PlanRemoved -> "plan removed: ${update.id}"
        is SessionUpdate.AvailableCommandsUpdate -> "commands offered: ${update.availableCommands.size}"
        is SessionUpdate.CurrentModeUpdate -> "mode now: ${update.currentModeId.value}"
        is SessionUpdate.ConfigOptionUpdate -> "options now: " + update.configOptions.joinToString(", ") { option ->
            "${option.id.value}=" + when (option) {
                is SessionConfigOption.Select -> option.currentValue.value
                is SessionConfigOption.BooleanOption -> option.currentValue.toString()
            }
        }
        is SessionUpdate.SessionInfoUpdate -> "session info: ${update.title ?: ""}".trimEnd()
        is SessionUpdate.UsageUpdate -> "context: ${update.used} of ${update.size}"
        is SessionUpdate.UnknownSessionUpdate -> "update ${update.sessionUpdateType}"
    }

    fun turnEnded(stopReason: String, usage: Usage?) =
        "turn ended: $stopReason; " + (usage?.let { "tokens input ${it.input}, output ${it.output}, total ${it.total}" } ?: "no usage reported")

    private fun text(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.text
        else -> "[${block.javaClass.simpleName.lowercase()}]"
    }

    private fun input(raw: JsonElement?) = if (raw == null || raw == JsonNull) "" else " $raw"

    /**
     * A tool's result, as the agent reports it (S2.5): the text of its content — a refusal of a seat's `send` is one
     * (contracts.md §5) — else its raw output; at most [OUTPUT_LIMIT] characters, and nothing when it reports none.
     */
    private fun output(content: List<ToolCallContent>?, raw: JsonElement?): String {
        val text = content.orEmpty().mapNotNull { (it as? ToolCallContent.Content)?.content?.let(::text) }.joinToString(" ")
            .ifEmpty { if (raw == null || raw == JsonNull) "" else raw.toString() }
        if (text.isEmpty()) return ""
        return " → " + if (text.length <= OUTPUT_LIMIT) text else text.take(OUTPUT_LIMIT) + " … (${text.length - OUTPUT_LIMIT} more characters)"
    }

    private const val OUTPUT_LIMIT = 2000

    /** The protocol's own word for an enum value, as it travels. */
    fun <T : Enum<T>> wire(value: T, serializer: KSerializer<T>): String = ACPJson.encodeToJsonElement(serializer, value).jsonPrimitive.content

    private fun wire(status: com.agentclientprotocol.model.ToolCallStatus) = wire(status, com.agentclientprotocol.model.ToolCallStatus.serializer())
}
