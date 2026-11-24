@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kp.daemon.answered
import kp.daemon.Bora
import kp.daemon.Daemon
import kp.daemon.FakeSeating
import kp.daemon.Office
import kp.daemon.Player
import kp.daemon.Script
import kp.daemon.SimulatedPeers
import kp.daemon.Walk
import kp.daemon.WalkFailure
import kp.daemon.checkout
import kp.daemon.hero.Hero
import kp.daemon.hero.SetClock
import kp.mailroom.Answer
import kp.mailroom.Sender
import kp.model.Envelope
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kp.model.Read
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * S2.4 T1's seam: the daemon started on a throwaway project as a rehearsal starts it — the real mailroom, seat API and
 * driver — with S2.1's fake agent as every seat's process ([FakeSeating]), the world's facts and the other operations
 * from a walk's table, and an injected clock. The walk is played step by step by the spec; its seats are seeded as the
 * walk starts them. Panes go to a fake tmux that notes what it is asked ([tmuxCalls]), or to none ([tmux] given).
 * [prepared] starts the daemon again on a project an earlier one wrote: nothing is seeded, and no seat is started.
 */
class SeatsOffice(
    walk: String = "seats",
    val root: Path = checkout,
    prepared: Path? = null,
    /** Each seat's fake-agent script, without its `record`: written before the seat starts. */
    scripts: Map<String, String> = emptyMap(),
    tmux: String? = null,
    startsAt: String = "2026-11-18T09:00:00Z",
    /**
     * S3.7: the seats perform `token.mint` and answer a seat's ask for a GitHub token, against S3.2's local GitHub — the
     * four Apps, with keys of the spec's in the seats' secrets folder (`office/secrets/apps`) — which is [apps].
     */
    github: Boolean = false,
    /** The shape of the tokens [apps] mints: GitHub's today, unless a spec asks for the former one. */
    private val shape: kp.ops.FakeApps.TokenShape = kp.ops.FakeApps.TokenShape.STATELESS,
) : AutoCloseable {
    val project: Path = prepared ?: Files.createTempDirectory(checkout.resolve("build").createDirectories(), "seats-")
    val apps: kp.ops.FakeApps? = if (github) kp.ops.FakeApps.start(project.resolve("office/secrets/apps"), shape = shape).also { apps -> kp.daemon.THE_APPS.forEach(apps::add) } else null
    private val sockets: Path = Files.createTempDirectory("kp-")
    val clock = SetClock(Instant.parse(startsAt))
    val script = Script(Walk.read(Hero.walkFile(walk)), EFFORT)
    val said = CopyOnWriteArrayList<String>()
    private val fakeTmux: Path = project.resolve("office/tmux").also { writeFakeTmux(it) }
    private val daemon: Daemon
    val seated: kp.daemon.seats.Seated
    val agents: FakeAgents
    val player: Player

    init {
        if (prepared == null) script.seed(project, OFFICE, clock)
        val fake = project.resolve("office/fake").createDirectories()
        scripts.forEach { (seat, text) ->
            val given = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
            val defaults = mapOf("probe" to JsonPrimitive(true), "efforts" to kotlinx.serialization.json.Json.parseToJsonElement("""["low", "medium", "high", "xhigh", "max"]"""))
            fake.resolve("$seat.json").writeText(JsonObject(defaults + given + ("record" to JsonPrimitive(fake.resolve("$seat.jsonl").toString()))).toString())
        }
        val seating = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = tmux ?: fakeTmux.toString(), panes = PANES, github = apps?.api)
        val (seated, agents) = seating.seat(root, script, project, OFFICE, clock, { said += it }, seeding = prepared == null)
        this.seated = seated
        this.agents = agents
        daemon = Daemon.start(seated.office, script.facts, sockets.resolve("kp.sock"), SimulatedPeers(), beside = listOf(seated))
        player = script.player(office, NoBora)
    }

    val office: Office get() = daemon.office
    val seats: Seats get() = seated.seats
    fun stage(name: String = script.walk.stage): String = script.stageId(name)

    /** A message [from] a seat or role, as its tool hands it on: [kind] about the walk's stage, with [args] (JSON). */
    fun send(from: String, kind: String, args: String = "{}", re: String? = null, stage: String = stage()): Answer =
        office.submit(Sender.Seat(from), kotlinx.serialization.json.Json.parseToJsonElement(
            """{"kind": "$kind", "stage": "$stage", "subject": "$kind", "args": $args""" + (re?.let { """, "re": "$it"""" } ?: "") + "}",
        ) as JsonObject).answered()

    /** The log as it counts, in `seq` order. */
    fun lines(): List<Envelope> = office.counted()

    /** Each line, as a reader tells it: a message by its kind and parties, a record by its event and what it is about. */
    fun told(after: Long = 0): List<String> = lines().filter { it.seq.toLong() > after }.map(::told)

    /** Waits until [done] holds, at most [seconds]; the spec's view of a daemon that works on threads of its own. */
    fun until(what: String, seconds: Long = 30, done: () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000
        var version = office.changes.version()
        while (!done()) {
            if (System.nanoTime() > end) throw AssertionError("waited ${seconds}s for $what; the log:\n" + told().joinToString("\n"))
            version = office.changes.await(version, 200)
        }
    }

    /** [seat]'s unread messages, each as `kind id`. */
    fun unread(seat: String): List<String> = office.state.unread(seat).map { id -> "${office.state.delivered[id]?.kind} $id" }

    /** The operations of [op] on the log, `phase cause`. */
    fun ops(op: String): List<Op> = lines().filterIsInstance<Op>().filter { it.op == op }

    /** What [seat]'s fake agents received, in order: `event` and the field of note — a prompt's text, a setting, the probe's status. */
    fun received(seat: String): List<JsonObject> = agents.received(seat)

    /** The texts [seat]'s fake agents were prompted with, in order. */
    fun prompts(seat: String): List<String> = received(seat).filter { it.text("event") == "session/prompt" }.map { it.text("text")!! }

    /**
     * Waits until [seat]'s fake agents have recorded a prompt that [done] holds of — the record a fake agent writes once the
     * prompt has reached it, after the daemon's log says the prompt was sent (S3.10 T2): by that record, never a time.
     */
    fun prompted(seat: String, what: String, done: (String) -> Boolean) = awaitWritten(agents.record(seat), "$seat prompted with $what") { prompts(seat).any(done) }

    /** What the fake tmux was asked, one call a line. */
    fun tmuxCalls(): List<String> = project.resolve("office/tmux.log").let { if (Files.exists(it)) it.readLines() else emptyList() }

    /** The fake agents' `hold` steps named [name] end: their file exists from now on. */
    fun release(name: String) = Path.of(holdPath(name)).writeText("released\n")

    /** The daemon stops — its seats die with it — and the project stays: [again] starts it on what was written. */
    override fun close() {
        daemon.close()
        apps?.close()
        sockets.deleteRecursively()
    }

    /** A daemon started again on this project, with the same walk: nothing seeded, no seat started; [github] as asked, its tokens in this one's shape. */
    fun again(scripts: Map<String, String> = emptyMap(), github: Boolean = false): SeatsOffice = SeatsOffice(script.walk.name, root, project, scripts, github = github, shape = shape)

    fun delete() = project.deleteRecursively()

    /** A walk played here by the spec never needs Bora. */
    private object NoBora : Bora {
        override fun send(ask: Bora.Ask) = throw WalkFailure("the seats' walk asks nothing of Bora")
        override fun acts() = Unit
    }

    companion object {
        const val OFFICE = "panda"
        const val EFFORT = "KP"
        const val PANES = "kp-spec"

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private val holds: Path = Files.createTempDirectory("kp-holds-")

        /** The file a fake agent's `hold` step named [name] waits for; a name is used by one spec only. */
        fun holdPath(name: String): String = holds.resolve(name).toString()

        fun told(line: Envelope): String = when (line) {
            is Message -> "${line.kind} ${line.from.value}→${line.to.value}"
            is Op -> "op ${line.op} ${line.phase}" + ((line.with?.get("seat") ?: line.result?.get("seat")) as? JsonPrimitive)?.let { " ${it.content}" }.orEmpty() + (line.reason?.let { ": $it" } ?: "")
            is Read -> "read ${line.seat}"
            is Fact -> "fact ${line.fact} ${line.subject}"
            is kp.model.Transition -> "transition ${line.machine} ${line.subject} ${line.from}→${line.to}"
            is kp.model.Delivered -> "delivered" + (line.woke?.let { " woke $it" } ?: "")
            is kp.model.Refused -> "refused ${line.rule}"
            else -> (line as? kp.model.Record)?.event ?: "?"
        }

        /**
         * A tmux of the specs' own, beside its log: it notes each call, and keeps the windows of one session in a file —
         * `-V`, `has-session`, `new-session`, `new-window`, `list-windows`, `kill-window`, as the panes call them.
         */
        private fun writeFakeTmux(file: Path) {
            file.parent.createDirectories()
            file.writeText(
                """
                #!/bin/sh
                here=${'$'}(dirname "${'$'}0")
                echo "${'$'}*" >> "${'$'}here/tmux.log"
                windows="${'$'}here/tmux.windows"
                case "${'$'}1" in
                  -V) echo "tmux 3.4" ;;
                  has-session) [ -f "${'$'}windows" ] ;;
                  new-session) echo "${'$'}6" > "${'$'}windows" ;;
                  new-window) echo "${'$'}6" >> "${'$'}windows" ;;
                  list-windows) [ -f "${'$'}windows" ] && cat "${'$'}windows" ;;
                  kill-window) w=${'$'}{3#*:}; grep -vx "${'$'}w" "${'$'}windows" > "${'$'}windows.next"; mv "${'$'}windows.next" "${'$'}windows" ;;
                esac
                """.trimIndent() + "\n",
            )
            file.toFile().setExecutable(true)
        }
    }
}

/**
 * A keporkak checkout of the specs' own: the checkout's rules, robots, personas, renders, skills, profiles and hooks,
 * and its pool files — the roster as [roster] makes it from the checkout's.
 */
fun fixtureRoot(roster: (String) -> String): Path {
    val root = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "root-")
    for (folder in listOf("rules", "robots", "agents", "renders", "skills", "docs", "hooks", "seat", "office")) Files.createSymbolicLink(root.resolve(folder), checkout.resolve(folder))
    val pool = root.resolve("pool").createDirectories()
    for (file in listOf("offices.yaml", "budgets.yaml")) Files.copy(checkout.resolve("pool/$file"), pool.resolve(file))
    pool.resolve("roster.yaml").writeText(roster(Files.readString(checkout.resolve("pool/roster.yaml"))))
    return root
}
