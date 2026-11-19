package kp.daemon

import kp.mailroom.FactSource
import java.nio.file.Path
import java.time.Duration

/**
 * The daemon as a process (architecture.md §2, §4): start-up — the rules, the fold of every log file, the timers, the
 * control socket, in that order — and a clean stop: the socket first, so no command arrives half-way; then the
 * timers; then what runs beside the mailroom — the seats, which die with the daemon, and the seat API (S2.4); then the
 * mailroom, whose writer lets go of the log's lock. The office it runs is built by its caller: the service's, or a
 * rehearsal's.
 */
class Daemon private constructor(val office: Office, private val socket: ControlSocket, private val timers: Thread, private val beside: List<AutoCloseable>) : AutoCloseable {
    @Volatile private var stopped = false

    /** Stops the daemon cleanly; a second call does nothing. */
    @Synchronized
    override fun close() {
        if (stopped) return
        stopped = true
        socket.close()
        timers.interrupt()
        timers.join(JOIN_MILLIS)
        beside.forEach { runCatching(it::close).onFailure { error -> Journal.say("stopping: ${error.message ?: error.javaClass.simpleName}") } }
        office.close()
        Journal.say("stopped: office ${office.name}")
    }

    companion object {
        private const val JOIN_MILLIS = 10_000L
        /** How often the rule texts are compared with those in force (hot reload), at most. */
        private val RELOAD_EVERY: Duration = Duration.ofSeconds(5)

        /**
         * Starts the daemon around [office]: its timers, then its control socket at [socketPath], whose peers [peers]
         * reads. [world] is what the office observes besides Bora's presence — the three lines of a stage list among it.
         * [beside] runs beside the mailroom and is stopped, in order, before it.
         */
        fun start(office: Office, world: FactSource, socketPath: Path, peers: PeerReader, beside: List<AutoCloseable> = emptyList()): Daemon {
            val timers = Thread.ofPlatform().name("kp-timers").daemon(true).unstarted { timers(office) }
            timers.start()
            val socket = try {
                ControlSocket.open(socketPath, peers, KpCommands(office, world))
            } catch (error: Exception) {
                timers.interrupt()
                beside.forEach { runCatching(it::close) }
                office.close()
                throw error
            }
            Journal.say("started: office ${office.name} · project ${office.project} · rules ${office.rules.hash} · socket $socketPath")
            return Daemon(office, socket, timers, beside)
        }

        /** Fires each deadline when the clock passes it, and puts changed rule texts to the mailroom, until interrupted. */
        private fun timers(office: Office) {
            while (!Thread.currentThread().isInterrupted && !office.closed) {
                try {
                    val now = office.clock.now()
                    val next = listOfNotNull(office.nextDeadline(), now.plus(RELOAD_EVERY)).min()
                    val wait = Duration.between(now, next).toMillis()
                    if (wait > 0) Thread.sleep(wait)
                    if (office.nextDeadline()?.let { !it.isAfter(office.clock.now()) } == true) office.tick()
                    office.reloadIfChanged()
                } catch (_: InterruptedException) {
                    return
                } catch (error: Exception) {
                    if (office.closed) return
                    Journal.say("timers: ${error.message ?: error.javaClass.simpleName}")
                    try {
                        Thread.sleep(RELOAD_EVERY.toMillis())
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }
}
