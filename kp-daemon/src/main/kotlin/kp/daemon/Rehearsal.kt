package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.daemon.seats.Agent
import kp.daemon.seats.ClaudeAgent
import kp.daemon.seats.FakeAgents
import kp.daemon.seats.Panes
import kp.daemon.seats.Roots
import kp.daemon.seats.Seated
import kp.daemon.seats.Seats
import kp.driver.claude.ClaudeAdapter
import kp.driver.home.CompiledAnswerer
import kp.mailroom.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kp.engine.Datum
import kp.engine.FactRef
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `just rehearse <walk>` (architecture.md §7): the daemon on a throwaway project, the walk played through it by the
 * harness ([Script]) — its seats scripted, its facts tabled, its operations ended as the walk says — pausing where the
 * walk needs Bora, who answers with the real `kp` on the daemon's socket. The robots are the daemon's. A step that does
 * not do what the walk expects ends the playing, and says why; the daemon keeps answering `kp` until the rehearsal is
 * closed. With fake seats (`--seats fake`, S2.4) the walk's seats are processes — S2.1's fake agent — driven by the
 * daemon's own seats behind the seat API: woken, re-seated and watched as real seats will be (S2.5).
 */
class Rehearsal private constructor(
    private val script: Script, private val daemon: Daemon, private val out: (String) -> Unit, val seated: Seated? = null, val fake: FakeAgents? = null,
) : AutoCloseable {
    private val over = CountDownLatch(1)
    /** Whether the walk was played to its end; null while it is played. */
    @Volatile var passed: Boolean? = null
        private set

    /** Blocks until the walk has been played — to its end or to the step that stopped it — at most [millis]; whether it was. */
    fun awaitPlayed(millis: Long = Long.MAX_VALUE): Boolean = over.await(millis, TimeUnit.MILLISECONDS)
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
            val player = script.player(office, AtTheCommandLine(), out)
            try {
                player.rest()
                passed = true
                out("the walk is done: the stage is ${office.state.stage(script.stageId(script.walk.stage))}." +
                    if (player.evidence == null) " The daemon answers kp until the rehearsal is stopped (Ctrl-C)." else "")
            } finally {
                player.evidence?.let { out("the evidence: $it") }
            }
        } catch (_: InterruptedException) {
            // closed while waiting for Bora
        } catch (failure: WalkFailure) {
            passed = false
            out("the walk stops here: ${failure.message}")
        } catch (error: Exception) {
            passed = false
            if (!office.closed) out("the rehearsal stopped: $error")
        } finally {
            over.countDown()
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
         * Bora. [out] hears what happens. [clock] is the wall clock unless a spec moves it. With [seating], the walk's seats
         * are processes: fake agents ([FakeSeating]) or Claude seats ([RealSeating]).
         */
        fun start(
            root: Path, walk: Path, project: Path, office: String, socket: Path, peers: PeerReader,
            clock: Clock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }, seating: LiveSeating? = null, out: (String) -> Unit,
        ): Rehearsal {
            val script = Script(Walk.read(walk))
            script.seed(project, office, clock)
            if (seating == null) {
                val started = Office.start(office, project, root, script.facts, script.operations, script.seats, clock)
                val daemon = Daemon.start(started, script.facts, socket, peers)
                return Rehearsal(script, daemon, out).also { it.player.start() }
            }
            val (seated, agent) = if (seating is FakeSeating) seating.seat(root, script, project, office, clock, out) else seating.seated(root, script, project, office, clock, out)
            val daemon = Daemon.start(seated.office, script.facts, socket, peers, beside = listOf(seated))
            return Rehearsal(script, daemon, out, seated, agent as? FakeAgents).also { it.player.start() }
        }
    }
}

/**
 * How a rehearsal seats its walk's seats as processes (S2.4, S2.5): the daemon's own seats in front of them — the seat
 * API on a free loopback port, the roots under the throwaway project's `office/`, a worktree, a list and STATUS for each
 * of the walk's stages (what the walk's folder holds is copied into the project first) — a coder's home lets it write its
 * list, so a stage without one seats no coder — panes in the tmux session [panes] by [tmux]. The walk's seats start as
 * the walk starts them — ready, seeded — at the level the walk's first request for their role names; a step that reports
 * a seat's death ends its process, and a step that ends a seat operation waits for the seats' result. A rehearsal's
 * stages belong to no effort of the plan: their seats' homes are built with [profile]. When the walk names Claude seats,
 * their steps are their own ([OwnSteps]) and the play's evidence goes to [evidence] — by default the office's scratch
 * root, `rehearsals/`.
 */
