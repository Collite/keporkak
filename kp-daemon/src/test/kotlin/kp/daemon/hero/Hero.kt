@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kp.daemon.Bora
import kp.daemon.Daemon
import kp.daemon.FakeSeating
import kp.daemon.Log
import kp.daemon.Office
import kp.daemon.Played
import kp.daemon.Player
import kp.daemon.Ran
import kp.daemon.Script
import kp.daemon.SimulatedPeers
import kp.daemon.Walk
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.engine.Datum
import kp.mailroom.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/** A clock that stands still until the spec sets it: the injected clock of the heroes. */
class SetClock(@Volatile var now: Instant) : Clock {
    override fun now(): Instant = now
}

/**
 * A hero (S1.6 T1's seam): the daemon as S1.5 starts it in rehearsal — the real loader, log, engine, mailroom and robots,
 * its control socket — on a throwaway project, with the walk [walk] of `rules/walks/` played by the harness from the
 * spec's own thread. Bora's steps are the real `scripts/kp` over the socket, `sudo` simulated ([AtTheSocket]); the clock
 * is injected and moves only when the spec sets it ([at]). The rules are the checkout's, or those under [root].
 */
class Hero(
    walk: String, startsAt: String = "2026-11-17T09:00:00Z", root: Path = checkout, prepared: Path? = null, world: (Script) -> Unit = {}, amend: (Walk) -> Walk = { it },
    /** The walk's seats as S2.1's fake agent, driven by the daemon's own seats (S2.4, `--seats fake`), panes in a tmux of [tmux]. */
    fake: FakeSeating? = null,
) : AutoCloseable {
    /**
     * The throwaway project — or [prepared], a project whose log is already written: the daemon starts on it again,
     * nothing seeded, in the world [world] sets up.
     */
    val project: Path = prepared ?: Files.createTempDirectory(checkout.resolve("build").createDirectories(), "hero-")
    private val sockets: Path = Files.createTempDirectory("kp-")
    private val socket: Path = sockets.resolve("kp.sock")
    private val peers = SimulatedPeers()
    val clock = SetClock(Instant.parse(startsAt))
    val script = Script(amend(Walk.read(walkFile(walk))))
    val said = ArrayList<String>()
    private val daemon: Daemon
    val office: Office get() = daemon.office
    val player: Player

    init {
        if (prepared == null) script.seed(project, OFFICE, clock)
        world(script)
        daemon = if (fake == null) Daemon.start(Office.start(OFFICE, project, root, script.facts, script.operations, script.seats, clock), script.facts, socket, peers)
        else fake.seat(root, script, project, OFFICE, clock, { said += it }, seeding = prepared == null).let { (seated, _) ->
            Daemon.start(seated.office, script.facts, socket, peers, beside = listOf(seated))
        }
        player = script.player(office, AtTheSocket()) { said += it }
    }

    /** The stage id the walk's stage [name] is played as. */
    fun stage(name: String = script.walk.stage): String = script.stageId(name)

    /**
     * Sets the clock to [time] (`2026-11-13T22:30:00Z`, or `22:30` on the clock's day — the next day once it is past) and
     * fires what then falls due, as the timers would; what that wrote, as the reader is told it.
     */
    fun at(time: String): List<String> {
        val before = office.state.seq
        val next = if ('T' in time) Instant.parse(time) else {
            val today = Instant.parse(clock.now.toString().substringBefore('T') + "T$time:00Z")
            if (today.isBefore(clock.now)) today.plusSeconds(DAY) else today
        }
        require(!next.isBefore(clock.now)) { "the clock does not go back: $next is before ${clock.now}" }
        clock.now = next
        office.tick()
        return Story(this).told(before + 1, office.state.seq)
    }

    /** Plays the walk up to and including its step [number] (from 1); what those steps wrote, as the reader is told it. */
    fun through(number: Int): List<String> = told(player.throughStep(number))

    /** Plays the rest of the walk and checks how it ends; what those steps wrote, as the reader is told it. */
    fun rest(): List<String> = told(player.rest())

    private fun told(played: List<Played>): List<String> = if (played.isEmpty()) emptyList() else Story(this).told(played.first().first, played.last().last)

    /** Runs `scripts/kp` with [args] on the daemon's socket; with [root], as `sudo kp …`, simulated. */
    fun kp(vararg args: String, root: Boolean = false): Ran {
        peers.root = root
        try {
            val process = ProcessBuilder(listOf(checkout.resolve("scripts/kp").toString()) + args).redirectErrorStream(true)
                .apply { environment()["KP_SOCKET"] = socket.toString() }.start()
            val text = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "kp ${args.joinToString(" ")} did not end" }
            return Ran(process.exitValue(), text)
        } finally {
            peers.root = false
        }
    }

    fun log(): Log = Log(project.resolve("mail"))

    override fun close() {
        daemon.close()
        sockets.deleteRecursively()
    }

    /**
     * Bora at the socket: each of his steps is the `kp` command that sends it — `sudo` when the step is signed — and his
     * acting is `sudo kp status`. A ruling that carries more than its outcome says it as `kp rule`'s `key=value` words
     * (contracts.md §6, Amendment 23); one about a message that raised no ticket — H-2′'s about the anomaly-stop, class
     * C — names that message by its id (Amendment 24).
     */
    private inner class AtTheSocket : Bora {
        override fun send(ask: Bora.Ask) {
            fun ticket(): String = ask.ticket?.toString() ?: throw WalkFailure("step ${ask.number}: no open ticket for Bora's ${ask.kind}")
            fun item(): String = ask.ticket?.toString() ?: ask.re ?: throw WalkFailure("step ${ask.number}: no open ticket or message for Bora's ${ask.kind}")
            val args = ask.args.fields
            val ran = when (ask.kind) {
                "approval" -> kp("approve", ticket(), root = ask.signed)
                "decline" -> kp("decline", ticket(), root = ask.signed)
                "ruling" -> kp(*(listOf("rule", item(), (args.getValue("outcome") as Datum.Text).value) +
                    args.filterKeys { it != "outcome" }.map { (key, value) -> "$key=${(value as Datum.Text).value}" }).toTypedArray(), root = ask.signed)
                "queue-approval" -> kp(*(listOf("queue") + (args["stages"] as Datum.Items).items.map { (it as Datum.Text).value }).toTypedArray(), root = ask.signed)
                "away" -> kp("away", root = ask.signed)
                else -> throw WalkFailure("step ${ask.number}: Bora has no command for ${ask.kind}")
            }
            said += "kp ${ask.kind}: ${ran.text.trim()}"
        }

        override fun acts() {
            said += "sudo kp status: " + kp("status", root = true).text.lines().first()
        }

    }

    companion object {
        const val OFFICE = "panda"

        private const val DAY = 86_400L

        fun walkFile(walk: String): Path =
            checkout.resolve("rules/walks/$walk.yaml").takeIf { it.exists() } ?: checkout.resolve("kp-daemon/src/test/resources/walks/$walk.yaml")
    }
}
