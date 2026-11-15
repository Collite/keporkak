package kp.driver.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Repairs, in the transport, a line from the agent whose unstable fields the pinned SDK cannot decode. The handshake
 * test covers each (architecture.md §5). A line that needs none passes unchanged.
 */
internal object Repairs {
    class Repaired(val line: String, val notes: List<String>)

    fun repair(line: String): Repaired {
        if (!line.contains("\"agentCapabilities\"")) return Repaired(line, emptyList())
        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return Repaired(line, emptyList())
        val result = message["result"] as? JsonObject ?: return Repaired(line, emptyList())
        val capabilities = result["agentCapabilities"] as? JsonObject ?: return Repaired(line, emptyList())
        val auth = capabilities["auth"] as? JsonObject ?: return Repaired(line, emptyList())
        val status = auth["status"] ?: return Repaired(line, emptyList())
        if (status == JsonNull || (status as? JsonPrimitive)?.booleanOrNull != null) return Repaired(line, emptyList())
        // W1 (design/06 Q1): the adapter sends `auth.status` as an object; SDK 0.34.0 types it Boolean and throws.
        val repaired = JsonObject(message + ("result" to JsonObject(result + ("agentCapabilities" to JsonObject(capabilities + ("auth" to JsonObject(auth - "status")))))))
        return Repaired(repaired.toString(), listOf("repaired W1: agentCapabilities.auth.status was $status where the SDK reads a boolean; dropped"))
    }
}