sealed class LiveSeating(val tmux: String, val panes: String, val profile: String, val watchEvery: java.time.Duration?, val evidence: Path?, val github: java.net.URI? = null) {
    /** The agent the seats run; [home] is the throwaway project's `office/`. */
    internal abstract fun agent(root: Path, home: Path): Agent

    /** The folder whose `claude-token` the driver reads for the seats. */
    internal abstract fun secrets(root: Path, home: Path, office: String): Path

    /** [seat]'s process ends from outside, as a crash would; false when it has none. */
    internal abstract fun die(seat: String, seats: Seats, agent: Agent): Boolean

    /** The seats of [script]'s walk on [project]; with [seeding], its seats are started as it starts them — not when a daemon starts again. */
    internal fun seated(root: Path, script: Script, project: Path, office: String, clock: Clock, out: (String) -> Unit, seeding: Boolean = true): Pair<Seated, Agent> {
        val home = project.resolve("office")
        script.walk.fixtures?.takeIf { seeding }?.let { folder ->
            folder.listDirectoryEntries().filter { it.isRegularFile() }.forEach { file -> Files.copy(file, project.resolve(file.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
        }
        val roots = Roots(home.resolve("worktrees"), home.resolve("scratch"), home.resolve("seats"), secrets(root, home, office))
        script.stages.values.forEach { stage ->
            Seats.worktree(stage)?.let { roots.worktrees.resolve(it).createDirectories() }
            LIST.matchEntire(stage)?.destructured?.let { (phase, number) ->
                val list = project.resolve("tasks-p$phase-s$number-${script.walk.name.lowercase()}.md")
                if (project.listDirectoryEntries("tasks-p$phase-s$number-*.md").isEmpty()) list.writeText("# ${script.walk.name}: the list of $stage, as a rehearsal has it\n")
            }
        }
        project.resolve("STATUS.md").takeIf { !it.exists() }?.writeText("# STATUS, as a rehearsal has it\n")
        val agent = agent(root, home)
        // A seat's stage: the one whose table names it for its role — as the walk's seed places a stage's seat — else the walk's.
        val placed = script.walk.stageNames.flatMap { name ->
            script.walk.seats.values.distinct().mapNotNull { role ->
                (script.facts.table(name).observe(FactRef(SEAT_NAME, mapOf(OF to Datum.Text(role))), script.stageId(name)) as? Datum.Text)?.value?.let { it to script.stageId(name) }
            }
        }.reversed().toMap()
        val seeds = script.walk.seats.mapValues { (seat, role) ->
            val level = script.walk.steps.firstNotNullOfOrNull { step -> step.fields(ARGS).takeIf { (it[ROLE] as? Datum.Text)?.value == role }?.get(LEVEL) as? Datum.Text }
            Seats.Seed(role, placed[seat] ?: script.stageId(script.walk.stage), level?.value)
        }
        val own = script.walk.claudeSeats?.let { claude ->
            SeatsAtPlay(claude, roots.seats.resolve("_transcripts"), evidence ?: scratchOf(root, office).resolve("rehearsals"), roots.secrets.resolve("claude-token"),
                script.walk.file?.let { file -> if (file.startsWith(root)) root.relativize(file) else file } ?: Path.of(script.walk.name), clock)
        }
        lateinit var made: Seats
        val seated = Seated.start(office, project, root, script.facts, script.operations, clock, LOOPBACK) { rules ->
            Seats(root, office, project, roots, agent, clock, Panes(tmux, panes, out), out, rules, seeds, profileOf = { profile }, moreEfforts = mapOf(script.effort to "."), turns = own, github = github) { listener, time ->
                ClaudeAdapter.driver(roots.secrets.resolve("claude-token"), roots.seats, time, CompiledAnswerer(roots.seats), listener)
            }.also { made = it; own?.driven = it }
        }
        own?.attach(seated.office)
        script.live = object : LiveSeats {
            override fun performs(operation: String) = operation in Seats.OPERATIONS
            override fun die(seat: String) = die(seat, made, agent)
            override val own: OwnSteps? get() = own
        }
        if (seeding) seeds.keys.sorted().forEach { seat -> seated.seats.seed(seat)?.let { out("seat $seat did not start: $it") } }
        seated.start(watchEvery ?: seated.watchEvery())
        own?.attach(seated.office)
        return seated to agent
    }

    /** The office's scratch root (contracts.md §9): where a rehearsal's evidence goes unless the caller says. */
    private fun scratchOf(root: Path, office: String): Path {
        val rules = InForceRules.of(RuleFiles(root).read()).first ?: error("the rules are not valid")
        return Roots.of(rules.ruleSet.pool.offices[office]?.roots ?: error("pool/offices.yaml names no office '$office'")).scratch
    }

    companion object {
        const val LOOPBACK = "127.0.0.1:0"
        private const val ARGS = "args"
        private const val ROLE = "role"
        private const val LEVEL = "level"
        private const val SEAT_NAME = "seat.name"
        private val LIST = Regex("^[A-Z][A-Z0-9-]*/P([0-9]+)/S([0-9]+\\.[0-9]+)$")
        private const val OF = "of"
    }
}

/**
 * The walk's seats as S2.1's fake agent (S2.4, `just rehearse <walk> --seats fake`): run from [classpath] — the driver's
 * test fixtures. A seat follows [scripts]' entry for it, else the walk's own (`<walk>/fake/<seat>.json` beside the walk),
 * else the default — each with its `record`, the stop hook's probe and the roster's efforts unless it says otherwise.
 */
class FakeSeating(
    val classpath: String, tmux: String = "tmux", panes: String = Panes.SESSION, profile: String = "keporkak", watchEvery: java.time.Duration? = null,
    val scripts: Map<String, String> = emptyMap(), evidence: Path? = null, github: java.net.URI? = null,
) : LiveSeating(tmux, panes, profile, watchEvery, evidence, github) {
    @Volatile private var walk: Walk? = null

    /** The seats of [script]'s walk on [project], as [LiveSeating.seated] seats them, with their fake agents. */
    internal fun seat(root: Path, script: Script, project: Path, office: String, clock: Clock, out: (String) -> Unit, seeding: Boolean = true): Pair<Seated, FakeAgents> {
        walk = script.walk
        val (seated, agent) = seated(root, script, project, office, clock, out, seeding)
        return seated to agent as FakeAgents
    }

    override fun agent(root: Path, home: Path): Agent {
        val agents = FakeAgents(classpath, home.resolve("fake"))
        val folder = home.resolve("fake").createDirectories()
        val given = walk?.let { walk -> walk.seats.keys.associateWith { seat -> walk.fixtures?.resolve("fake/$seat.json")?.takeIf { it.exists() }?.readText() } }.orEmpty()
        (given.filterValues { it != null }.mapValues { it.value!! } + scripts).forEach { (seat, text) ->
            val script = Json.parseToJsonElement(text) as JsonObject
            val defaults = mapOf("probe" to JsonPrimitive(true), "efforts" to Json.parseToJsonElement("""["low", "medium", "high", "xhigh", "max"]"""))
            folder.resolve("$seat.json").writeText(JsonObject(defaults + script + ("record" to JsonPrimitive(agents.record(seat).toString()))).toString())
        }
        return agents
    }

    override fun secrets(root: Path, home: Path, office: String): Path = FakeAgents.secrets(home.resolve("secrets"))

    override fun die(seat: String, seats: Seats, agent: Agent): Boolean = (agent as FakeAgents).die(seat)
}

/**
 * The walk's seats as Claude seats (S2.5, `just rehearse <walk> --seats real`; contracts.md §12, Amendment 27): the pinned
 * adapter `just adapter` installed under the checkout's `office/adapter`, the office's own secrets — the seats' token is
 * Bora's, read into the agent's environment by the driver and never written anywhere. A step that reports a seat gone ends
 * its process by force through the driver: the fact is the driver's own observation.
 */
class RealSeating(tmux: String = "tmux", panes: String = Panes.SESSION, profile: String = "keporkak", evidence: Path? = null) :
    LiveSeating(tmux, panes, profile, null, evidence) {
    override fun agent(root: Path, home: Path): Agent = ClaudeAgent(root.resolve("office/adapter"))

    override fun secrets(root: Path, home: Path, office: String): Path {
        val rules = InForceRules.of(RuleFiles(root).read()).first ?: error("the rules are not valid")
        return Roots.of(rules.ruleSet.pool.offices[office]?.roots ?: error("pool/offices.yaml names no office '$office'")).secrets
    }

    override fun die(seat: String, seats: Seats, agent: Agent): Boolean = seats.end(seat)
}
