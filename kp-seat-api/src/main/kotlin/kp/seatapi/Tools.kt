package kp.seatapi

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kp.mailroom.Mailroom

/**
 * The five seat tools (contracts.md §5), each call made for the one seat its token names ([Binding]). A tool's answer is
 * JSON, as the text of its one content block and as its structured content; an error of the tool names the field.
 */
internal class Tools(private val mailroom: Mailroom) {
    /** An MCP server whose tools act for [binding]'s seat, and for no other. */
    fun server(binding: Binding): Server = Server(
        Implementation(name = "keporkak", version = "1"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
}

/** A tool's answer: [value] as JSON text and structured content. */
internal fun answer(value: JsonObject) = CallToolResult(content = listOf(TextContent(value.toString())), structuredContent = value)

/** An error of the tool (contracts.md §5): `<field>: <what is wrong>` — no token, no value it was not given. */
internal fun error(field: String, problem: String) = CallToolResult(content = listOf(TextContent("$field: $problem")), isError = true)
