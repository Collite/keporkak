package kp.driver.acp

import com.agentclientprotocol.agent.AgentInfo
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kp.driver.Answerer
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.ObservedFact
import kp.driver.Offers
import kp.driver.Outcome
import kp.driver.SessionSpec
import kp.driver.Started
import kp.driver.TurnEnd
import kp.driver.UsageWindow
import kp.model.SeatId
import java.io.IOException
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

    fun transcript(seat: SeatId): Transcript = transcripts.computeIfAbsent(seat) { Transcript.of(seatsRoot, it, clock) }

    /** A process started with the built environment, its connection, and `initialize` answered (T2). */
    suspend fun launch(spec: SessionSpec, onClosed: () -> Unit = {}): Outcome<Launched> {
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
        val connection = AcpConnection(process, scope, "kp-driver ${spec.seat.value}", onNote = { transcript.write(it) }, onClosed = onClosed)
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

    override suspend fun start(spec: SessionSpec): Outcome<Started> = TODO("S2.1 T3")
    override suspend fun prompt(seat: SeatId, text: String): Outcome<TurnEnd> = TODO("S2.1 T4")
    override suspend fun cancel(seat: SeatId): Outcome<Unit> = TODO("S2.1 T4")
    override suspend fun kill(seat: SeatId, grace: Duration): Outcome<Unit> = TODO("S2.1 T7")
    override suspend fun load(spec: SessionSpec, sessionId: String): Outcome<Started> = TODO("S2.1 T7")
    override fun offers(seat: SeatId): Outcome<Offers> = TODO("S2.1 T3")
    override fun observe(seat: SeatId, fact: String, window: UsageWindow?): Outcome<ObservedFact> = TODO("S2.1 T6")

    override fun close() {
        launched.forEach { it.end(Duration.ZERO) }
        scope.cancel()
    }
}

/** A started and initialized agent. */
internal class Launched(val process: AgentProcess, val connection: AcpConnection, val info: AgentInfo)
