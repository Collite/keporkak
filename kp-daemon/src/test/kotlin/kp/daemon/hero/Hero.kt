@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.daemon.Bora
import kp.daemon.Daemon
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
import kp.mailroom.Sender
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
class Hero(walk: String, startsAt: String = "2026-11-17T09:00:00Z", root: Path = checkout, amend: (Walk) -> Walk = { it }) : AutoCloseable {
    val project: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "hero-")
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
        script.seed(project, OFFICE, clock)
        daemon = Daemon.start(Office.start(OFFICE, project, root, script.facts, script.operations, script.seats, clock), script.facts, socket, peers)
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
     * acting is `sudo kp status`. A ruling that carries more than its outcome (the `seat` and `cause` POOL-6 reads) is
     * more than `kp rule` can say: it goes in by the door `kp` uses, his signed command (a Found line of S1.6).
     */
    private inner class AtTheSocket : Bora {
        override fun send(ask: Bora.Ask) {
            fun ticket(): String = ask.ticket?.toString() ?: throw WalkFailure("step ${ask.number}: no open ticket for Bora's ${ask.kind}")
            val args = ask.args.fields
            val ran = when (ask.kind) {
                "approval" -> kp("approve", ticket(), root = ask.signed)
                "decline" -> kp("decline", ticket(), root = ask.signed)
                "ruling" -> if (args.keys == setOf("outcome")) kp("rule", ticket(), (args.getValue("outcome") as Datum.Text).value, root = ask.signed) else signed(ask)
                "queue-approval" -> kp(*(listOf("queue") + (args["stages"] as Datum.Items).items.map { (it as Datum.Text).value }).toTypedArray(), root = ask.signed)
                "away" -> kp("away", root = ask.signed)
                else -> throw WalkFailure("step ${ask.number}: Bora has no command for ${ask.kind}")
            }
            said += "kp ${ask.kind}: ${ran.text.trim()}"
        }

        override fun acts() {
            said += "sudo kp status: " + kp("status", root = true).text.lines().first()
        }

        private fun signed(ask: Bora.Ask): Ran {
            if (ask.signed) office.acted()
            val input = JsonObject(listOfNotNull("kind" to JsonPrimitive(ask.kind), "stage" to JsonPrimitive(ask.stage), "subject" to JsonPrimitive("${ask.kind} (step ${ask.number})"),
                ask.re?.let { "re" to JsonPrimitive(it) }, "args" to kotlinx.serialization.json.Json.parseToJsonElement(ask.args.json())).toMap())
            val answer = office.submit(Sender.Bora(signed = ask.signed), input)
            return Ran(0, "${answer.status} ${answer.id}")
        }
    }

    companion object {
        const val OFFICE = "panda"

        /**
         * `h2-prime`'s shadow rulings name no `re`: as written, each opens a thread of its own, where `thread.state eq held`
         * cannot hold. The Deputy's half writes it about the held item that woke it — the one the `hold` before it answers.
         */
        val shadowRulingsAnswerTheirHold: (Walk) -> Walk = { walk ->
            walk.withSteps(walk.steps.mapIndexed { index, step ->
                if (step.kind != "shadow-ruling" || step.text("re") != null) step
                else walk.steps.take(index).lastOrNull { it.kind == "hold" }?.text("re")?.let { step.with("re", Datum.Text(it)) } ?: step
            })
        }

        /**
         * `h2-prime` has the TeamLead send the tail's `merge` again once Bora is back; the daemon lets the merge the Deputy
         * held go at his return (DEP-3 holds it `until: presence`), and that one asks for `git.merge`. The second merge
         * is left out and the walk's `git.merge` ends the first: the tail resumes, as design/11 §3 says.
         */
        val theHeldMergeResumesTheTail: (Walk) -> Walk = { walk ->
            val again = walk.steps.single { it.id == "tail-merge" }
            walk.withSteps(walk.steps.filter { it !== again }.map { step ->
                if (step.operation != "git.merge" || step.text("request") != "tail-merge") step
                else step.with("request", Datum.Text("tail-held")).let { merge -> again.fields["stage"]?.let { merge.with("stage", it) } ?: merge }
            })
        }

        private const val DAY = 86_400L

        fun walkFile(walk: String): Path =
            checkout.resolve("rules/walks/$walk.yaml").takeIf { it.exists() } ?: checkout.resolve("kp-daemon/src/test/resources/walks/$walk.yaml")
    }
}
