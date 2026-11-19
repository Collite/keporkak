package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.settled
import kp.log.LogWriter
import kp.log.counted
import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.Offer
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Held
import kp.model.Message
import kp.model.OfficeId
import kp.model.Op
import kp.model.Refused
import kp.rules.RobotTrigger
import java.nio.file.Path
import java.time.temporal.ChronoUnit

/**
 * The harness (S1.6 T2): a walk of `rules/walks/` played through the daemon's real entry points — what `just rehearse`
 * plays and what the hero specs drive. Its seats are scripted: each sends its step's kind with the step's args, as a
 * seat's tool hands the mailroom what it was given. Bora's steps are his commands ([Bora]). A fact the walk reports
 * (`event: {fact: …}`) is reported to the mailroom as an observer would; Bora's `bora.acted` is a signed command of
 * his. Each operation the walk names ends as its `operation:` step says, when the walk reaches that step; one the walk
 * does not name ends `ok` at once. The robots are the daemon's: a step a robot sends (`by: robot:…`) or the mailroom
 * sends (`who: mailroom`) is looked for on the log, not played. After each step its `expect:` is read from the log and
 * the state; a step that does not meet it ends the play with a [WalkFailure] naming the step, what was expected, what
 * was found, and the refusal if there was one.
 */
class Script(val walk: Walk, val effort: String = EFFORT) {
    /** Each stage the walk names, by the stage id it is played as. */
    val stages: Map<String, String> = ids(walk, effort)
    val facts = WalkFacts(walk, stages)
    val operations = Performer()
    val seats: Deliveries = ScriptedSeats(walk.seats.keys)
    /**
     * The walk's seats as processes, when they are (S2.4: `--seats fake`): the seat operations are theirs to perform —
     * a step that names one waits for its result — and a step that reports a seat's death ends its process.
     */
    @Volatile var live: LiveSeats? = null

    fun stageId(name: String): String = stages.getValue(name)

    /**
     * What the walk starts from, written before the daemon starts — what an earlier part of the log would have left:
     * its seats `ready`; each stage that starts elsewhere than `planned` in its state; a stage's seat in the state its
     * `machines:` gives. One judgement, closed (contracts.md §3, Amendment 23), so a start has nothing to void.
     */
    fun seed(project: Path, office: String, clock: Clock) = LogWriter.open(project, OfficeId(office)).use { log ->
        val at = clock.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val judgement = log.judgement()
        var moved = false
        fun move(machine: String, subject: String, from: String, to: String) =
            judgement.add(Json.parseToJsonElement("""{"v":1,"at":"$at","event":"transition","machine":"$machine","subject":"$subject","from":"$from","to":"$to","cause":"rehearsal"}""").jsonObject,
                if (machine == STAGE) kp.model.StageId(subject) else null).also { moved = true }
        walk.seats.keys.forEach { seat -> move(SEAT, seat, PROVISIONED, READY) }
        walk.stageNames.forEach { name ->
            val initial = walk.initialOf(name)
            if (initial != Walk.PLANNED) move(STAGE, stageId(name), Walk.PLANNED, initial)
            walk.stages[name]?.machines.orEmpty().forEach { (machine, state) ->
                require(machine == SEAT) { "the walk sets the $machine machine of $name: the harness seeds only a stage's seat" }
                val seat = (facts.table(name).observe(FactRef(SEAT_NAME, mapOf(OF to Datum.Text(CODER))), stageId(name)) as? Datum.Text)?.value
                    ?: error("the walk sets the seat of $name but names no coder's seat there")
                if (state != READY) move(SEAT, seat, READY, state)
            }
        }
        if (moved) judgement.close(at)
    }

    /**
     * The world as it stands once the walk's steps up to [index] (from 0, inclusive) have begun: each step's facts set in
     * order, the walk at that step's stage — what a daemon started again in the middle of the walk observes.
     */
    fun worldThrough(index: Int) {
        var stage = walk.stage
        walk.steps.take(index + 1).forEach { step ->
            stage = step.text(STAGE) ?: stage
            facts.table(stage).set(step.facts)
        }
        facts.current = stage
    }

    /** A player of this walk on [office]; Bora's steps go to [bora], and [out] hears each step as it is played. */
    fun player(office: Office, bora: Bora, out: (String) -> Unit = {}): Player = Player(this, office, bora, out).also { operations.attach(it) }

