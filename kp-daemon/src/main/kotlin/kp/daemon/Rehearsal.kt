package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.log.LogWriter
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.mailroom.Status
import kp.model.Message
import kp.model.OfficeId
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * `just rehearse <walk>` (architecture.md §7): the daemon on a throwaway project, with the walk's table of facts, its
 * seats scripted — each sends its step's kind with the step's args, the scripted performer ends each operation as the
 * walk says — pausing where the walk needs Bora, who answers with the real `kp` on the daemon's socket. The robots are
 * the daemon's: a step a robot sends (`by: robot:…`) is theirs, not the walk's to play. A fact the walk triggers is
 * skipped. A refused step ends the playing; the daemon keeps answering `kp` until the rehearsal is closed.
 */
class Rehearsal private constructor(private val walk: Walk, private val stage: String, private val daemon: Daemon, private val facts: TableFacts, private val out: (String) -> Unit) : AutoCloseable {
    /** The office the daemon runs: what a spec fires the deadlines of once it has moved the clock. */
    internal val office: Office get() = daemon.office
    private val ids = ConcurrentHashMap<String, String>()
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
            out("rehearsing ${walk.name}: stage $stage on ${office.project}")
            walk.steps.forEachIndexed { index, step -> if (!step(index + 1, step)) return }
            out("the walk is done: the stage is ${office.state.stage(stage)}. The daemon answers kp until the rehearsal is stopped (Ctrl-C).")
        } catch (_: InterruptedException) {
            // closed while waiting for Bora
        } catch (error: Exception) {
            if (!office.closed) out("the rehearsal stopped: $error")
        } finally {
            synchronized(lock) {
                ended = true
                lock.notifyAll()
            }
        }
    }

    /** Plays one step; false when the walk cannot go on. */
    private fun step(number: Int, step: Walk.Step): Boolean {
        facts.set(step.facts)
        val who = step.who
        val kind = step.kind
        return when {
            step.operation != null -> true
            step.fields.containsKey(EVENT) -> true.also { out("$number · a fact the walk triggers (${(step.fields[EVENT] as? Datum.Fields)?.fields?.get(FACT)?.let { (it as? Datum.Text)?.value }}): skipped — the mailroom takes facts as triggers from S1.4 on") }
            who == MAILROOM -> true
            step.text(BY)?.startsWith(ROBOT) == true -> true.also { robots(number, step, kind) }
            who == BORA && kind != null -> bora(number, step, kind)
            who != null && kind != null -> send(number, step, who, kind)
            else -> true
        }
    }

    private fun send(number: Int, step: Walk.Step, who: String, kind: String): Boolean {
        val re = step.text(RE)?.let { ids[it] ?: it }
        val input = JsonObject(listOfNotNull(KIND to JsonPrimitive(kind), STAGE to JsonPrimitive(stage), SUBJECT to JsonPrimitive("$kind (rehearsal step $number)"),
            ARGS to Json.parseToJsonElement((step.fields[ARGS] ?: Datum.Fields(emptyMap())).json()).jsonObject, re?.let { RE to JsonPrimitive(it) }).toMap())
        val answer = office.submit(Sender.Seat(who), input)
        step.id?.let { ids[it] = answer.id }
        val by = step.text(BY)?.let { " ($it)" } ?: ""
        out("$number · $who$by $kind: ${answer.status.name.lowercase()} ${answer.id}" + (answer.transition?.let { " — $it" } ?: "") +
            (answer.requirement?.let { " — ${answer.rule}: $it" } ?: "") + " · the stage is ${office.state.stage(stage)}")
        if (answer.status == Status.REFUSED) out("the walk stops here: its step $number was refused")
        return answer.status != Status.REFUSED
    }

    /**
     * A step the robots host sends, not the walk: what a later step names by its id is the last message of its kind that
     * rule sent, when it has sent one.
     */
    private fun robots(number: Int, step: Walk.Step, kind: String?) {
        val by = step.text(BY)
        val sent = office.state.delivered.values.lastOrNull { it.kind == kind && it.by == by }
        step.id?.let { id -> sent?.let { ids[id] = it.id } }
        out("$number · $by $kind: the robots' to send" + (sent?.let { " — ${it.id}" } ?: ", not sent"))
    }

    /** Waits for Bora's message of [kind], answered with `kp` on the socket; the walk goes on once it is delivered. */
    private fun bora(number: Int, step: Walk.Step, kind: String): Boolean {
        val before = office.state.delivered.keys.toSet()
        out("$number · waiting for Bora's $kind: kp inbox, then ${command(step, kind)}")
        synchronized(lock) {
            pauses++
            waitingFor = kind
            lock.notifyAll()
        }
        var version = office.changes.version()
        while (true) {
            val answered = office.state.delivered.values.firstOrNull { it.id !in before && it.kind == kind && it.from == BORA }
            if (answered != null) {
                step.id?.let { ids[it] = answered.id }
                out("$number · bora $kind: delivered ${answered.id} · the stage is ${office.state.stage(stage)}")
                return true
            }
            if (office.closed) return false
            version = office.changes.await(version, WAIT_MILLIS)
        }
    }

    /** The `kp` command that answers the item Bora is asked about. */
    private fun command(step: Walk.Step, kind: String): String {
        val item = step.text(RE)?.let { ids[it] }
        val ticket = office.state.openTickets().entries.filter { (_, ticket) -> if (item != null) ticket.item == item else office.state.delivered[ticket.item]?.stage == stage }
            .maxOfOrNull { it.key }?.toString() ?: "<ticket>"
        val outcome = (step.fields(ARGS)[OUTCOME] as? Datum.Text)?.value ?: "<outcome>"
        return when (kind) {
            "approval" -> "sudo kp approve $ticket"
            "decline" -> "sudo kp decline $ticket"
            "ruling" -> "sudo kp rule $ticket $outcome"
            "away" -> "sudo kp away"
            "queue-approval" -> "sudo kp queue $stage"
            else -> "sudo kp … ($kind)"
        }
    }

    /** The walk's operations, ended as its `operation:` steps say, in their order; one the walk does not name ends `ok`. */
    private class ScriptedOperations(walk: Walk, private val facts: TableFacts) : Operations {
        private val results = ConcurrentHashMap<String, ConcurrentLinkedDeque<Walk.Step>>().apply {
            walk.steps.filter { it.operation != null }.forEach { getOrPut(it.operation!!) { ConcurrentLinkedDeque() }.add(it) }
        }

        override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
            val step = results[request.operation]?.pollFirst()
            step?.let { facts.set(it.facts) }
            done(if (step?.text(RESULT) == FAILED) OperationResult.Failed(step.text(REASON) ?: "failed in the walk") else OperationResult.Ok())
        }
    }

    /** The walk's seats: scripted, so each is idle and takes what wakes it. */
    private class ScriptedSeats(private val seats: Set<String>) : Deliveries {
        override fun idle(seat: String) = seat in seats
        override fun wake(seat: String, message: Message) = seat in seats
    }

    companion object {
        private const val JOIN_MILLIS = 10_000L
        private const val WAIT_MILLIS = 30_000L
        private const val EVENT = "event"
        private const val FACT = "fact"
        private const val MAILROOM = "mailroom"
        private const val BORA = "bora"
        private const val KIND = "kind"
        private const val STAGE = "stage"
        private const val SUBJECT = "subject"
        private const val ARGS = "args"
        private const val RE = "re"
        private const val BY = "by"
        private const val ROBOT = "robot:"
        private const val OUTCOME = "outcome"
        private const val RESULT = "result"
        private const val REASON = "reason"
        private const val FAILED = "failed"
        /** The effort a rehearsal's stage belongs to: none of the plan's. */
        const val EFFORT = "REHEARSAL"

        /**
         * Rehearses [walk] on the throwaway project [project] as office [office]: its seats start `ready`, the daemon
         * starts with the walk's facts and listens on [socket], and the walk is played until it needs Bora. [out] hears
         * what happens. [clock] is the wall clock unless a spec moves it.
         */
        fun start(
            root: Path, walk: Path, project: Path, office: String, socket: Path, peers: PeerReader,
            clock: Clock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }, out: (String) -> Unit,
        ): Rehearsal {
            val read = Walk.read(walk)
            val stage = "$EFFORT/P${read.stage.removePrefix("S").substringBefore('.')}/${read.stage}"
            seatsReady(project, office, read.seats.keys, clock)
            val facts = TableFacts(read.facts)
            val started = Office.start(office, project, root, facts, ScriptedOperations(read, facts), ScriptedSeats(read.seats.keys), clock)
            val daemon = Daemon.start(started, facts, socket, peers)
            return Rehearsal(read, stage, daemon, facts, out).also { it.player.start() }
        }

        /** The walk's seats, `ready` before the walk begins — what an earlier part of the log would have left. */
        private fun seatsReady(project: Path, office: String, seats: Set<String>, clock: Clock) = LogWriter.open(project, OfficeId(office)).use { log ->
            val at = clock.now().truncatedTo(ChronoUnit.SECONDS).toString()
            seats.forEach { seat ->
                log.append(Json.parseToJsonElement("""{"v":1,"at":"$at","event":"transition","machine":"seat","subject":"$seat","from":"provisioned","to":"ready","cause":"rehearsal"}""").jsonObject)
            }
        }
    }
}
