package kp.driver.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kp.driver.Answerer
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.ObservedFact
import kp.driver.Offers
import kp.driver.Outcome
import kp.driver.SeatFacts
import kp.driver.SessionSpec
import kp.driver.SessionToken
import kp.driver.Started
import kp.driver.TurnEnd
import kp.driver.UsageWindow
import kp.model.SeatId
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** The driver over ACP, for one [AgentBinding]. */
internal class AcpDriver(
    private val binding: AgentBinding,
    private val seatsRoot: Path,
    private val clock: Clock,
    private val answerer: Answerer,
    private val listener: Listener,
) : Driver {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("kp-driver"))
    private val transcripts = ConcurrentHashMap<SeatId, Transcript>()
    private val launched = ConcurrentHashMap.newKeySet<AgentProcess>()
    private val seats = ConcurrentHashMap<SeatId, SeatSession>()
    private val random = SecureRandom()

    fun transcript(seat: SeatId): Transcript = transcripts.computeIfAbsent(seat) { Transcript.of(seatsRoot, it, clock) }

    /** A process started with the built environment, its connection, and `initialize` answered (T2). */
    suspend fun launch(spec: SessionSpec, onClosed: () -> Unit = {}, wire: Wire = Wire.NONE): Outcome<Launched> {
        val transcript = transcript(spec.seat)
        val environment = when (val built = binding.environment(spec)) {
            is Outcome.Ok -> built.value
            is Outcome.Failed -> return built.also { transcript.write("cannot start: ${it.reason}") }
        }
        environment.secrets.forEach(transcript::hide)
        val process = try {
            AgentProcess.start(spec.command, spec.workingDirectory, environment.variables)
        } catch (e: IOException) {
            return failed(transcript, "the agent cannot be started: ${e.message}")
        }
        launched += process
        transcript.write("process ${process.pid} started: ${spec.command.joinToString(" ")} in ${spec.workingDirectory}")
        val connection = AcpConnection(process, scope, "kp-driver ${spec.seat.value}", onNote = { transcript.write(it) }, onClosed = onClosed, wire = wire)
        val info = try {
            connection.initialize()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            process.end(Duration.ZERO)
            process.stderrTail().forEach { transcript.write("agent stderr: $it") }
            return failed(transcript, "initialize failed: ${e.message ?: e.javaClass.simpleName}")
        }
        transcript.write("initialized: ${info.implementation?.name ?: "agent"} ${info.implementation?.version ?: ""}, protocol ${info.protocolVersion}".replace("  ", " "))
        return Outcome.Ok(Launched(process, connection, info))
    }

    private fun failed(transcript: Transcript, reason: String): Outcome.Failed {
        val hidden = transcript.hidden(reason)
        transcript.write("failed: $hidden")
        return Outcome.Failed(hidden)
    }

    override suspend fun start(spec: SessionSpec): Outcome<Started> {
        val seat = when (val launched = fresh(spec)) {
            is Outcome.Ok -> launched.value
            is Outcome.Failed -> return launched
        }
        val parameters = SessionCreationParameters(spec.workingDirectory.toString(), servers(spec, seat.token))
        seat.session = try {
            seat.launched.connection.client.newSession(parameters) { _, _ -> seat.operations }
        } catch (e: Exception) {
            return abandon(seat, "session/new failed", e)
        }
        seat.note("session ${seat.session.sessionId.value} opened in ${spec.workingDirectory} with ${spec.servers.joinToString(", ") { it.name }.ifEmpty { "no MCP server" }}")
        return opened(seat)
    }

    /**
     * Loads [sessionId] into a fresh process (T7): `session/load` with the servers injected again under a fresh token
     * (§5); the agent replays the history, marked `replay` in the transcript; mode, model and effort are set again.
     * A session that cannot be loaded fails with the reason; starting a new one instead is the caller's decision.
     */
    override suspend fun load(spec: SessionSpec, sessionId: String): Outcome<Started> {
        val seat = when (val launched = fresh(spec)) {
            is Outcome.Ok -> launched.value
            is Outcome.Failed -> return launched
        }
        if (!seat.launched.info.capabilities.loadSession) return abandon(seat, "the agent does not offer session/load", null)
        val parameters = SessionCreationParameters(spec.workingDirectory.toString(), servers(spec, seat.token))
        seat.note("loading session $sessionId")
        seat.session = try {
            seat.launched.connection.client.loadSession(SessionId(sessionId), parameters, seat.operations)
        } catch (e: Exception) {
            return abandon(seat, "session/load of $sessionId failed", e)
        }
        seat.note("session $sessionId loaded in ${spec.workingDirectory} with ${spec.servers.joinToString(", ") { it.name }.ifEmpty { "no MCP server" }}")
        return opened(seat)
    }

    /** A fresh process for the seat, initialized, with a fresh token; refused while the seat has a live session. */
    private suspend fun fresh(spec: SessionSpec): Outcome<SeatSession> {
        seats[spec.seat]?.takeIf { it.live }?.let { return Outcome.Failed("seat ${spec.seat.value} has a live session ${it.session.sessionId.value}") }
        val seat = SeatSession(spec.seat, spec, mint(), transcript(spec.seat), clock, listener, answerer, scope)
        seat.transcript.hide(seat.token.value)
        val launched = when (val result = launch(spec, onClosed = seat::protocolClosed, wire = seat.wire)) {
            is Outcome.Ok -> result.value
            is Outcome.Failed -> return result
        }
        seat.launched = launched
        seat.observations.started(launched.process.pid)
        launched.process.exit.thenAccept { seat.processGone(it.exitValue()) }
        return Outcome.Ok(seat)
    }

    /** Mode, model and effort set; the seat registered; its token handed to the caller. */
    private suspend fun opened(seat: SeatSession): Outcome<Started> {
        val settings = try {
            seat.configure()
        } catch (e: Exception) {
            return abandon(seat, "setting mode, model and effort failed", e)
        }
        if (settings is Outcome.Failed) return abandon(seat, settings.reason, null)
        val set = (settings as Outcome.Ok).value
        seat.handedOut = true
        seats[seat.seat] = seat
        seat.note("started: session ${seat.session.sessionId.value}, mode ${set.mode}, model ${set.model}, effort ${set.effort}")
        return Outcome.Ok(Started(seat.seat, seat.session.sessionId.value, seat.token, set.mode!!, set.model!!, set.effort!!))
    }

    /** A session that could not be opened: its process is ended, and the reason returned. */
    private suspend fun abandon(seat: SeatSession, what: String, error: Exception?): Outcome.Failed {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        seat.launched.process.end(Duration.ZERO)
        return failed(seat.transcript, if (error == null) what else "$what: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun servers(spec: SessionSpec, token: SessionToken): List<McpServer> = spec.servers.map { server ->
        val headers = server.headers + if (server.carriesToken) mapOf("Authorization" to "Bearer ${token.value}") else emptyMap()
        McpServer.Http(server.name, server.url, headers.map { (name, value) -> HttpHeader(name, value) })
    }

    /** The session token (§5): random, one per started process. */
    private fun mint() = SessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes)))
    override suspend fun prompt(seat: SeatId, text: String): Outcome<TurnEnd> =
        seats[seat]?.prompt(text) ?: Outcome.Failed("seat ${seat.value} has no session")

    override suspend fun cancel(seat: SeatId): Outcome<Unit> =
        seats[seat]?.cancel() ?: Outcome.Failed("seat ${seat.value} has no session")
    /**
     * Closes the agent's input, waits [grace], then ends the process tree by force (T7). Returns when every process
     * of the tree is gone and `seat.process_gone` has been reported.
     */
    override suspend fun kill(seat: SeatId, grace: Duration): Outcome<Unit> {
        val session = seats[seat] ?: return Outcome.Failed("seat ${seat.value} has no session")
        val process = session.launched.process
        session.note("kill: input closed; grace ${Observations.duration(grace).content}, then the process tree by force")
        withContext(Dispatchers.IO) { process.end(grace) }
        session.processGone(process.exitCode() ?: -1)
        session.launched.connection.close()
        return Outcome.Ok(Unit)
    }
    override fun offers(seat: SeatId): Outcome<Offers> =
        seats[seat]?.let { Outcome.Ok(it.offers()) } ?: Outcome.Failed("seat ${seat.value} has no session")
    override fun observe(seat: SeatId, fact: String, window: UsageWindow?): Outcome<ObservedFact> {
        if (fact !in SeatFacts.names) return Outcome.Failed("the driver observes no fact named $fact")
        return seats[seat]?.observations?.observe(fact, window) ?: Outcome.Failed("seat ${seat.value} has no session")
    }

    override fun close() {
        launched.forEach { it.end(Duration.ZERO) }
        scope.cancel()
    }
}

private const val TOKEN_BYTES = 32

/** A started and initialized agent. */
internal class Launched(val process: AgentProcess, val connection: AcpConnection, val info: AgentInfo)
