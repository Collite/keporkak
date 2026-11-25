package kp.daemon

import kotlinx.serialization.json.JsonObject
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.log.LogLine
import kp.log.LogReader
import kp.log.counted
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.mailroom.Mailroom
import kp.mailroom.MailroomRefusal
import kp.mailroom.Offer
import kp.mailroom.Operations
import kp.mailroom.Reload
import kp.mailroom.Sender
import kp.model.OfficeId
import kp.model.utc
import kp.rules.Problem
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * One office's mailroom as the daemon holds it (architecture.md §2, `kp-daemon`): the mailroom on the project's log,
 * the rule set in force, and what the daemon itself observes — Bora's signed commands. Every change it carries is
 * signalled on [changes], which `kp inbox --follow` and a rehearsal wait on. It decides nothing: the mailroom judges.
 */
class Office private constructor(
    val name: String,
    val project: Path,
    private val mailroom: Mailroom,
    private val files: RuleFiles,
    rules: InForceRules,
    val presence: Presence,
    val clock: Clock,
) : AutoCloseable {
    val changes = Changes()
    @Volatile var rules: InForceRules = rules
        private set
    /**
     * The four facts the daemon reports when they change (contracts.md §4.4, Amendment 45): the service's, told of a
     * judgement that moved a seat or a stage and of a reload here, and by its seats of the rest. None in a rehearsal,
     * whose world is its walk's table.
     */
    @Volatile var reports: Reports? = null
    /** The last reload the mailroom refused, while no later one was accepted (contracts.md §2.2, Amendment 15). */
    @Volatile var refusedReload: RefusedReload? = null
        private set
    @Volatile var closed = false
        private set

    /** The fold of the log, as the mailroom works from it. */
    val state: State get() = mailroom.state

    /** The message whose arrival is being judged while its facts are asked, not yet on the log; null between judgements (Amendment 47). */
    fun arriving(): kp.model.Message? = mailroom.arriving

    /**
     * Bora ran a signed command — any `kp` command under `sudo`, a read too (contracts.md §6): it is the time his
     * presence is read from, and it reaches the mailroom as Bora acting, which brings him back when he was away. A
     * command that is a message is also judged as one ([submit]); it acts only once.
     */
    fun acted() {
        presence.signed()
        mailroom.command(signed = true)
        changes.signal()
    }

    /**
     * Carries one message through the mailroom's one way in (contracts.md §2.1: it sets `from`, `by` and `signed`); input
     * that is no message is answered [Offer.Malformed], and nothing is written (§3, §5).
     */
    fun submit(sender: Sender, input: JsonObject): Offer = mailroom.submit(sender, input).also { changes.signal() }

    /**
     * A fact the world reports changed — what an observer reports (contracts.md §4.1 `on: fact`, §8): the mailroom
     * judges what it triggers. Until the observers exist (P3), the rehearsal's harness reports a walk's facts here.
     */
    fun changed(fact: FactRef, value: Datum, stage: String? = null, evidence: String? = null) {
        mailroom.changed(fact, value, stage, evidence)
        changes.signal()
    }

    /** The world reports [fact], to be kept though it triggers nothing: a turn's end (contracts.md §4.4, Amendment 44). */
    fun recorded(fact: FactRef, value: Datum, stage: String? = null, evidence: String? = null) {
        mailroom.recorded(fact, value, stage, evidence)
        changes.signal()
    }

    /** [seat]'s turn ended, or it came up: idle with unread mail, it is woken with the oldest (contracts.md §4.4, Amendment 22). */
    fun idle(seat: String) {
        mailroom.idle(seat)
        changes.signal()
    }

    /** [seat]'s unread messages, oldest first, not marked read: what a re-seat replays. */
    fun unread(seat: String): List<kp.model.Message> = mailroom.unread(seat)

    /** [seat] has seen [ids]: a re-seat's prompt replayed them (contracts.md §4.4, Amendment 22). */
    fun read(seat: String, ids: List<String>) {
        mailroom.read(seat, ids)
        changes.signal()
    }

    /**
     * The seat API on [listen] (`host:port`; contracts.md §5, §11), in front of this office's mailroom, with [tokens] as
     * its registry and [github] answering a seat's ask for a GitHub token (§4.4, Amendment 35).
     */
    fun seatApi(tokens: kp.seatapi.Tokens, listen: String, github: kp.seatapi.GitHubTokens = kp.seatapi.GitHubTokens.NONE): kp.seatapi.SeatApi =
        kp.seatapi.SeatApi.start(mailroom, tokens, listen, github)

    /**
     * Waits until the operations handed to their performers are performed — or wait for an answer given later — and every
     * result is judged (contracts.md §7, Amendment 36): what a walk's next step, or a spec, waits for. True when they are.
     */
    fun settle(): Boolean = mailroom.settle().also { changes.signal() }

    /** Fires the deadlines that have passed. */
    fun tick() {
        mailroom.tick()
        changes.signal()
    }

    fun nextDeadline(): Instant? = mailroom.nextDeadline()

    /**
     * Hot reload (architecture.md §3): when the checkout's rule texts differ from those in force — and from those of
     * the last refused reload, which is not asked again — the mailroom reads and judges them. Null: nothing changed.
     */
    fun reloadIfChanged(): Reload? {
        val texts = files.read()
        if (texts == rules.texts || texts == refusedReload?.texts) return null
        return when (val reload = mailroom.reload()) {
            is Reload.Accepted -> reload.also {
                rules = InForceRules.of(files.lastRead).first ?: error("the mailroom accepted rules kp-rules refuses")
                refusedReload = null
                reports?.reloaded()
            }
            is Reload.Rejected -> reload.also { refusedReload = RefusedReload(clock.now(), it.problems, files.lastRead) }
        }.also {
            Journal.say("rules: ${if (it is Reload.Accepted) "reloaded, ${it.hash}" else "a reload was refused; the old set stays in force"}")
            changes.signal()
        }
    }

    /** The log read back — every file the mailroom folds, in `seq` order — for what the state does not keep. */
    fun lines(): List<LogLine> = LogReader(project, OfficeId(name)).read().lines

    /** The log's lines as they count: a voided judgement's left out but its first (contracts.md §3, Amendment 23). */
    fun counted(): List<kp.model.Envelope> = lines().counted().map { it.entry }

    override fun close() {
        closed = true
        mailroom.close()
        changes.signal()
    }

    companion object {
        /**
         * Starts the mailroom of office [name] on [project]'s log with the rule texts of the checkout [root] (architecture.md
         * §4): it folds every file, puts the rules in force and records them, and hosts the robots (contracts.md §8).
         * [world] is every fact the daemon does not observe itself. Throws [MailroomRefusal] when the log or the rules are
         * not fit to work from.
         *
         * The office is made once the log is folded and the rules are in force, before the start carries any judgement
         * again (contracts.md §4.4, Amendment 34): Bora's presence is read from the fold then, and [folded] is told the
         * office — the world reads the fold from there — so a fact that reads the log is never unknown only because the
         * daemon has just started.
         */
        fun start(
            name: String, project: Path, root: Path, world: FactSource, operations: Operations, deliveries: Deliveries, clock: Clock,
            folded: (Office) -> Unit = {},
        ): Office {
            val files = RuleFiles(root)
            val presence = Presence(clock)
            val facts = FactSource { fact, stage -> presence.observe(fact) ?: world.observe(fact, stage) }
            var office: Office? = null
            // A result is judged on its operation's thread (contracts.md §7, Amendment 36): its change is signalled there.
            Mailroom.start(project, OfficeId(name), files, facts, operations, deliveries, clock, robots = true, journal = Journal::say,
                resulted = { office?.changes?.signal() }, moved = { office?.reports?.moved() }) { mailroom ->
                val rules = InForceRules.of(files.lastRead).first ?: error("the mailroom started on rules kp-rules refuses")
                presence.seed(mailroom.state)
                presence.state = { mailroom.state }
                office = Office(name, project, mailroom, files, rules, presence, clock).also(folded)
            }
            return checkNotNull(office) { "the mailroom started without folding its log" }
        }
    }
}

