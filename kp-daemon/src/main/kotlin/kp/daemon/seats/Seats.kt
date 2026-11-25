package kp.daemon.seats

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kp.daemon.InForceRules
import kp.daemon.Office
import kp.driver.Cancel
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.ObservedFact
import kp.driver.Outcome
import kp.driver.SeatFacts
import kp.driver.SessionSpec
import kp.driver.SessionToken
import kp.driver.UsageWeights
import kp.driver.UsageWindow
import kp.driver.home.ConfigHomes
import kp.driver.home.PermissionDefaults
import kp.driver.home.Places
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.model.Message
import kp.model.SeatId
import kp.rules.Line
import kp.rules.RuleSet
import kp.seatapi.Binding
import kp.seatapi.Tokens
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The daemon's seats (architecture.md §2, §5; contracts.md §4.4, Amendment 22): it performs the seat operations through
 * the driver, prompts an idle seat with a message delivered to it, reports what the driver observes to the mailroom as
 * facts, and keeps a pane per seat. It decides nothing — when an operation is asked for is the rules', a limit is the
 * robots' — and it compares no observation with anything: it reports what it was asked for, or what happened.
 *
 * A seat is idle when it has a live session and no turn in flight; its wake is `ok` once the prompt is on its way. A
 * turn that ends leaves the seat idle, and the mailroom wakes it with its oldest unread message. Gone and closed are
 * each reported when they happen, a retired seat's too: the daemon holds no report back and joins none — that one death
 * is one event is the rules' to say (contracts.md §4.4, Amendment 28). The silence, an open tool call, the context used
 * and the usage of every seat with a turn in flight are reported every [watchEvery]. At a start, a seat the state holds
 * live and the driver does not is reported gone.
 */