    /**
     * The walk's operations: a request the walk names in a step still to come — the operation, and a request step whose
     * kind and sender are the requesting message's — waits for that step; any other ends `ok` at once. A request made
     * before a player is attached — by the judgement a start carries again — waits until the player says which.
     */
    inner class Performer : Operations {
        @Volatile var player: Player? = null
            private set
        private val waiting = ArrayList<Pair<OperationRequest, (OperationResult) -> Unit>>()

        override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
            if (player?.awaits(request) != false) synchronized(waiting) { waiting += request to done } else done(OperationResult.Ok())
        }

        /** [player] plays the walk from now on: what waits and no step of it still to come names ends `ok`. */
        fun attach(player: Player) {
            this.player = player
            val free = synchronized(waiting) { waiting.filterNot { player.awaits(it.first) }.also { waiting.removeAll(it) } }
            free.forEach { (_, done) -> done(OperationResult.Ok()) }
        }

        /** The request of [operation] for the message [cause] that waits for its step, taken. */
        fun take(operation: String, cause: String): ((OperationResult) -> Unit)? = synchronized(waiting) {
            val at = waiting.indexOfFirst { (request, _) -> request.operation == operation && request.cause == cause }
            if (at < 0) null else waiting.removeAt(at).second
        }

        /** What still waits: operation and cause. */
        fun waiting(): List<Pair<String, String>> = synchronized(waiting) { waiting.map { it.first.operation to it.first.cause } }
    }

    /** The walk's seats: scripted, so each is idle — its turn ends at once — and takes what wakes it; each is live at a start. */
    private class ScriptedSeats(private val seats: Set<String>) : Deliveries {
        override fun idle(seat: String) = seat in seats
        override fun wake(seat: String, message: Message) = seat in seats
        override fun live(): Set<String> = seats
    }

    companion object {
        /** The effort a rehearsal's stage belongs to: none of the plan's. */
        const val EFFORT = "REHEARSAL"
        private const val STAGE = "stage"
        private const val SEAT = "seat"
        private const val SEAT_NAME = "seat.name"
        private const val OF = "of"
        private const val CODER = "coder"
        private const val PROVISIONED = "provisioned"
        private const val READY = "ready"
        private val NUMBERED = Regex("S([0-9]+)\\.([0-9]+)")

        /**
         * A stage id for each stage the walk names: `S2.1` is `<effort>/P2/S2.1`; a name of another form (`tail`,
         * `S3.2-rollback`) takes the stage its number names when no other stage has it, else the next free one of its phase.
         */
        fun ids(walk: Walk, effort: String): Map<String, String> {
            val taken = LinkedHashMap<String, String>()
            fun free(phase: Int, minor: Int): String = generateSequence(minor) { it + 1 }.map { "$effort/P$phase/S$phase.$it" }.first { it !in taken.values }
            walk.stageNames.sortedBy { if (NUMBERED.matches(it)) 0 else 1 }.forEach { name ->
                val numbered = NUMBERED.matchEntire(name) ?: NUMBERED.find(name)?.takeIf { it.range.first == 0 }
                taken[name] = numbered?.let { free(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: free(0, 1)
            }
            return walk.stageNames.associateWith(taken::getValue)
        }
    }
}

/** Bora, for a walk: his messages — `kp`'s commands, signed or not — and his acting, which is any signed command. */
interface Bora {
    /** Sends Bora's message the step asks for; returns once it is on the log. */
    fun send(ask: Ask)

    /** Bora runs a signed command — any `sudo kp …`: he acts (contracts.md §6). */
    fun acts()

    /**
     * What a step asks of Bora: [kind] about [stage], in reply to the message [re] (its id) when the step names one, with
     * [args] — stage names already played as stage ids — signed when [signed]. [ticket] is the open ticket of the item
     * it answers, when there is one.
     */
    class Ask(val number: Int, val step: Walk.Step, val kind: String, val stage: String, val re: String?, val args: Datum.Fields, val signed: Boolean, val ticket: Long?)
}

/** A walk's seats as processes (S2.4): what they perform, and a death as a walk plays one. */
interface LiveSeats {
    /** Whether [operation] is the seats' to perform: the walk waits for its result rather than giving it. */
    fun performs(operation: String): Boolean

    /** [seat]'s process ends from outside; false when it has none. */
    fun die(seat: String): Boolean
}

/** A step that did not do what the walk expected of it. */
class WalkFailure(message: String) : AssertionError(message)

/** One played step: its number, the message it is about (sent, or found), its outcome, and the lines it wrote. */
class Played(val number: Int, val step: Walk.Step, val stage: String, val message: String?, val outcome: String?, val first: Long, val last: Long)

/**
 * Plays a [Script]'s walk on an office, step by step; what a step names by its `id` is the message it sent or found. A
 * player is used by one thread.
 */
class Player internal constructor(private val script: Script, val office: Office, private val bora: Bora, private val out: (String) -> Unit) {
    private val walk = script.walk
    /** A step's id → the id of the message it is about. */
    val ids = HashMap<String, String>()
    /** The messages robot and mailroom steps have found, so that each is found once. */
    private val found = HashSet<String>()
    val played = ArrayList<Played>()
    /** The index of the next step to play. */
    var next = 0
        private set
    /** The stage the walk is at, by its name in the walk. */
    var stage: String = walk.stage
        private set

    val done: Boolean get() = next >= walk.steps.size

    /** The steps whose own line was on the log a daemon started again on: by index, the message each is about and its outcome. */
    private val kept = HashMap<Int, Pair<String?, String?>>()
    /** Steps before this index are caught up — on the log already — and not checked again. */
    private var caughtUp = 0

    /**
     * Takes up a walk on a daemon started again on a log a player of it wrote: each step in [kept] (by index) already
     * has its own line there — a message, an operation's result, a fact — and the start carried what that line began.
     * Those steps are played again without being sent; a robot's or the mailroom's message is looked for as always.
     */
    fun resume(kept: Map<Int, Pair<String?, String?>>) {
        require(next == 0) { "a player resumes only before it has played" }
        this.kept += kept
        caughtUp = (kept.keys.maxOrNull() ?: -1) + 1
    }

    /** Plays the next step and checks what it expects. */
    fun step(): Played {
        val index = next++
        val step = walk.steps[index]
        stage = step.text(STAGE) ?: stage
        script.facts.current = stage
        script.facts.table(stage).set(step.facts)
        val first = last() + 1
        val (message, outcome) = when {
            index in kept -> kept.getValue(index)
            step.operation != null -> operation(index + 1, step)
            step.fields.containsKey(EVENT) -> fact(index + 1, step)
            step.who == MAILROOM || step.text(BY)?.startsWith(ROBOT) == true -> find(index + 1, step)
            step.who == BORA && step.kind != null -> bora(index + 1, step, step.kind!!)
            step.who != null && step.kind != null -> send(index + 1, step, step.who!!)
            else -> null to null
        }
        step.id?.let { id -> message?.let { ids[id] = it } }
        office.changes.signal()
        return Played(index + 1, step, script.stageId(stage), message, outcome, first, last()).also { played ->
            this.played += played
            out(describe(played))
            if (index >= caughtUp) check(played)
        }
    }

    /** Plays the steps up to and including the one whose id is [id]. */
    fun through(id: String): List<Played> {
        require(walk.steps.drop(next).any { it.id == id }) { "no step $id is left to play in ${walk.name}" }
        return buildList { while (true) { val played = step(); add(played); if (played.step.id == id) break } }
    }

    /** Plays the steps up to and including the [number]th (from 1). */
    fun throughStep(number: Int): List<Played> = buildList { while (next < number) add(step()) }

    /** Plays every step left, then checks how the walk ends: its `final` states and its `expected_counts`. */
    fun rest(): List<Played> = buildList { while (!done) add(step()) }.also { finish() }

    /** The walk's end: each stage in the state `final` gives, and as many deliveries of each counted kind as it says. */
    fun finish() {
        val states = when (val final = walk.final) {
            is Datum.Text -> mapOf(walk.stage to final.value)
            is Datum.Fields -> final.fields.mapValues { (it.value as Datum.Text).value }
            else -> emptyMap()
        }
        states.forEach { (name, expected) ->
            val actual = state(script.stageId(name))
            if (actual != expected) throw WalkFailure("walk ${walk.name} ends with $name $actual, not $expected")
        }
        val lines = lines()
        walk.expectedCounts.forEach { (kind, expected) ->
            val actual = lines.filterIsInstance<Delivered>().count { messageOf(lines, it.id)?.kind == kind }
            if (actual != expected) throw WalkFailure("walk ${walk.name} delivers $kind $actual times, not $expected")
        }
        val waiting = script.operations.waiting()
        if (waiting.isNotEmpty()) throw WalkFailure("walk ${walk.name} ends with operations waiting for their results: $waiting")
    }

    /**
     * Whether a request waits for a step still to come: an operation step that names the operation, whose request step
     * has the requesting message's kind and sender — the very message, once that step has been played.
     */
    internal fun awaits(request: OperationRequest): Boolean {
        val cause = office.state.let { it.delivered[request.cause]?.let { d -> Triple(d.kind, d.from, d.by) } ?: it.pending[request.cause]?.let { p -> Triple(p.kind, p.from, p.by) } }
            ?: return false
        return walk.steps.drop(next).any { step ->
            if (step.operation != request.operation) return@any false
            val asked = step.text(REQUEST) ?: return@any false
            ids[asked]?.let { return@any it == request.cause }
            val by = walk.steps.firstOrNull { it.id == asked } ?: return@any false
            by.kind == cause.first && (by.text(BY)?.let { it == cause.third } ?: (by.who == cause.second || cause.second.startsWith("${by.who}-")))
        }
    }

    // ---- the kinds of step

    /**
     * A seat's message: the step's kind, args, `re`, `to` and body, as a seat's tool hands them on. Input that is no
     * message is answered as the mailroom answers it, nothing is written, and the walk stops at the step (contracts.md §3).
     */
    private fun send(number: Int, step: Walk.Step, who: String): Pair<String?, String?> {
        val answer = when (val offer = office.submit(Sender.Seat(who), input(number, step))) {
            is Offer.Answered -> offer.answer
            is Offer.Malformed -> throw WalkFailure(at(number, step) + ": its input is no message — $offer; nothing was written")
        }
        return answer.id to answer.status.name.lowercase()
    }

    private fun input(number: Int, step: Walk.Step): JsonObject = JsonObject(listOfNotNull(
        KIND to JsonPrimitive(step.kind),
        STAGE to JsonPrimitive(script.stageId(stage)),
        SUBJECT to JsonPrimitive("${step.kind} (${walk.name} step $number)"),
        ARGS to Json.parseToJsonElement(args(step).json()),
        step.text(RE)?.let { RE to JsonPrimitive(ids[it] ?: it) },
        step.text(TO)?.let { TO to JsonPrimitive(it) },
        step.text(BODY)?.let { BODY to JsonPrimitive(it) },
    ).toMap())

    /** The step's args, a value that names one of the walk's stages read as the stage id it is played as. */
    private fun args(step: Walk.Step): Datum.Fields {
        fun played(value: Datum): Datum = when (value) {
            is Datum.Text -> script.stages[value.value]?.let(Datum::Text) ?: value
            is Datum.Items -> Datum.Items(value.items.map(::played))
            is Datum.Fields -> Datum.Fields(value.fields.mapValues { played(it.value) })
            else -> value
        }
        return played(Datum.Fields(step.fields(ARGS))) as Datum.Fields
    }

    /** Bora's message: asked of [bora]; the step is about the message from him of its kind that is then on the log. */
    private fun bora(number: Int, step: Walk.Step, kind: String): Pair<String?, String?> {
        val before = last()
        val re = step.text(RE)?.let { ids[it] ?: it }
        val stageId = script.stageId(stage)
        val state = office.state
        val ticket = state.openTickets().entries.filter { (_, ticket) ->
            if (re != null) ticket.item == re else (state.delivered[ticket.item]?.stage ?: state.pending[ticket.item]?.stage) == stageId
        }.maxOfOrNull { it.key }
        bora.send(Bora.Ask(number, step, kind, stageId, re, args(step), step.text(SIGNED) == BORA, ticket))
        // Bora may have been refused on the way — an unsigned try: the step is about the message of his that went through.
        val lines = lines()
        val sent = lines.filterIsInstance<Message>().filter { it.seq.toLong() > before && it.from.value == BORA && it.kind == kind }.map { it.id.value }
        val message = sent.lastOrNull { outcome(lines, it) != REFUSED } ?: sent.lastOrNull() ?: throw WalkFailure(at(number, step) + ": Bora's $kind is not on the log")
        return message to outcome(lines, message)
    }

    /**
     * A message a robot or the mailroom sent of itself, looked for on the log: the first of the step's kind `by` the
     * step's rule (or from the mailroom) not found by an earlier step — in reply to the message the step's `re` names,
     * to the party its `to` names, when it names them.
     */
    private fun find(number: Int, step: Walk.Step): Pair<String?, String?> {
        val by = step.text(BY)
        val re = step.text(RE)?.let { ids[it] ?: it }
        val lines = lines()
        val message = lines.filterIsInstance<Message>().firstOrNull { message ->
            message.id.value !in found && message.kind == step.kind && (if (by != null) message.by == by || twins(by, message.by) else message.from.value == MAILROOM) &&
                (re == null || message.re?.value == re) && (step.text(TO)?.let { to -> message.to.value == to } ?: true)
        } ?: throw WalkFailure(at(number, step) + ": ${by ?: MAILROOM} sent no ${step.kind}" + (re?.let { " in reply to $it" } ?: "") + " — " +
            lines.filterIsInstance<Message>().filter { if (by != null) it.by.startsWith(ROBOT) else it.from.value == MAILROOM }.map { "${it.kind} ${it.by}" }
                .let { sent -> if (sent.isEmpty()) "${if (by != null) "the robots have" else "the mailroom has"} sent nothing yet" else "sent so far: ${sent.joinToString()}" })
        found += message.id.value
        return message.id.value to outcome(lines, message.id.value)
    }

    /**
     * An operation the walk ends: the request the step names, given the step's result — or, when the walk's seats are
     * processes and the operation is theirs, its result as they gave it, waited for.
     */
    private fun operation(number: Int, step: Walk.Step): Pair<String?, String?> {
        val operation = step.operation!!
        val asked = step.text(REQUEST) ?: throw WalkFailure(at(number, step) + ": names no request")
        val cause = ids[asked] ?: throw WalkFailure(at(number, step) + ": its request $asked has not been played")
        // A seat woken with a message is woken by the seats, scripted or live (S2.4): the walk waits for that result too.
        val seatsWake = operation == SEAT_WAKE && lines().filterIsInstance<Op>().any { it.op == SEAT_WAKE && it.cause == cause && it.with?.containsKey(SEAT) == true }
        if (script.live?.performs(operation) == true || seatsWake) {
            val result = waitFor(number, step, "$operation's result for $cause") {
                lines().filterIsInstance<Op>().lastOrNull { it.op == operation && it.cause == cause && it.phase != REQUESTED }
            }
            return cause to result.phase
        }
        val done = script.operations.take(operation, cause)
            ?: throw WalkFailure(at(number, step) + ": no $operation request of $cause waits for its result — requested: " +
                lines().filterIsInstance<Op>().filter { it.cause == cause }.joinToString { "${it.op} ${it.phase}" })
        val failed = step.text(RESULT) == FAILED
        done(if (failed) OperationResult.Failed(step.text(REASON) ?: "failed in the walk") else OperationResult.Ok())
        return cause to (if (failed) FAILED else OK)
    }

    /** A fact the walk reports: `bora.acted` is Bora acting; any other fact is reported as its table now gives it. */
    private fun fact(number: Int, step: Walk.Step): Pair<String?, String?> {
        val name = (step.fields(EVENT)[FACT] as? Datum.Text)?.value ?: throw WalkFailure(at(number, step) + ": reports an event that is no fact")
        if (name == BORA_ACTED) {
            bora.acts()
            return null to null
        }
        val ref = FactRef(name, step.fields(TRIGGER))
        val seat = (ref.params[SEAT] as? Datum.Text)?.value
        val live = script.live
        if (live != null && name in DEATHS && seat != null) {
            // The seat's process ends; the driver reports it, and the walk waits until the mailroom has judged it.
            val before = last()
            if (!live.die(seat)) throw WalkFailure(at(number, step) + ": $seat has no process to end")
            waitFor(number, step, "the report that $seat is gone") {
                lines().filterIsInstance<kp.model.Fact>().firstOrNull { it.seq.toLong() > before && it.fact in DEATHS && it.subject == seat }
            }
            return null to null
        }
        val value = TableFacts.lookup(script.facts.table(stage)[name], ref) ?: throw WalkFailure(at(number, step) + ": its table has no value for $ref")
        office.changed(ref, value, script.stageId(stage))
        return null to null
    }

    /** Waits until [found] finds something on the log, at most [WAIT_MILLIS]: what the step waits for, as [what] says. */
    private fun <T : Any> waitFor(number: Int, step: Walk.Step, what: String, found: () -> T?): T {
        val end = System.nanoTime() + WAIT_MILLIS * 1_000_000
        var version = office.changes.version()
        while (true) {
            found()?.let { return it }
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0 || office.closed) throw WalkFailure(at(number, step) + ": waited ${WAIT_MILLIS / 1000}s for $what")
            version = office.changes.await(version, minOf(left, POLL_MILLIS))
        }
    }

    // ---- what a step expects

    private fun check(played: Played) {
        val step = played.step
        val expect = step.expect
        val refused = played.outcome == REFUSED
        if (refused && expect[REFUSED_KEY] != Datum.Bool(true)) fail(played, "delivered", REFUSED)
        if (played.outcome == HELD && expect.isNotEmpty() && expect[HELD] != Datum.Bool(true)) fail(played, "delivered", HELD)
        expect.forEach { (key, value) ->
            val actual: Datum = when (key) {
                STATE_KEY -> Datum.Text(ownState(played).stage(played.stage) ?: Walk.PLANNED)
                STATES_KEY -> Datum.Fields((value as Datum.Fields).fields.keys.associateWith { machine -> Datum.Text(machineState(machine, played) ?: "-") })
                SENDS -> Datum.Items(sends(played).map { Datum.Text(it.kind) })
                WAKES -> Datum.Items(wakes(played).map { Datum.Text(it.second) })
                RULES -> Datum.Items((sends(played).map { it.by.removePrefix(ROBOT) } + wakes(played).map { it.first }).map(Datum::Text)).let { fired ->
                    if (liveEnd(played)) heardAsTheWalkSays(fired, value) else fired
                }
                HELD -> Datum.Bool(played.outcome == HELD)
                REFUSED_KEY -> Datum.Bool(refused)
                // The walker's own words for a refusal ("no arrow", "body line cap exhausted"): the engine writes others, and
                // the refusal itself is what `refused` checks.
                REFUSAL_CONTAINS -> value
                // The rule that refuses the step: the walker, the engine's walk harness and here a rehearsal and a hero hold
                // the step to it — another rule refusing it fails the step (S2.8; S2.9 T6).
                REFUSED_BY -> Datum.Text(refusal(played)?.rule ?: "-")
                else -> throw WalkFailure(at(played.number, step) + ": expects '$key', which the harness does not read")
            }
            if (actual != value) fail(played, value.shown(), actual.shown(), key)
        }
    }

    /** A step that ends a live seat's process: its end is the driver's to report (S2.4), the walk's fact or not. */
    private fun liveEnd(played: Played): Boolean =
        script.live != null && (played.step.fields(EVENT)[FACT] as? Datum.Text)?.value in DEATHS

    /**
     * The rules a live seat's end fired, read as the walk names them (S2.9 T3): the driver reports the process gone and the
     * protocol closed each as it happens, in either order, and the first is the one event — heard by the rule on that
     * fact, the walk's or its twin on the seat's other end. A rule on the other end stands for the walk's at its place;
     * what it sends is checked as `sends`.
     */
    private fun heardAsTheWalkSays(fired: Datum.Items, named: Datum): Datum {
        val walk = (named as? Datum.Items)?.items.orEmpty().map { (it as? Datum.Text)?.value }
        val heard = fired.items.map { (it as? Datum.Text)?.value }
        val same = walk.size == heard.size && walk.zip(heard).all { (want, got) -> want == got || twins(ROBOT + want, ROBOT + got) }
        return if (same) named else fired
    }

    /** Two robots' rules on a seat's two ends, with live seats: either may be the one that heard it first (S2.9 T3). */
    private fun twins(by: String, other: String): Boolean {
        if (script.live == null) return false
        val ends = office.rules.ruleSet.robots.values.flatMap { it.rules }.filter { (it.on as? RobotTrigger.Fact)?.name in DEATHS }.map { ROBOT + it.id }.toSet()
        return by in ends && other in ends
    }

    private fun fail(played: Played, expected: String, actual: String, key: String = "outcome"): Nothing {
        val refusal = refusal(played)?.let { " — refused ${it.id} by ${it.rule}: ${it.requirement}" + (it.failed?.let { failed -> " (failed: $failed)" } ?: "") } ?: ""
        throw WalkFailure("${at(played.number, played.step)}: expected $key $expected, found $actual$refusal")
    }

    private fun refusal(played: Played): Refused? = played.message?.let { id -> lines().filterIsInstance<Refused>().lastOrNull { it.id == id } }

    /**
     * The state the step's own judgement left: the log folded through the record of its message — or its operation's
     * result, or its fact — and the moves and requests written with it, before anything that judgement made the
     * mailroom or a robot send. What a robot sends in answer is a step of its own in a walk (`by: robot:…`). That cut
     * falls inside the judgement, so its lines are applied as written (`settled`, Amendment 23).
     */
    private fun ownState(played: Played): kp.engine.State {
        val lines = lines()
        val window = lines.filter { it.seq.toLong() in played.first..played.last }
        val anchor = when {
            played.step.operation != null -> window.lastOrNull { it is Op && it.op == played.step.operation && it.cause == played.message && it.phase != REQUESTED }
            played.message != null -> lines.lastOrNull { (it is Delivered && it.id == played.message) || (it is Held && it.id == played.message) || (it is Refused && it.id == played.message) }
                ?: lines.firstOrNull { it is Message && it.id.value == played.message }
            else -> window.firstOrNull { it is kp.model.Fact } ?: window.firstOrNull()
        } ?: return kp.engine.fold(lines.filter { it.seq.toLong() < played.first }).settled()
        val after = lines.dropWhile { it.seq < anchor.seq }.drop(1)
        val own = after.takeWhile { it is kp.model.Transition || (it is Op && it.phase == REQUESTED) || it is kp.model.Read || it is kp.model.Rules }
        val end = (own.lastOrNull() ?: anchor).seq
        return kp.engine.fold(lines.filter { it.seq <= end }).settled()
    }

    /** The state of [machine] the step speaks of: a seat — the one its args or trigger name, else its stage's coder; Bora's presence; its message's thread. */
    private fun machineState(machine: String, played: Played): String? {
        val state = ownState(played)
        return when (machine) {
            SEAT -> {
                val seat = (played.step.fields(ARGS)[SEAT] ?: played.step.fields(TRIGGER)[SEAT] as? Datum.Text)?.let { (it as? Datum.Text)?.value }
                    ?: (script.facts.table(stage).observe(FactRef(SEAT_NAME, mapOf(OF to Datum.Text(CODER))), played.stage) as? Datum.Text)?.value
                seat?.let { state.machine(SEAT, it)?.state ?: PROVISIONED }
            }
            PRESENCE -> state.presence(BORA) ?: PRESENT
            FLAG_THREAD -> played.message?.let { id -> (state.delivered[id]?.thread ?: state.pending[id]?.thread) }?.let { state.machine(FLAG, it)?.state ?: ASKED }
            else -> throw WalkFailure(at(played.number, played.step) + ": expects the state of '$machine', which the harness does not read")
        }
    }

    /**
     * What the robots sent on this step: in reply to its message — or, for an operation, to the message that requested
     * it, among the lines the step wrote; for a reported fact, the sends with no `re` the step wrote.
     */
    private fun sends(played: Played): List<Message> = lines().filterIsInstance<Message>().filter { message ->
        message.by.startsWith(ROBOT) && when {
            played.step.operation != null -> message.re?.value == played.message && message.seq.toLong() in played.first..played.last
            played.message == null -> message.re == null && message.seq.toLong() in played.first..played.last
            else -> message.re?.value == played.message
        }
    }

    /**
     * The robots' own seats woken on this step: (rule, mode) of each `seat.wake` requested for it in a mode — not a seat
     * woken with a message it was sent (S2.4).
     */
    private fun wakes(played: Played): List<Pair<String, String>> = lines().filterIsInstance<Op>().filter { op ->
        op.op == SEAT_WAKE && op.phase == REQUESTED && op.with?.containsKey(MODE) == true && when {
            played.step.operation != null || played.message == null -> op.seq.toLong() in played.first..played.last && (played.message == null || op.cause == played.message)
            else -> op.cause == played.message
        }
    }.map { op -> ((op.with?.get(RULE) as? JsonPrimitive)?.content ?: "?") to ((op.with?.get(MODE) as? JsonPrimitive)?.content ?: "?") }

    // ---- the log

    /** The log as it counts: a voided judgement's lines but its first are left out (Amendment 23). */
    private fun lines(): List<Envelope> = office.lines().counted().map { it.entry }

    private fun last(): Long = lines().lastOrNull()?.seq?.toLong() ?: 0L

    private fun state(stageId: String): String = office.state.stage(stageId) ?: Walk.PLANNED

    private fun messageOf(lines: List<Envelope>, id: String): Message? = lines.filterIsInstance<Message>().firstOrNull { it.id.value == id }

    /** What happened to message [id]: its last record — delivered, held or refused — or `written` when none says yet. */
    private fun outcome(lines: List<Envelope>, id: String): String = lines.lastOrNull { line ->
        (line is Delivered && line.id == id) || (line is Held && line.id == id) || (line is Refused && line.id == id)
    }.let { line ->
        when (line) {
            is Delivered -> DELIVERED
            is Held -> HELD
            is Refused -> REFUSED
            else -> "written"
        }
    }

    private fun at(number: Int, step: Walk.Step): String =
        "walk ${walk.name} step $number (" + listOfNotNull(step.id, step.who ?: step.text(BY), step.kind ?: step.operation ?: step.fields(EVENT)[FACT]?.let { (it as? Datum.Text)?.value }).joinToString(" · ") + ")"

    private fun describe(played: Played): String {
        val step = played.step
        val what = step.kind ?: step.operation ?: (step.fields(EVENT)[FACT] as? Datum.Text)?.value ?: "facts"
        val who = step.text(BY) ?: step.who ?: ""
        return "${played.number} · $who $what: ${played.outcome ?: "done"}" + (played.message?.let { " $it" } ?: "") + " · ${played.stage} is ${state(played.stage)}"
    }

    private fun Datum.shown(): String = when (this) {
        is Datum.Text -> value
        else -> json()
    }

    private companion object {
        const val STAGE = "stage"
        const val KIND = "kind"
        const val SUBJECT = "subject"
        const val ARGS = "args"
        const val RE = "re"
        const val TO = "to"
        const val BY = "by"
        const val BODY = "body"
        const val EVENT = "event"
        const val FACT = "fact"
        const val TRIGGER = "trigger"
        const val SIGNED = "signed"
        const val REQUEST = "request"
        const val RESULT = "result"
        const val REASON = "reason"
        const val MAILROOM = "mailroom"
        const val BORA = "bora"
        const val ROBOT = "robot:"
        const val BORA_ACTED = "bora.acted"
        const val SEAT = "seat"
        const val SEAT_NAME = "seat.name"
        const val SEAT_WAKE = "seat.wake"
        const val OF = "of"
        const val CODER = "coder"
        const val PROVISIONED = "provisioned"
        const val PRESENCE = "presence"
        const val PRESENT = "present"
        const val FLAG_THREAD = "flag_thread"
        const val FLAG = "flag"
        const val ASKED = "asked"
        const val RULE = "rule"
        const val MODE = "mode"
        const val REQUESTED = "requested"
        const val OK = "ok"
        const val FAILED = "failed"
        const val DELIVERED = "delivered"
        const val HELD = "held"
        const val REFUSED = "refused"
        const val STATE_KEY = "state"
        const val STATES_KEY = "states"
        const val SENDS = "sends"
        const val WAKES = "wakes"
        const val RULES = "rules"
        const val REFUSED_KEY = "refused"
        const val REFUSAL_CONTAINS = "refusal_contains"
        const val REFUSED_BY = "refused_by"
        /** A seat's death, as a walk reports it. */
        val DEATHS = setOf("seat.process_gone", "seat.protocol_closed")
        const val WAIT_MILLIS = 120_000L
        const val POLL_MILLIS = 1_000L
    }
}