/** A reload the mailroom refused: when, why, and the texts it read. */
class RefusedReload(val at: Instant, val problems: List<Problem>, val texts: Map<String, String>)

/** One signal per change of the office's state. */
class Changes {
    private val lock = Object()
    private var version = 0L

    fun signal() = synchronized(lock) {
        version++
        lock.notifyAll()
    }

    fun version(): Long = synchronized(lock) { version }

    /** Waits until a change after [seen], at most [millis]; the version then. */
    fun await(seen: Long, millis: Long): Long = synchronized(lock) {
        val end = System.nanoTime() + millis * 1_000_000
        while (version == seen) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) break
            lock.wait(left)
        }
        version
    }
}

/**
 * What the daemon observes of Bora itself (contracts.md §6, §4.4): when his last signed command came — any `kp`
 * command run under `sudo`, reads too. `bora.idle_for` is the time since then; `bora.acted` holds while that command
 * came after presence last moved. Before any signed command it knows of — none since the start, none on the log —
 * both are unknown.
 */
class Presence(private val clock: Clock) {
    @Volatile private var last: Instant? = null
    /** The state presence is read from, once the mailroom has started. */
    @Volatile var state: (() -> State)? = null

    /** A signed command arrived now. */
    fun signed() {
        last = clock.now()
    }

    /** What the log knows: the last signed message on it. */
    fun seed(state: State) {
        last = state.delivered.values.filter { it.signed }.maxOfOrNull { instant(it.at) }
    }

    fun observe(fact: FactRef): Datum? {
        val signed = last ?: return null
        return when (fact.name) {
            ACTED -> Datum.Bool(state?.invoke()?.machine(PRESENCE, BORA)?.since?.let { signed.isAfter(instant(it)) } ?: true)
            IDLE_FOR -> Datum.of(ChronoUnit.SECONDS.between(signed, clock.now()))
            else -> null
        }
    }

    private companion object {
        const val PRESENCE = "presence"
        const val BORA = "bora"
        const val ACTED = "bora.acted"
        const val IDLE_FOR = "bora.idle_for"
        fun instant(at: String): Instant = utc(at).toInstant(ZoneOffset.UTC)
    }
}
