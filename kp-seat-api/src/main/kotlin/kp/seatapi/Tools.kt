package kp.seatapi

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
        addTool(INBOX, "Your mail, oldest first: unread only unless `unread` is false, at most `limit`. Returning a message marks it read. Call it first.",
            schema(listOf(UNREAD, LIMIT), required = emptyList())) { request ->
            inbox(binding, request.arguments ?: EMPTY)
        }
        addTool(THREAD, "The messages of a thread, in order — only a thread you sent, received or were copied on a message of.",
            schema(listOf(ID), required = listOf(ID))) { request ->
            thread(binding, request.arguments ?: EMPTY)
        }
        addTool(WHOAMI, "Who you are: your seat, role, office, stage, model and effort; each kind you may send — to whom, what answers it, " +
            "its deadline, its window and how many are left in it; and the threads that wait for you or that you wait on.",
            schema(emptyList(), required = emptyList())) { _ ->
            whoami(binding)
        }
    }

    /**
     * `whoami` (contracts.md §5): the seat and what was bound with its token; `allowed`, asked of the engine through the
     * mailroom — no kind's name is in this source; `open`, the threads with a wait for the seat or on it.
     */
    private fun whoami(binding: Binding): CallToolResult = answer(buildJsonObject {
        put(SEAT, binding.seat)
        put(ROLE, binding.role)
        put(OFFICE, binding.office)
        put(STAGE, binding.stage)
        put(MODEL, binding.model)
        put(EFFORT, binding.effort)
        put(ALLOWED, JsonArray(mailroom.allowed(binding.seat, binding.stage).map { kind ->
            buildJsonObject {
                put(KIND, kind.kind)
                put(TO, kind.to)
                put(REPLY_KINDS, JsonArray(kind.replies.map(::JsonPrimitive)))
                put(DEADLINE, kind.deadline)
                put(WINDOW, kind.window)
                put(LEFT, kind.left)
            }
        }))
        put(OPEN, JsonArray(mailroom.open(binding.seat).map(::JsonPrimitive)))
    })

    /** `inbox` (contracts.md §5) as the mailroom gives it: `{messages, more}`; what it returns is read from now on. */
    private fun inbox(binding: Binding, arguments: JsonObject): CallToolResult {
        val unread = arguments[UNREAD]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: return error(UNREAD, "not true or false") }
        val limit = arguments[LIMIT]?.takeIf { it !is JsonNull }?.let { value ->
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: return error(LIMIT, "not a whole number above zero")
        }
        val inbox = mailroom.inbox(binding.seat, unread = unread ?: true, limit = limit)
        return answer(buildJsonObject { put(MESSAGES, JsonArray(inbox.messages.map { it.json })); put(MORE, inbox.more) })
    }

    /** `thread` (contracts.md §5): its messages, only to a party of it — to any other seat as if it did not exist. */
    private fun thread(binding: Binding, arguments: JsonObject): CallToolResult {
        val id = (arguments[ID] as? JsonPrimitive)?.takeIf { it.isString && MESSAGE_ID.matches(it.content) }?.content
            ?: return error(ID, "not a message id (${MESSAGE_ID.pattern})")
        val messages = mailroom.thread(binding.seat, id) ?: return error(ID, "no thread $id that ${binding.seat} is a party to")
        return answer(buildJsonObject { put(MESSAGES, JsonArray(messages.map { it.json })) })
    }

    /**
     * `send` (contracts.md §5): the input goes to the mailroom in the seat's name — `from` and `by` are never taken from
     * it; only the fields a sender supplies are handed on. `stage` defaults to the seat's.
     */
    private fun send(binding: Binding, arguments: JsonObject): CallToolResult {
        val stage = binding.stage?.takeIf { STAGE !in arguments }?.let { mapOf(STAGE to JsonPrimitive(it)) }.orEmpty()
        return offered(mailroom.submit(Sender.Seat(binding.seat), JsonObject(arguments.filterKeys { it in SENT } + stage)))
    }

    /** `reply`: as `send`, with `re` — the mailroom gives it the thread, the stage and, when none is given, the subject of the message it answers. */
    private fun reply(binding: Binding, arguments: JsonObject): CallToolResult {
        if (arguments[RE] == null || arguments[RE] is JsonNull) return error(RE, "missing — a reply names the message it answers")
        return offered(mailroom.submit(Sender.Seat(binding.seat), JsonObject(arguments.filterKeys { it in REPLIED })))
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
private const val INBOX = "inbox"
private const val THREAD = "thread"
private const val UNREAD = "unread"
private const val LIMIT = "limit"
private const val ID = "id"
private const val MESSAGES = "messages"
private const val MORE = "more"
private const val WHOAMI = "whoami"
private const val SEAT = "seat"
private const val ROLE = "role"
private const val OFFICE = "office"
private const val MODEL = "model"
private const val EFFORT = "effort"
private const val ALLOWED = "allowed"
/** `whoami`'s name for what answers a kind (contracts.md §5): the kinds the rules list under `replies`. */
private const val REPLY_KINDS = "reply"
private const val DEADLINE = "deadline"
private const val WINDOW = "window"
private const val LEFT = "left"
private const val OPEN = "open"
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
    UNREAD to buildJsonObject { put("type", "boolean"); put("description", "unread only (the default), or every message delivered to you") },
    LIMIT to buildJsonObject { put("type", "integer"); put("minimum", 1) },
    ID to buildJsonObject { put("type", "string"); put("description", "the thread's id: the id of its first message") },
)

/** A message's id (contracts.md §1). */
private val MESSAGE_ID = Regex("^m-[a-z0-9]+-[0-9]{6,}$")