class Seats(
    /** The keporkak checkout: the renders, personas, skills, packaging data, profiles and hooks a home is built from. */
    private val keporkak: Path,
    private val officeName: String,
    /** The project checkout the daemon writes: where a stage's list and STATUS are. */
    private val project: Path,
    private val roots: Roots,
    private val agent: Agent,
    private val clock: Clock,
    val panes: Panes,
    private val say: (String) -> Unit,
    /** The rules in force while the office starts: what the roster, the offices file and the personas are read from before [attach]. */
    private val startingRules: () -> InForceRules?,
    /** The seats a rehearsal seeds: their role and stage, which no `seat.start` on the log names. */
    private val seeds: Map<String, Seed> = emptyMap(),
    /** The profile a seat's home is built with, by its stage's effort: the one in `docs/profiles` that names it, unless the caller says. */
    private val profileOf: ((String?) -> String?)? = null,
    /** Efforts' folders in the project beside those of `pool/offices.yaml`: a rehearsal's own effort's. */
    private val moreEfforts: Map<String, String> = emptyMap(),
    /** What hears each turn's end: a rehearsal whose seats play their own steps (S2.5). */
    private val turns: Turns? = null,
    /** GitHub's REST root, when the seats perform `token.mint` and answer a seat's ask for a GitHub token (S3.7): null, they do neither. */
    private val github: java.net.URI? = null,
    driver: (Listener, java.time.Clock) -> Driver,
) : Deliveries, AutoCloseable {
    /** A seat a rehearsal starts outside the rules, as an earlier part of the log would have left it: its role, stage and level. */
    data class Seed(val role: String, val stage: String?, val level: String? = null)

    /**
     * What hears that a seat's turn has ended (S2.5), before the seat is idle again: the seat, the message that woke it for
     * the turn — null for a re-seat's replay and a compaction — and the turn's `seat.usage` as the driver observes it then;
     * null when the turn ended without the agent's answer or reported no usage. [parts] is that usage by its parts, as the
     * agent reported them (S3.6 T6).
     */
    fun interface Turns {
        fun ended(seat: String, cause: String?, usage: Long?, parts: kp.driver.Usage?)
    }

    /** The registry of live session tokens the seat API reads (contracts.md §5). */
    val tokens = Tokens()
    /** Where the operations run, each on a thread of its own: a start takes seconds, and the mailroom does not wait for it. */
    private val work = Executors.newCachedThreadPool { Thread(it, "kp-seats-work").apply { isDaemon = true } }
    /** Where everything that reaches the mailroom from the driver goes, in order: a driver's thread never waits for the mailroom. */
    private val reporter = Executors.newSingleThreadExecutor { Thread(it, "kp-seats-report").apply { isDaemon = true } }
    private val later: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "kp-seats-watch").apply { isDaemon = true } }
    private val held = ConcurrentHashMap<String, Held>()
    private val minting = ConcurrentHashMap<String, Binding>()
    /** The SHA-256 of every session token minted here: what lets a rehearsal's evidence find one without holding it (S2.5). */
    private val minted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val compacts = ConcurrentHashMap<String, MutableList<(OperationResult) -> Unit>>()
    @Volatile private var office: Office? = null
    /** What the log knows, from the office this reports to: a stage's `worktree.create` record among it (Amendment 40). */
    private val log = kp.daemon.OfficeLog()
    @Volatile private var hookUrl: String? = null
    @Volatile private var listen: String? = null
    @Volatile private var closing = false
    private val time = object : java.time.Clock() {
        override fun instant(): Instant = clock.now()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): java.time.Clock = this
    }
    private val listener = object : Listener {
        override fun tokenMinted(seat: SeatId, token: SessionToken) {
            minted += sha256(token.value)
            minting[seat.value]?.let { tokens.register(token.value, it) }
        }

        override fun tokenDead(seat: SeatId, token: SessionToken) = tokens.revoke(token.value)

        override fun observed(fact: ObservedFact) = this@Seats.observed(fact)

        override fun reported(seat: SeatId, report: kp.driver.SubscriptionReport) = this@Seats.reported(seat.value, report)
    }
    /** The vendor's last report of each window of each subscription, through any seat: the last stands for all (Amendment 44). */
    private val subscriptions = ConcurrentHashMap<Pair<String, String>, kp.facts.VendorReport>()
    private val driver: Driver = driver(listener, time)

    /**
     * A seat's GitHub identity (contracts.md §4.4, Amendment 35), when the seats have [github]: `token.mint` performed for a
     * seat, and the seat API's answer to its ask — with the Apps' keys in the office's secrets folder. A token handed to a
     * seat is hidden in its transcript by its prefix, as every installation token is: none is kept for it (S3.11).
     */
    val identity: GitHubIdentity? = github?.let { api ->
        GitHubIdentity(officeName, ::inForce, log, roots.secrets.resolve("apps"), api, say)
    }

    /** What hears how a turn ended. */
    private fun interface Ended {
        fun turn(end: Outcome<*>)
    }

    /** One seat's session as the daemon holds it. */
    private class Held(val binding: Binding, val session: String) {
        @Volatile var busy = false
        @Volatile var gone = false
        @Volatile var ending = false
    }

    /**
     * The office whose mailroom this reports to, and the seat API's address — `host:port` — that each session is given
     * for its messaging server and its hooks (contracts.md §10, §11).
     */
    fun attach(office: Office, listen: String) {
        this.office = office
        log.office = office
        this.listen = listen
        this.hookUrl = "http://$listen"
    }

    // ---- delivery (contracts.md §4.4, Amendment 22)

    override fun idle(seat: String): Boolean = held[seat]?.let { !it.busy && !it.gone && !it.ending } ?: false

    override fun wake(seat: String, message: Message): Boolean = prompt(seat, message) is OperationResult.Ok

    override fun live(): Set<String> = held.filterValues { !it.gone }.keys

    /** [seat] prompted with [message]: `ok` once the prompt is on its way; the seat is busy until its turn ends. */
    override fun prompt(seat: String, message: Message): OperationResult {
        val session = held[seat] ?: return OperationResult.Failed("seat $seat has no session")
        synchronized(session) {
            if (session.busy || session.gone || session.ending) return OperationResult.Failed("seat $seat is not idle: a turn is in flight")
            session.busy = true
        }
        return turn(seat, session, mail(message), cause = message.id.value)
    }

    /**
     * One turn of [seat] on [text], `ok` once the prompt is sent. When the turn ends the seat is idle again: a compaction
     * asked for meanwhile is done first, then the mailroom is told. [then] hears how the turn ended; [onSent] is queued
     * for the mailroom when the prompt is sent — before anything its end queues.
     */
    private fun turn(seat: String, session: Held, text: String, then: Ended = Ended {}, onSent: (() -> Unit)? = null, cause: String? = null): OperationResult {
        val sent = CompletableFuture<String?>()
        later {
            driver.prompt(SeatId(seat), text) {
                onSent?.let { task -> reporter.execute { task() } }
                sent.complete(null)
            }
        }.whenComplete { end, error ->
            val outcome: Outcome<*> = end ?: Outcome.Failed("the turn failed: ${error?.message ?: "?"}")
            // A prompt never sent began no turn (contracts.md §4.4, Amendment 30): its caller hears it failed, and the seat
            // is idle again without being said to be — it is not woken again for that message until another arrival.
            val unsent = sent.complete((outcome as? Outcome.Failed)?.reason ?: "the turn ended before the prompt was sent")
            reporter.execute {
                if (!unsent) {
                    then.turn(outcome)
                    recorded(seat, session, outcome)
                    turns?.ended(seat, cause, (outcome as? Outcome.Ok<*>)?.let { used(seat) }, ((outcome as? Outcome.Ok<*>)?.value as? kp.driver.TurnEnd)?.usage)
                }
                ended(seat, session, turned = !unsent)
            }
        }
        val failed = try {
            sent.get(SEND_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            "the prompt was not sent in ${SEND_WAIT_SECONDS}s"
        }
        return if (failed == null) OperationResult.Ok() else OperationResult.Failed(failed)
    }

    /**
     * A turn ended — or, not [turned], a prompt was never sent: a compaction waiting for it, then the seat is idle again.
     * Only a turn's end tells the mailroom, which wakes the seat with its oldest unread message (contracts.md §4.4).
     */
    private fun ended(seat: String, session: Held, turned: Boolean = true) {
        val waiting = compacts.remove(seat)
        if (waiting != null && !session.gone && !closing) {
            work.execute { compact(seat, session) { result -> waiting.forEach { it(result) } } }
            return
        }
        waiting?.forEach { it(OperationResult.Failed("seat $seat has no session")) }
        session.busy = false
        if (turned && !closing) office?.idle(seat)
    }

    /**
     * The one record a turn's end leaves (contracts.md §4.4, Amendment 44): `seat.usage` of the window `turn` — the turn's
     * count by the pool's `usage.weights`, with its four parts, how it ended and its seat's subscription in the evidence. A
     * turn that ended without the agent's answer — a cancel, a kill, its process gone — is marked partial, with what was
     * observed of it: a cancelled turn's parts as the agent gave them, and of one with no answer nothing, so zero. The
     * watch's reports of a turn in flight are no turn's end. None is written once the daemon is stopping.
     */
    private fun recorded(seat: String, session: Held, outcome: Outcome<*>) {
        val office = office?.takeUnless { closing } ?: return
        val end = (outcome as? Outcome.Ok<*>)?.value as? kp.driver.TurnEnd
        val weights = weights()
        val count = weights?.count(end?.usage)
        val how = when {
            end == null -> "no answer, partial — ${(outcome as? Outcome.Failed)?.reason}"
            end.stopReason == CANCELLED -> "$CANCELLED, partial"
            else -> end.stopReason
        }
        val parts = if (end == null) "nothing of its parts was observed" else UsageWeights.parts(end.usage)
        val counted = weights?.let { "weighted $count by $it" } ?: "not counted: no weights are in force"
        val evidence = "$TURN_ENDED$how · $parts · $counted · subscription ${subscription(session.binding)}"
        office.recorded(FactRef(SeatFacts.USAGE, mapOf(SEAT to Datum.Text(seat), WINDOW to Datum.Text(UsageWindow.TURN.word))),
            count?.let { Datum.of(it) } ?: Datum.Null, session.binding.stage, evidence)
    }

    /** The weights in force (contracts.md §9, `usage.weights`): how a turn's tokens are counted when it ends. */
    private fun weights(): UsageWeights? = rules()?.pool?.budgets?.usage?.weights?.let { UsageWeights(it.input, it.output, it.cacheWrite, it.cacheRead) }

    /** A seat's subscription: the vendor of its line (Amendment 44) — the order line of the model and effort it was started with. */
    private fun subscription(binding: Binding): String =
        rules()?.pool?.roster?.order?.firstOrNull { it.model == binding.model && it.effort == binding.effort }?.vendor ?: "unknown"

    /** The `seat.usage` of [seat]'s last turn, as the driver observes it (contracts.md §4.4): null when it reported none. */
    private fun used(seat: String): Long? =
        ((driver.observe(SeatId(seat), SeatFacts.USAGE, UsageWindow.TURN) as? Outcome.Ok)?.value?.value as? JsonPrimitive)?.content?.toLongOrNull()

    /** A message as the prompt that wakes a seat with it (contracts.md §4.4: the prompt is that message). */
    private fun mail(message: Message): String = "Mail ${message.id.value}: ${message.kind} from ${message.from.value}" +
        (message.stage?.let { " about ${it.value}" } ?: "") + ".\n" + message.json

    // ---- the operations (contracts.md §4.4)

    /**
     * The seat operations performed here; any other — a robot's own seat woken in a mode among them — by [other], which
     * also says what each works on and what one interrupted left (contracts.md §7, Amendment 36).
     */
    fun operations(other: Operations): Operations = object : Operations {
        override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) = perform(request, done, other)

        override fun on(request: OperationRequest): String? = other.on(request)

        override fun left(request: OperationRequest): String? = other.left(request)
    }

    private fun perform(request: OperationRequest, done: (OperationResult) -> Unit, other: Operations) {
        val seat = (request.with.fields[SEAT] as? Datum.Text)?.value
        when {
            seat == null || !performs(request.operation) -> other.perform(request, done)
            closing -> done(OperationResult.Failed("the daemon is stopping"))
            request.operation == TOKEN_MINT -> work.execute { done(mint(seat)) }
            else -> work.execute {
                val result = try {
                    when (request.operation) {
                        START -> start(seat, request)
                        RESEAT -> reseat(seat)
                        CANCEL -> cancel(seat)
                        KILL -> kill(seat)
                        RETIRE -> retire(seat)
                        else -> null.also { compact(seat, done) }
                    }
                } catch (error: Exception) {
                    OperationResult.Failed("${request.operation} of $seat failed: ${error.message ?: error.javaClass.simpleName}")
                }
                result?.let(done)
            }
        }
    }

    /**
     * `seat.start` (contracts.md §4.4, §9, §10): the model and the effort for the role, the six places for the stage, the
     * home built from the persona, the session's token registered with the seat's binding before its process starts,
     * then the driver's start. Its `ok` names the seat and its session.
     */
    private fun start(seat: String, request: OperationRequest): OperationResult {
        val role = (request.with.fields[ROLE] as? Datum.Text)?.value ?: return OperationResult.Failed("seat.start of $seat names no role")
        val stage = office?.state?.delivered?.get(request.cause)?.stage
        val binding = when (val bound = bind(seat, role, stage, request.cause)) {
            is Bound.Ok -> bound.binding
            is Bound.Not -> return OperationResult.Failed(bound.why)
        }
        return open(seat, binding, session = null)
    }

    /**
     * `seat.reseat` (contracts.md §4.4, Amendment 22): the session the log names loaded into a fresh process — or, when it
     * cannot be loaded, a new session started as a start starts one; then one prompt: pointers to the seat's list and to
     * STATUS, and every unread message, which that prompt marks read. Its `ok` names the seat and its session.
     */
    private fun reseat(seat: String): OperationResult {
        val log = office?.let { SeatLog(it.counted()) }
        val known = log?.known?.get(seat)
        val before = held[seat]
        val binding = before?.binding ?: known?.role?.let { role -> (bind(seat, role, known.stage, null) as? Bound.Ok)?.binding }
            ?: seeds[seat]?.let { seed -> (bind(seat, seed.role, seed.stage, null) as? Bound.Ok)?.binding }
            ?: return OperationResult.Failed("the log names no start of seat $seat, nor its role")
        if (before != null && !before.gone) {
            before.ending = true
            blocking { driver.kill(SeatId(seat), KILL_GRACE) }
        }
        val opened = open(seat, binding, session = known?.session ?: before?.session)
        if (opened !is OperationResult.Ok) return opened
        val session = held.getValue(seat).also { it.busy = true }
        val unread = office?.unread(seat).orEmpty()
        // The replayed mail is marked read once the prompt is sent, before its turn's end can wake the seat with any of it.
        val read = CompletableFuture<Unit>()
        val replayed = turn(seat, session, replay(binding, unread), onSent = { runCatching { office?.read(seat, unread.map { it.id.value }) }; read.complete(Unit) })
        if (replayed is OperationResult.Failed) return replayed
        read.get(SEND_WAIT_SECONDS, TimeUnit.SECONDS)
        return opened
    }

    /** The re-seat's one prompt: where the seat's list and STATUS are, and its unread mail. */
    private fun replay(binding: Binding, unread: List<Message>): String {
        val layout = layout(binding.stage)
        return buildString {
            append("You are ${binding.seat}, re-seated: your session was lost and is given back to you. ")
            append("Your list: ${layout.list?.let { "project:${project.relativize(it)}" } ?: "none found for ${binding.stage ?: "your work"}"}. ")
            append("STATUS: ${layout.status?.let { "project:${project.relativize(it)}" } ?: "none found"}. ")
            if (unread.isEmpty()) append("You have no unread mail.") else {
                append("Your unread mail, oldest first:")
                unread.forEach { append("\n").append(mail(it)) }
            }
        }
    }

    /** A session opened for [binding]: loaded from [session] when it can be, otherwise started. */
    private fun open(seat: String, binding: Binding, session: String?): OperationResult {
        val layout = layout(binding.stage)
        val workingDirectory = layout.worktree
        if (!workingDirectory.isDirectory()) return OperationResult.Failed("the working directory $workingDirectory does not exist: creating it is worktree.create's")
        val rules = rules() ?: return OperationResult.Failed("no rules are in force")
        val defaults = when (val read = PermissionDefaults.of(rules.engagement.permissions)) {
            is Outcome.Ok -> read.value
            is Outcome.Failed -> return OperationResult.Failed(read.reason)
        }
        val profile = (profileOf ?: ::profile)(layout.effort) ?: return OperationResult.Failed("no profile in docs/profiles names the effort ${layout.effort}")
        val places = Places(workingDirectory, layout.scratch, project, layout.list, codeRepos(rules), listen)
        val home = when (val built = ConfigHomes(keporkak, roots.seats).build(SeatId(seat), binding.role, profile, places, defaults)) {
            is Outcome.Ok -> built.value
            is Outcome.Failed -> return OperationResult.Failed("the home of $seat cannot be built: ${built.reason}")
        }
        val spec = SessionSpec(SeatId(seat), agent.command(seat, binding.role), workingDirectory, home.path, binding.model, binding.effort, home.servers,
            SeatEnvironment.of(agent.environment(seat), home.path, rules.pool.github?.seats?.get(binding.role)?.app ?: binding.role), hookUrl,
            weights = UsageWeights.Source(::weights))
        minting[seat] = binding
        val started = try {
            val loaded = session?.let { blocking { driver.load(spec, it) } }
            if (loaded is Outcome.Ok) loaded else blocking { driver.start(spec) }
        } finally {
            minting.remove(seat)
        }
        return when (started) {
            is Outcome.Ok -> {
                held[seat] = Held(binding, started.value.sessionId)
                panes.open(seat, roots.seats.resolve("_transcripts/$seat.log"))
                OperationResult.Ok(Datum.Fields(mapOf(SEAT to Datum.Text(seat), SESSION to Datum.Text(started.value.sessionId))))
            }
            is Outcome.Failed -> OperationResult.Failed(started.reason)
        }
    }

    /**
     * `token.mint` (contracts.md §4.4, Amendment 35), the effect of a seat's start: a mint for the seat's role and stage, its
     * token thrown away.
     */
    private fun mint(seat: String): OperationResult {
        val binding = held[seat]?.binding ?: return OperationResult.Failed("seat $seat has no session")
        return try {
            identity!!.mint(binding)
        } catch (error: Exception) {
            OperationResult.Failed("token.mint of $seat failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Whether [operation] is the seats' to perform: a seat operation, and `token.mint` when the seats have a GitHub identity. */
    fun performs(operation: String): Boolean = operation in PERFORMED || (operation == TOKEN_MINT && identity != null)

    /** `seat.cancel` (contracts.md §4.4): the turn in flight asked to end; with none, nothing to cancel, and the result says so. */
    private fun cancel(seat: String): OperationResult = when (val cancelled = blocking { driver.cancel(SeatId(seat)) }) {
        is Outcome.Ok -> OperationResult.Ok(Datum.Fields(mapOf(CANCEL_RESULT to Datum.Text(if (cancelled.value == Cancel.SENT) "sent" else "nothing in flight"))))
        is Outcome.Failed -> OperationResult.Failed(cancelled.reason)
    }

    /** `seat.kill` (contracts.md §4.4): the agent's input closed, a grace, then its process tree by force; its end is reported as it happens. */
    private fun kill(seat: String): OperationResult = when (val killed = blocking { driver.kill(SeatId(seat), KILL_GRACE) }) {
        is Outcome.Ok -> OperationResult.Ok()
        is Outcome.Failed -> OperationResult.Failed(killed.reason)
    }

    /** `seat.retire` (contracts.md §4.4): the session ended, the pane closed, the home kept; its end is reported like any other. */
    private fun retire(seat: String): OperationResult {
        val session = held[seat]
        session?.ending = true
        val ended = if (session != null && !session.gone) blocking { driver.kill(SeatId(seat), KILL_GRACE) } else Outcome.Ok(Unit)
        held.remove(seat)
        panes.close(seat)
        return when (ended) {
            is Outcome.Ok -> OperationResult.Ok(Datum.Fields(mapOf(SEAT to Datum.Text(seat))))
            is Outcome.Failed -> OperationResult.Failed(ended.reason)
        }
    }

    /**
     * `seat.compact`, as the probe showed (S2.4 pre-flight): the adapter's `/compact`, sent as a prompt. A seat with a
     * turn in flight is compacted when that turn ends. `ok` when the compaction's turn ends `end_turn`.
     */
    private fun compact(seat: String, done: (OperationResult) -> Unit) {
        val session = held[seat] ?: return done(OperationResult.Failed("seat $seat has no session"))
        val now = synchronized(session) {
            if (session.busy) {
                compacts.getOrPut(seat) { java.util.Collections.synchronizedList(ArrayList()) } += done
                false
            } else true.also { session.busy = true }
        }
        if (now) compact(seat, session, done)
    }

    private fun compact(seat: String, session: Held, done: (OperationResult) -> Unit) {
        session.busy = true
        val sent = turn(seat, session, COMPACT, then = Ended { end ->
            done(when (end) {
                is Outcome.Ok<*> -> (end.value as? kp.driver.TurnEnd)?.stopReason.let { reason ->
                    if (reason == END_TURN) OperationResult.Ok() else OperationResult.Failed("the compaction's turn ended $reason")
                }
                is Outcome.Failed -> OperationResult.Failed(end.reason)
            })
        })
        if (sent is OperationResult.Failed) done(sent)
    }

    // ---- the binding (contracts.md §5, §9)

    private sealed interface Bound {
        data class Ok(val binding: Binding) : Bound
        data class Not(val why: String) : Bound
    }

    /** What [seat] is bound with: its role, the office, its stage, and the model and effort the roster gives the role there. */
    private fun bind(seat: String, role: String, stage: String?, cause: String?): Bound {
        val rules = rules() ?: return Bound.Not("no rules are in force")
        val levels = Levels(rules.pool.roster, inForce()?.texts ?: emptyMap())
        val seated = levels.line(role, asked(role, stage, cause)) { other -> lineOf(other, stage, levels) }
        return when (seated) {
            is Levels.Result.Seated -> Bound.Ok(Binding(seat, role, officeName, stage, seated.line.model, seated.line.effort))
            is Levels.Result.Unseated -> Bound.Not("$seat cannot be seated as $role: ${seated.why}")
        }
    }

    /** [role]'s line on [stage]: the binding of the seat that has it, live, else the line the roster gives it there. */
    private fun lineOf(role: String, stage: String?, levels: Levels): Line? {
        held.values.firstOrNull { it.binding.role == role && it.binding.stage == stage && !it.gone }?.binding?.let { bound ->
            return rules()?.pool?.roster?.order?.firstOrNull { it.model == bound.model && it.effort == bound.effort }
        }
        return (levels.line(role, asked(role, stage, null)) { null } as? Levels.Result.Seated)?.line
    }

    /**
     * The level a request names for [role] on [stage]: the message that caused the start, or one it answers, that names
     * it; else the last delivery about the stage that names the role and a level; else a rehearsal's seed of the role.
     */
    private fun asked(role: String, stage: String?, cause: String?): String? {
        val delivered = office?.state?.delivered ?: return null
        return Levels.asked(role, stage, cause, delivered) ?: seeds.values.firstOrNull { it.role == role && it.stage == stage }?.level
    }

    // ---- the places (contracts.md §9, §10)

    /** Where a stage's seat works: its worktree, its scratch, its list and STATUS, and its effort. */
    private class Layout(val worktree: Path, val scratch: Path, val list: Path?, val status: Path?, val effort: String?)

    /**
     * A stage's places: its worktree `<roots.worktrees>/<effort>-p<n>-s<n.m>` (lower case) and its scratch beside it under
     * the scratch root; its list `tasks-p<n>-s<n.m>-*.md` and `STATUS.md` in its effort's folder of the project. A seat
     * with no stage works in the project checkout, with a scratch of its own.
     */
    private fun layout(stage: String?): Layout {
        val match = stage?.let(STAGE_FORM::matchEntire) ?: return Layout(project, roots.scratch.resolve("seats"), null, null, efforts().keys.firstOrNull())
        val (effort, phase, number) = match.destructured
        val name = worktree(stage)!!
        val folder = efforts()[effort]?.let(project::resolve)
        val list = folder?.takeIf { it.isDirectory() }?.listDirectoryEntries("tasks-p$phase-s$number-*.md")?.minByOrNull { it.name }
        val status = folder?.resolve("STATUS.md")?.takeIf { it.isRegularFile() }
        return Layout(roots.worktrees.resolve(name), roots.scratch.resolve(name), list, status, effort)
    }

    private fun efforts(): Map<String, String> = rules()?.pool?.offices?.get(officeName)?.project?.efforts.orEmpty() + moreEfforts

    private fun codeRepos(rules: RuleSet): List<Path> = rules.pool.offices[officeName]?.repos?.values.orEmpty().map(::home)

    /** The profile whose frontmatter names [effort] (contracts.md §10, Amendment 22): its file's name. */
    private fun profile(effort: String?): String? {
        if (effort == null) return null
        val folder = keporkak.resolve("docs/profiles").takeIf { it.isDirectory() } ?: return null
        return folder.listDirectoryEntries("*.md").sortedBy { it.name }.firstOrNull { file ->
            Files.readString(file).lineSequence().drop(1).takeWhile { it.trim() != "---" }.any { it.trim() == "effort: $effort" }
        }?.name?.removeSuffix(".md")
    }

    private fun inForce(): InForceRules? = office?.rules ?: startingRules()

    private fun rules(): RuleSet? = inForce()?.ruleSet

    // ---- the facts (contracts.md §4.4)

    /**
     * The seats' facts, and every other from [other]: what the driver observes of a seat (source O) — of a seat with no
     * session, gone and closed, and no time silent or with a tool call open; the binding of a seat that has one; and
     * `roster.model_allowed`, from the roster and the seat's binding.
     */
    fun facts(other: FactSource): FactSource = FactSource { fact, stage ->
        val seat = (fact.params[SEAT] as? Datum.Text)?.value
        when {
            seat == null -> other.observe(fact, stage)
            fact.name in SeatFacts.names -> observe(seat, fact)
            fact.name == MODEL_ALLOWED -> allowed(seat, stage, other)
            fact.name in BOUND -> held[seat]?.takeIf { !it.gone }?.binding?.let { bound ->
                Datum.Text(when (fact.name) { SEAT_ROLE -> bound.role; SEAT_MODEL -> bound.model; else -> bound.effort })
            } ?: other.observe(fact, stage)
            else -> other.observe(fact, stage)
        }
    }

    private fun observe(seat: String, fact: FactRef): Datum? {
        val session = held[seat]?.takeIf { !it.gone }
        if (session == null) return when (fact.name) {
            SeatFacts.PROCESS_GONE, SeatFacts.PROTOCOL_CLOSED -> Datum.Bool(true)
            SeatFacts.SILENT_FOR, SeatFacts.TOOL_CALL_OPEN_FOR -> Datum.Text(ZERO)
            else -> null
        }
        val window = (fact.params[WINDOW] as? Datum.Text)?.value?.let { word -> UsageWindow.entries.firstOrNull { it.word == word } }
        return (driver.observe(SeatId(seat), fact.name, window) as? Outcome.Ok)?.value?.value?.let(::datum)
    }

    /**
     * `roster.model_allowed` (contracts.md §4.4): the seat's model is not reserved, or is reserved for its role — the role
     * and the model of the seat's binding while it is held; else as the world names them (the service's, from the log and
     * the report being judged; a rehearsal's walk); else, for a seat a `session-report` being judged asks to start, what
     * the report names (Amendment 47): its role, bound as its start will bind it — the level of the request it answers,
     * on the report's stage. A seat named by nothing: its role from its name, bound on the stage asked.
     */
    private fun allowed(seat: String, stage: String?, other: FactSource): Datum? {
        val roster = rules()?.pool?.roster ?: return null
        val bound = held[seat]?.takeIf { !it.gone }?.binding
        val report = reported(office?.arriving(), seat)
        val role = bound?.role ?: (other.observe(FactRef(SEAT_ROLE, mapOf(SEAT to Datum.Text(seat))), stage) as? Datum.Text)?.value ?: report?.role ?: roleOf(seat)
        val model = bound?.model ?: (other.observe(FactRef(SEAT_MODEL, mapOf(SEAT to Datum.Text(seat))), stage) as? Datum.Text)?.value
            ?: ((bind(seat, role, report?.stage ?: stage, report?.request) as? Bound.Ok)?.binding?.model) ?: return null
        return Datum.Bool(roster.reserved[model]?.contains(role) ?: true)
    }

    /** A seat's role from its name (contracts.md §1: `<role>[-<n or office>]`), or the role a rehearsal seeds it with. */
    private fun roleOf(seat: String): String = seeds[seat]?.role ?: seat.substringBefore('-')

    // ---- reporting (contracts.md §4.4, Amendment 22)

    /** What the driver observed without being asked — the process gone, the protocol closed: each reported when it happens. */
    private fun observed(fact: ObservedFact) {
        val seat = fact.subject.value
        val session = held[seat] ?: return
        if (fact.fact == SeatFacts.PROCESS_GONE) {
            session.gone = true
            session.busy = false
            reporter.execute { panes.close(seat) }
        }
        if (fact.fact in ENDS && !closing) report(seat, session, fact)
    }

    private fun report(seat: String, session: Held, fact: ObservedFact) = reporter.execute {
        if (!closing) office?.changed(FactRef(fact.fact, mapOf(SEAT to Datum.Text(seat))), datum(fact.value) ?: Datum.Null, session.binding.stage, fact.evidence)
    }

    /**
     * The watch (contracts.md §4.4, §9): for each seat with a turn in flight, its silence, its open tool call, the context
     * it has used and its usage, as the driver observes them now — reported; the robots' rules compare.
     */
    fun watch() {
        val office = office ?: return
        held.filter { (_, session) -> session.busy && !session.gone }.forEach { (seat, session) ->
            WATCHED.forEach { (name, window) ->
                val observed = driver.observe(SeatId(seat), name, window) as? Outcome.Ok ?: return@forEach
                val params = mapOf(SEAT to Datum.Text(seat)) + (window?.let { mapOf(WINDOW to Datum.Text(it.word)) } ?: emptyMap())
                datum(observed.value.value)?.let { office.changed(FactRef(name, params), it, session.binding.stage, observed.value.evidence) }
            }
        }
    }

    /**
     * The seats' start, once the office is attached (contracts.md §4.4, Amendment 22): each seat the state holds live and
     * the driver does not is reported gone — the daemon's seats died with it, and come back by the rules' re-seat. Then
     * the watch, every [watchEvery] of the injected clock (§9; issue #42, item 2): it reports when the clock has passed
     * the next `watch.every` since the start, and not between; a clock that passes several at once is reported once.
     */
    fun start(watchEvery: Duration?) {
        val office = office ?: error("the seats are attached to no office")
        val log = SeatLog(office.counted())
        log.live.filterNot(held::containsKey).sorted().forEach { seat ->
            val stage = log.known[seat]?.stage ?: seeds[seat]?.stage
            office.changed(FactRef(SeatFacts.PROCESS_GONE, mapOf(SEAT to Datum.Text(seat))), Datum.Bool(true), stage,
                "the daemon started; the seat's process did not outlive the one before it")
        }
        watchEvery?.let { every ->
            val began = clock.now()
            var next = began.plus(every)
            later.scheduleWithFixedDelay({
                runCatching {
                    val now = clock.now()
                    if (!closing && !now.isBefore(next)) {
                        watch()
                        next = began.plus(every.multipliedBy(Duration.between(began, now).dividedBy(every) + 1))
                    }
                }.onFailure { say("watch: ${it.message}") }
            }, LOOK_MILLIS, LOOK_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * A rehearsal's seat, started outside the rules — as an earlier part of the log would have left it: no `op` record,
     * and its binding is its [Seed]. Null when it started, else why not.
     */
    fun seed(seat: String): String? {
        val seed = seeds[seat] ?: return "seat $seat is not seeded"
        val bound = bind(seat, seed.role, seed.stage, null) as? Bound.Ok ?: return (bind(seat, seed.role, seed.stage, null) as Bound.Not).why
        return when (val opened = open(seat, bound.binding, session = null)) {
            is OperationResult.Ok -> null
            is OperationResult.Failed -> opened.reason
        }
    }

    /**
     * A rehearsal's crash of [seat] (S2.5): its process tree ended by force through the driver, with no grace and outside
     * any operation — its end is observed and reported as any other. False when it has no live process.
     */
    fun end(seat: String): Boolean {
        if (held[seat]?.takeIf { !it.gone } == null) return false
        return blocking { driver.kill(SeatId(seat), Duration.ZERO) } is Outcome.Ok
    }

    /** What [seat]'s session is bound with — role, stage, model and effort — while it has one. */
    fun binding(seat: String): Binding? = held[seat]?.binding

    /** What the vendor last reported of each subscription's windows through any seat (contracts.md §4.4, Amendment 44). */
    fun reports(): List<kp.facts.VendorReport> = subscriptions.values.toList()

    /** The vendor said, through [seat]'s agent, how much of a window of the seat's subscription is used: it stands for every seat of it. */
    private fun reported(seat: String, report: kp.driver.SubscriptionReport) {
        val binding = held[seat]?.binding ?: return
        val subscription = subscription(binding)
        subscriptions[subscription to report.window] = kp.facts.VendorReport(subscription, report.window, report.fraction, report.resets, report.heard, seat)
    }

    /** The session [seat] has now, or had last. */
    fun session(seat: String): String? = held[seat]?.session

    /** The SHA-256 of each session token minted here, never a token. */
    fun mintedDigests(): Set<String> = minted.toSet()

    /** The daemon stops: no more reports; the seats die with it — their panes closed — and the driver lets go of them. */
    override fun close() {
        closing = true
        later.shutdownNow()
        held.keys.forEach(panes::close)
        driver.close()
        reporter.shutdown()
        reporter.awaitTermination(REPORTER_WAIT, TimeUnit.SECONDS)
        work.shutdownNow()
    }

    companion object {
        const val SEAT = "seat"
        private const val ROLE = "role"
        private const val SESSION = "session"
        private const val WINDOW = "window"
        private const val CANCEL_RESULT = "cancel"
        private const val ZERO = "0s"
        private const val END_TURN = "end_turn"
        private const val CANCELLED = "cancelled"
        /** How a turn's end begins its record's evidence: what tells it from the watch's reports of a turn in flight. */
        const val TURN_ENDED = "turn ended: "
        /** The adapter's way to compact a session (S2.4's probe): its `compact` command, sent as a prompt. */
        const val COMPACT = "/compact"
        private const val START = "seat.start"
        private const val RESEAT = "seat.reseat"
        private const val CANCEL = "seat.cancel"
        private const val KILL = "seat.kill"
        private const val RETIRE = "seat.retire"
        private const val COMPACT_OP = "seat.compact"
        private val PERFORMED = setOf(START, RESEAT, CANCEL, KILL, RETIRE, COMPACT_OP)
        private const val TOKEN_MINT = "token.mint"
        private const val MODEL_ALLOWED = "roster.model_allowed"
        private const val SEAT_ROLE = "seat.role"
        private const val SEAT_MODEL = "seat.model"
        private const val SEAT_EFFORT = "seat.effort"
        private val BOUND = setOf(SEAT_ROLE, SEAT_MODEL, SEAT_EFFORT)
        /** What the watch reports of a seat with a turn in flight (contracts.md §4.4, Amendment 22). */
        private val WATCHED: List<Pair<String, UsageWindow?>> = listOf(
            SeatFacts.SILENT_FOR to null, SeatFacts.TOOL_CALL_OPEN_FOR to null, SeatFacts.CONTEXT_USED to null,
            SeatFacts.USAGE to UsageWindow.TURN, SeatFacts.USAGE to UsageWindow.SESSION,
        )
        private val STAGE_FORM = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\\.[0-9]+)$")
        /** How long the agent is given to end by itself when it is killed or retired, before its process tree is ended by force. */
        private val KILL_GRACE: Duration = Duration.ofSeconds(5)
        /** A seat's two ends the driver reports (contracts.md §4.4): each is reported as it happens. */
        private val ENDS = setOf(SeatFacts.PROCESS_GONE, SeatFacts.PROTOCOL_CLOSED)
        private const val SEND_WAIT_SECONDS = 60L
        /** How often the watch looks at the injected clock: when it reports is the clock's and `watch.every`'s. */
        private const val LOOK_MILLIS = 250L
        private const val REPORTER_WAIT = 10L

        /** A fact's value as the driver gives it, as the engine reads it: a boolean, a number, or text — a duration among them. */
        internal fun datum(value: JsonElement): Datum? {
            val primitive = value as? JsonPrimitive ?: return null
            return when {
                primitive.isString -> Datum.Text(primitive.content)
                primitive.booleanOrNull != null -> Datum.Bool(primitive.booleanOrNull!!)
                primitive.doubleOrNull != null -> Datum.of(primitive.content.toBigDecimal())
                else -> null
            }
        }

        /** A stage's worktree's name under the worktrees root: `<effort>-p<n>-s<n.m>`, lower case; null for no stage. */
        fun worktree(stage: String?): String? = stage?.let(STAGE_FORM::matchEntire)?.destructured?.let { (effort, phase, number) -> "${effort.lowercase()}-p$phase-s$number" }

        /** `~` as the office user's home. */
        internal fun home(path: String): Path = Path.of(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))

        /** The SHA-256 of [text], in hex. */
        fun sha256(text: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The four roots of an office (contracts.md §9), `~` expanded. */
data class Roots(val worktrees: Path, val scratch: Path, val seats: Path, val secrets: Path) {
    companion object {
        fun of(roots: Map<String, String>): Roots = Roots(
            Seats.home(roots.getValue("worktrees")), Seats.home(roots.getValue("scratch")), Seats.home(roots.getValue("seats")), Seats.home(roots.getValue("secrets")),
        )
    }
}

/** How a seat's agent is started: its command and the environment the caller builds for it (contracts.md §10: nothing inherited). */
interface Agent {
    fun command(seat: String, role: String): List<String>
    fun environment(seat: String): Map<String, String>
}
