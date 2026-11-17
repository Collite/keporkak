package kp.seatapi

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kp.mailroom.Mailroom
import kp.mailroom.Offer
import kp.mailroom.Sender
import kp.mailroom.result

/**
 * The five seat tools (contracts.md §5), each call made for the one seat its token names ([Binding]). A tool's answer is
 * JSON, as the text of its one content block and as its structured content; an error of the tool names the field.
 */
internal class Tools(private val mailroom: Mailroom) {
    /** An MCP server whose tools act for [binding]'s seat, and for no other. */
    fun server(binding: Binding): Server = Server(
        Implementation(name = "keporkak", version = "1"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).apply {
        addTool(SEND, "Send a message of a kind your role may send. Your seat is its sender, whatever the input says; `stage` defaults to yours. " +
            "The result says delivered, held or refused — a refusal names the rule and the requirement. There is no way to wait: after a " +
            "message that expects a reply, end your turn; the reply wakes you.", schema(SENT, required = listOf(KIND, SUBJECT))) { request ->
            send(binding, request.arguments ?: EMPTY)
        }
        addTool(REPLY, "Reply to a message: its thread, its stage and, when you give none, its subject are the message's. The result is send's.",
            schema(REPLIED, required = listOf(RE, KIND))) { request ->
            reply(binding, request.arguments ?: EMPTY)
        }
    }

    /**
     * `send` (contracts.md §5): the input goes to the mailroom in the seat's name — `from` and `by` are never taken from
     * it; only the fields a sender supplies are handed on. `stage` defaults to the seat's.
     */
    private fun send(binding: Binding, arguments: JsonObject): CallToolResult {
        val stage = binding.stage?.takeIf { STAGE !in arguments }?.let { mapOf(STAGE to JsonPrimitive(it)) }.orEmpty()
        return offered(mailroom.offer(Sender.Seat(binding.seat), JsonObject(arguments.filterKeys { it in SENT } + stage)))
    }

    /** `reply`: as `send`, with `re` — the mailroom gives it the thread, the stage and, when none is given, the subject of the message it answers. */
    private fun reply(binding: Binding, arguments: JsonObject): CallToolResult {
        if (arguments[RE] == null || arguments[RE] is JsonNull) return error(RE, "missing — a reply names the message it answers")
        return offered(mailroom.offer(Sender.Seat(binding.seat), JsonObject(arguments.filterKeys { it in REPLIED })))
    }

    /** What the mailroom answered: §5's result, or the error of the tool that names the field. */
    private fun offered(offer: Offer): CallToolResult = when (offer) {
        is Offer.Answered -> answer(offer.answer.result())
        is Offer.Malformed -> error(offer.field, offer.problem)
    }
}

/** A tool's answer: [value] as JSON text and structured content. */
internal fun answer(value: JsonObject) = CallToolResult(content = listOf(TextContent(value.toString())), structuredContent = value)

/** An error of the tool (contracts.md §5): `<field>: <what is wrong>` — no token, no value it was not given. */
internal fun error(field: String, problem: String) = CallToolResult(content = listOf(TextContent("$field: $problem")), isError = true)

/** An input schema over [fields], each of the type the message's schema gives it (contracts.md §2.1). */
private fun schema(fields: List<String>, required: List<String>) = ToolSchema(
    properties = buildJsonObject { fields.forEach { put(it, TYPES.getValue(it)) } },
    required = required,
)

private val EMPTY = JsonObject(emptyMap())

private const val SEND = "send"
private const val REPLY = "reply"
private const val KIND = "kind"
private const val TO = "to"
private const val STAGE = "stage"
private const val SUBJECT = "subject"
private const val BODY = "body"
private const val POINTERS = "pointers"
private const val ARGS = "args"
private const val RE = "re"

/** What a sender supplies to `send` and to `reply` (contracts.md §5) — the only fields handed on. */
private val SENT = listOf(KIND, TO, STAGE, SUBJECT, BODY, POINTERS, ARGS)
private val REPLIED = listOf(RE, KIND, SUBJECT, BODY, POINTERS, ARGS)

private val TYPES: Map<String, JsonObject> = mapOf(
    KIND to buildJsonObject { put("type", "string"); put("description", "the message's kind, as the rules name it") },
    TO to buildJsonObject { put("type", "string"); put("description", "the addressee, where the kind leaves a choice") },
    STAGE to buildJsonObject { put("type", "string"); put("description", "<effort>/P<n>/S<n.m>; yours when left out") },
    SUBJECT to buildJsonObject { put("type", "string"); put("description", "one line, at most 120 characters") },
    BODY to buildJsonObject { put("type", "string"); put("description", "prose for the reader; pointers, not payloads") },
    POINTERS to buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) },
    ARGS to buildJsonObject { put("type", "object"); put("description", "the kind's typed parameters") },
    RE to buildJsonObject { put("type", "string"); put("description", "the id of the message this answers") },
)
