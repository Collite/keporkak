package kp.driver.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.protocol.ProtocolOptions
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

/**
 * ACP over one agent process's stdio: the SDK's transport, protocol and client. Each line from the agent passes
 * [Repairs] before the SDK decodes it; a repair is noted. [wire] sees every line, both ways, in the order the
 * transport carries it — before the SDK acts on an incoming one, and before an outgoing one reaches the agent.
 * [onClosed] runs once when the transport closes.
 */
internal class AcpConnection(
    process: AgentProcess,
    scope: CoroutineScope,
    name: String,
    onNote: (String) -> Unit,
    onClosed: () -> Unit,
    wire: Wire = Wire.NONE,
) {
    private val transport = StdioTransport(
        scope,
        Dispatchers.IO,
        process.lines().map { line -> Repairs.repair(line).also { it.notes.forEach(onNote) }.line.also(wire::incoming) },
        { line -> wire.outgoing(line); process.write(line) },
        name,
    )
    private val protocol = Protocol(scope, transport, ProtocolOptions(protocolDebugName = name))
    val client = Client(protocol)

    init {
        transport.onClose(onClosed)
        protocol.start()
    }

    /** `initialize`, version 1 only; the client declares neither `fs` nor `terminal` (S2.1 T2). */
    suspend fun initialize(): AgentInfo = client.initialize(
        ClientInfo(
            protocolVersion = PROTOCOL_VERSION,
            capabilities = ClientCapabilities(fs = FileSystemCapability(readTextFile = false, writeTextFile = false), terminal = false),
            implementation = Implementation(name = "keporkak", version = "kp-driver"),
            supportedProtocolVersions = setOf(PROTOCOL_VERSION),
        )
    )

    fun close() = protocol.close()

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

/** A tap on the wire; it must not throw. */
internal interface Wire {
    fun incoming(line: String)
    fun outgoing(line: String)

    companion object {
        val NONE = object : Wire {
            override fun incoming(line: String) {}
            override fun outgoing(line: String) {}
        }
    }
}
