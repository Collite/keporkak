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
    /** On the office, the steps its log already held when the rehearsal started (S3.5 T4): taken up, not sent again. */
    private val held: Map<Int, Pair<String?, String?>> = emptyMap(),
    /** On the office, the walk's seats the log held live when the rehearsal started, and how many lines the log counted then (S3.5 T4). */
    private val liveAtStart: Set<String> = emptySet(),
    private val linesAtStart: Int = 0,
    /** The step after which the play stops, as a stop there would — a spec's cut (S3.5 T1); null: it plays to the walk's end. */
    private val cutAfter: Int? = null,
) : AutoCloseable {
    private val over = CountDownLatch(1)
    /** The player of the walk, once it plays: whose step it waits for ([Player.awaiting]). */
    @Volatile var playing: Player? = null
        private set
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
            out("rehearsing ${script.walk.name}: stage ${script.stageId(script.walk.stage)} on ${office.project}" + if (script.onTheOffice) " — the office's own log, facts and operations" else "")
            val player = script.player(office, AtTheCommandLine(), out).also { playing = it }
            if (held.isNotEmpty() || liveAtStart.isNotEmpty()) {
                startedAgain()
                // What the start itself wrote — the deaths of the seats that died with the last play — may hold a step too.
                when (val course = OnTheOffice.course(script, office.counted())) {
                    is OnTheOffice.Course.Otherwise -> throw WalkFailure("the office's log went another way than walk ${script.walk.name} at ${course.why}")
                    is OnTheOffice.Course.Kept -> if (course.steps.isNotEmpty()) {
                        out("the log holds the walk's first ${course.steps.size} steps: they are checked, not sent; the walk plays on from step ${course.steps.size + 1}")
                        player.resume(course.steps)
                    }
                }
            }
            if (cutAfter != null) {
                player.throughStep(cutAfter)
                // A stop after the step and what it set going: no operation is in flight when it comes.
                until { inFlight().isEmpty() }
                out("the play stops after step $cutAfter, as a stop there would" + inFlight().let { if (it.isEmpty()) "" else " — in flight: ${it.joinToString()}" })
                return
            }
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

    /**
     * A start on a log that holds the walk (S3.5 T4) reports each seat the log holds live gone — its process died with the
     * rehearsal — and the rules pause the stage until it is re-seated, as after any death. The walk goes on as the TeamLead
     * would, once that is done: each of [liveAtStart] reported gone, no operation in flight and the stage not paused — at
     * most [CUT_MILLIS]; then it goes on as it is, and its next step says what it finds.
     */
    private fun startedAgain() {
        val stage = script.stageId(script.walk.stage)
        fun reported(): Boolean = office.counted().drop(linesAtStart).filterIsInstance<kp.model.Fact>().filter { it.fact in Script.DEATHS_OF_A_SEAT }.map { it.subject }.containsAll(liveAtStart)
        office.settle()
        until { reported() && inFlight().isEmpty() && office.state.stage(stage) != PAUSED }
        out("started again: the stage is ${office.state.stage(stage)}" + inFlight().let { if (it.isEmpty()) "" else "; in flight: ${it.joinToString()}" })
    }

    /** The operations requested on the log with no result: `<op> for <cause>`. */
    private fun inFlight(): List<String> {
        val ops = office.counted().filterIsInstance<kp.model.Op>()
        return ops.filter { it.phase == REQUESTED && ops.none { ended -> ended.op == it.op && ended.cause == it.cause && ended.phase != REQUESTED } }
            .map { "${it.op} for ${it.cause}" }
    }

    /**
     * Waits on the office's changes until [done] holds — what the rehearsal's own start or cut set going has ended — at most
     * [CUT_MILLIS]: a bound on the rehearsal's own waiting, never a deadline of the rules; past it the walk goes on as it is.
     */
    private fun until(done: () -> Boolean) {
        var version = office.changes.version()
        val end = System.nanoTime() + CUT_MILLIS * 1_000_000
        while (!done() && System.nanoTime() < end) version = office.changes.await(version, WAIT_POLL)
    }

    companion object {
        private const val JOIN_MILLIS = 10_000L
        private const val CUT_MILLIS = 60_000L
        private const val PAUSED = "paused"
        private const val REQUESTED = "requested"
        /** A seat's states in which the daemon holds its process (contracts.md §4.1, the seat machine). */
        private val LIVE = setOf("ready", "busy", "idle")
        private const val WAIT_POLL = 500L
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

        /**
         * Rehearses [walk] on the office (contracts.md §12, Amendment 46; S3.5): where the service would run — the office
         * [office]'s project checkout [project] and its log, its main checkouts, and GitHub at [api] — with the seats of
         * [seating] as the service starts seats, its world and its performers the service's, the seat API on [listen] (the
         * office's `listen.mcp` unless the caller says), and the daemon answering `kp` on [socket]. The walk gives only
         * what a role without a seat would send and write; a walk that gives a fact or an operation's result is refused. It
         * starts only when the service is stopped, the walk's stage has a list, the project checkout and every main checkout
         * are clean, and Bora is present; otherwise nothing is started and [OnTheOffice.Refusal] says what was found. On a
         * log that already holds the walk's first steps it plays on from the first it does not hold; on one that went
         * another way it is refused, naming the step. With [cutAfter] the play stops after that step, as a stop there would.
         */
        fun onTheOffice(
            root: Path, walk: Path, office: String, project: Path, socket: Path, peers: PeerReader, seating: LiveSeating,
            api: java.net.URI = kp.ops.GitHubApps.GITHUB, listen: String? = null, clock: Clock = serviceClock, cutAfter: Int? = null, out: (String) -> Unit,
        ): Rehearsal {
            val played = Walk.read(walk)
            OnTheOffice.supplied(played).takeIf { it.isNotEmpty() }?.let { supplied ->
                throw OnTheOffice.Refusal("walk ${played.name} gives what a rehearsal on the office reads from the office (Amendment 46): ${supplied.joinToString("; ")}")
            }
            if (played.claudeSeats == null) throw OnTheOffice.Refusal("walk ${played.name} names no claude_seats: on the office a seat's step is the seat's own, waited for and never sent")
            if (!Script.STAGE_ID.matches(played.stage)) throw OnTheOffice.Refusal("walk ${played.name} names its stage ${played.stage}: on the office a walk names a stage of the plan by its whole id")
            val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
            val inForce = rules ?: throw OnTheOffice.Refusal("the rules are not valid: ${loaded.problems.joinToString("; ")}")
            val script = Script(played, played.stage.substringBefore('/'), onTheOffice = true)
            val lines = OnTheOffice.lines(project, office)
            val course = OnTheOffice.course(script, lines)
            if (course is OnTheOffice.Course.Otherwise) throw OnTheOffice.Refusal("the office's log went another way than walk ${played.name} at ${course.why}")
            val held = (course as OnTheOffice.Course.Kept).steps
            val folded = kp.engine.fold(lines)
            val live = played.claudeSeats!!.seats.filter { seat -> folded.machine("seat", seat)?.state in LIVE }.toSet()
            val missing = OnTheOffice.missing(inForce, office, project, socket, played.stage, again = held.isNotEmpty() || lines.any { it is kp.model.Message && it.stage?.value == played.stage }, walk = played)
            if (missing.isNotEmpty()) throw OnTheOffice.Refusal("nothing is started: " + missing.joinToString("; "))
            val (seated, agent, world) = seating.onTheOffice(root, script, project, office, api, listen, clock, out)
            val daemon = try {
                Daemon.start(seated.office, world.view, socket, peers, beside = listOf(seated))
            } catch (error: Exception) {
                seated.close()
                seated.office.close()
                throw error
            }
            return Rehearsal(script, daemon, out, seated, agent as? FakeAgents, held, live, lines.size, cutAfter).also { it.player.start() }
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
            override fun performs(operation: String) = made.performs(operation)
            override fun die(seat: String) = die(seat, made, agent)
            override val own: OwnSteps? get() = own
        }
        if (seeding) seeds.keys.sorted().forEach { seat -> seated.seats.seed(seat)?.let { out("seat $seat did not start: $it") } }
        seated.start(watchEvery ?: seated.watchEvery())
        own?.attach(seated.office)
        return seated to agent
    }

    /**
     * The walk's seats on the office (contracts.md §12, Amendment 46; S3.5): the service's daemon as `kp-daemon run` makes it
     * — its world the project's, git's, GitHub's at [api] and the pool's, its performers the office's and those on GitHub,
     * the seats in front of the driver with the office's roots and secrets, the seat API on [listen] or the office's
     * `listen.mcp` — but its agent this seating's, no report of the four facts (Amendment 48), and nothing seeded: the walk
     * asks for its seats and starts them as the rules do. The Claude seats play their own steps ([SeatsAtPlay]); the
     * record goes to [evidence], else the office's scratch root, `rehearsals/`.
     */
    internal fun onTheOffice(root: Path, script: Script, project: Path, office: String, api: java.net.URI, listen: String?, clock: Clock, out: (String) -> Unit): Triple<Seated, Agent, ServiceWorld> {
        val rules = InForceRules.of(RuleFiles(root).read()).first ?: throw OnTheOffice.Refusal("the rules are not valid")
        val entry = rules.ruleSet.pool.offices[office] ?: throw OnTheOffice.Refusal("pool/offices.yaml names no office '$office'")
        val roots = Roots.of(entry.roots)
        if (this is FakeSeating) given(script.walk)
        val agent = agent(root, roots.scratch.resolve("rehearsals").resolve("${script.walk.name}-agents"))
        val performers = OfficeOperations(root, office, project, api = api)
        val github = GitHubOperations.of(office, performers, api)
        val pool = kp.daemon.seats.PoolFacts(office)
        val world = ServiceWorld(ProjectWorld.of(rules, office, project), GitHubWorld.of(office, project, api), pool)
        val own = script.walk.claudeSeats?.let { claude ->
            SeatsAtPlay(claude, roots.seats.resolve("_transcripts"), evidence ?: roots.scratch.resolve("rehearsals"), roots.secrets.resolve("claude-token"),
                script.walk.file?.let { file -> if (file.startsWith(root)) root.relativize(file) else file } ?: Path.of(script.walk.name), clock, onTheOffice = true)
        }
        var driving: Seats? = null
        lateinit var made: Seats
        val seated = Seated.start(office, project, root, world, github, clock, listen ?: entry.listen["mcp"] ?: throw OnTheOffice.Refusal("pool/offices.yaml gives office '$office' no listen.mcp"),
            folded = { folded -> world.reading(folded); pool.reading(folded, driving); github.reading(folded); performers.reading(folded) }) { starting ->
            Seats(root, office, project, roots, agent, clock, Panes(tmux, panes, out), out, starting, turns = own, github = api) { listener, time ->
                ClaudeAdapter.driver(roots.secrets.resolve("claude-token"), roots.seats, time, CompiledAnswerer(roots.seats), listener)
            }.also { made = it; driving = it; own?.driven = it }
        }
        own?.attach(seated.office)
        script.live = object : LiveSeats {
            override fun performs(operation: String) = made.performs(operation)
            override fun die(seat: String) = die(seat, made, agent)
            override val own: OwnSteps? get() = own
        }
        try {
            seated.start(watchEvery ?: seated.watchEvery())
        } catch (error: Exception) {
            seated.close()
            seated.office.close()
            throw error
        }
        own?.attach(seated.office)
        return Triple(seated, agent, world)
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

    /** The walk whose folder may give its seats' scripts (`<walk>/fake/<seat>.json`). */
    internal fun given(walk: Walk) {
        this.walk = walk
    }

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
