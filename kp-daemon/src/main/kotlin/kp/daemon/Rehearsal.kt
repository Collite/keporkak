package kp.daemon

import kp.mailroom.Clock
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `just rehearse <walk>` (architecture.md §7): the daemon on a throwaway project, the walk played through it by the
 * harness ([Script]) — its seats scripted, its facts tabled, its operations ended as the walk says — pausing where the
 * walk needs Bora, who answers with the real `kp` on the daemon's socket. The robots are the daemon's. A step that does
 * not do what the walk expects ends the playing, and says why; the daemon keeps answering `kp` until the rehearsal is
 * closed.
 */
class Rehearsal private constructor(private val script: Script, private val daemon: Daemon, private val out: (String) -> Unit) : AutoCloseable {
    /** The office the daemon runs: what a spec fires the deadlines of once it has moved the clock. */
    internal val office: Office get() = daemon.office
    private val lock = Object()
    private var pauses = 0
    private var returned = 0
    private var waitingFor: String? = null
    private var ended = false
    private val player = Thread.ofPlatform().name("kp-rehearsal").daemon(true).unstarted(::play)

    /** Blocks until the walk next waits for Bora — the kind it waits for — or has ended (null), at most [millis]. */
    fun awaitBora(millis: Long = 120_000): String? = synchronized(lock) {
        val end = System.nanoTime() + millis * 1_000_000
        while (pauses == returned && !ended) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) error("the rehearsal neither waited for Bora nor ended in ${millis}ms")
            lock.wait(left)
        }
        if (pauses == returned) return null
        returned = pauses
        waitingFor
    }

    override fun close() {
        player.interrupt()
        player.join(JOIN_MILLIS)
        daemon.close()
    }

    private fun play() {
        try {
            out("rehearsing ${script.walk.name}: stage ${script.stageId(script.walk.stage)} on ${office.project}")
            script.player(office, AtTheCommandLine(), out).rest()
            out("the walk is done: the stage is ${office.state.stage(script.stageId(script.walk.stage))}. The daemon answers kp until the rehearsal is stopped (Ctrl-C).")
        } catch (_: InterruptedException) {
            // closed while waiting for Bora
        } catch (failure: WalkFailure) {
            out("the walk stops here: ${failure.message}")
        } catch (error: Exception) {
            if (!office.closed) out("the rehearsal stopped: $error")
        } finally {
            synchronized(lock) {
                ended = true
                lock.notifyAll()
            }
        }
    }

    /** Bora at the command line: the walk pauses, says which `kp` command answers, and goes on once his message is on the log. */
    private inner class AtTheCommandLine : Bora {
        /** Waits until Bora's message of the kind is delivered or held: a refused one — unsigned, say — is his to send again. */
        override fun send(ask: Bora.Ask) {
            val before = office.state.delivered.keys + office.state.held.keys
            pause(ask.kind, "${ask.number} · waiting for Bora's ${ask.kind}: kp inbox, then ${command(ask)}")
            waitFor {
                val state = office.state
                (state.delivered.values.filter { it.from == BORA && it.kind == ask.kind }.map { it.id } +
                    state.held.keys.filter { state.pending[it]?.let { held -> held.from == BORA && held.kind == ask.kind } == true }).any { it !in before }
            }
        }

        /** Waits until Bora is present: any signed command brings him back. */
        override fun acts() {
            if (office.state.presence(BORA) in setOf(null, PRESENT)) return
            pause(ACTED, "waiting for Bora to act: any sudo kp …")
            waitFor { office.state.presence(BORA) == PRESENT }
        }

        private fun pause(kind: String, said: String) {
            out(said)
            synchronized(lock) {
                pauses++
                waitingFor = kind
                lock.notifyAll()
            }
        }

        private fun waitFor(done: () -> Boolean) {
            var version = office.changes.version()
            while (!done()) {
                if (office.closed) throw InterruptedException("the rehearsal was closed")
                version = office.changes.await(version, WAIT_MILLIS)
            }
        }

        /** The `kp` command that answers what Bora is asked. */
        private fun command(ask: Bora.Ask): String {
            val ticket = ask.ticket?.toString() ?: "<ticket>"
            val outcome = (ask.args.fields[OUTCOME] as? kp.engine.Datum.Text)?.value ?: "<outcome>"
            val sudo = if (ask.signed) "sudo " else ""
            return sudo + when (ask.kind) {
                "approval" -> "kp approve $ticket"
                "decline" -> "kp decline $ticket"
                "ruling" -> "kp rule ${ask.ticket ?: ask.re ?: "<ticket>"} $outcome"
                "away" -> "kp away"
                "queue-approval" -> "kp queue ${(ask.args.fields["stages"] as? kp.engine.Datum.Items)?.items?.joinToString(" ") { (it as kp.engine.Datum.Text).value } ?: ask.stage}"
                else -> "kp … (${ask.kind})"
            }
        }
    }

    companion object {
        private const val JOIN_MILLIS = 10_000L
        private const val WAIT_MILLIS = 30_000L
        private const val BORA = "bora"
        private const val PRESENT = "present"
        private const val ACTED = "acted"
        private const val OUTCOME = "outcome"
        /** The effort a rehearsal's stage belongs to: none of the plan's. */
        const val EFFORT = Script.EFFORT

        /**
         * Rehearses [walk] on the throwaway project [project] as office [office]: what the walk starts from is written
         * first, the daemon starts with the walk's facts and listens on [socket], and the walk is played until it needs
         * Bora. [out] hears what happens. [clock] is the wall clock unless a spec moves it.
         */
        fun start(
            root: Path, walk: Path, project: Path, office: String, socket: Path, peers: PeerReader,
            clock: Clock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }, out: (String) -> Unit,
        ): Rehearsal {
            val script = Script(Walk.read(walk))
            script.seed(project, office, clock)
            val started = Office.start(office, project, root, script.facts, script.operations, script.seats, clock)
            val daemon = Daemon.start(started, script.facts, socket, peers)
            return Rehearsal(script, daemon, out).also { it.player.start() }
        }
    }
}
