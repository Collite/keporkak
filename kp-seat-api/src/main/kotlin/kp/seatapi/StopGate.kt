package kp.seatapi

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kp.mailroom.Mailroom
import java.util.concurrent.ConcurrentHashMap

/**
 * `/hook/stop` (contracts.md §11) on the MCP listener: the stop hook posts `{"stop_hook_active": bool}` with the session
 * token as bearer and reads `{block, reason}`. The API counts the blocks per session; the count starts again when the
 * hook's body says no block is active. The engine says whether the turn may end; this counts and asks. An unknown or
 * dead token never gets here — the token gate answers it 401, and the hook lets the turn end.
 */
internal class StopGate(private val mailroom: Mailroom) {
    /** Consecutive blocks so far, per session — keyed by what names a token without being one. */
    private val blocks = ConcurrentHashMap<String, Int>()

    fun route(application: Application) = application.routing {
        post(HOOK_STOP) {
            val bound = call.attributes[BOUND]
            val body = runCatching { Json.parseToJsonElement(call.receiveText()) as? JsonObject }.getOrNull()
            val active = (body?.get(ACTIVE) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: return@post call.respondText("""{"error": "the body is {\"$ACTIVE\": true | false}"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val sofar = if (active) blocks[bound.session] ?: 0 else 0
            val stop = mailroom.stopGate(bound.binding.seat, sofar)
            if (stop.block) blocks[bound.session] = sofar + 1 else blocks.remove(bound.session)
            call.respondText(buildJsonObject { put(BLOCK, stop.block); put(REASON, stop.reason) }.toString(), ContentType.Application.Json)
        }
    }
}

private const val HOOK_STOP = "/hook/stop"
private const val ACTIVE = "stop_hook_active"
private const val BLOCK = "block"
private const val REASON = "reason"
