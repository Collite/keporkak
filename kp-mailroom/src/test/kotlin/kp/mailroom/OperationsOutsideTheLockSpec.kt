package kp.mailroom

import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.model.Message
import kp.model.Op
import kp.model.plain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val OTHER = "KP/P2/S2.2"
private const val APPROVAL = """{"kind": "approval", "stage": "$STAGE", "subject": "approved"}"""
private const val APPROVAL_OF_OTHER = """{"kind": "approval", "stage": "$OTHER", "subject": "approved"}"""
private const val COMPACT = """{"kind": "compact", "stage": "$STAGE", "subject": "compact", "args": {"seat": "coder-1"}}"""

/**
 * KP/P3/S3.3 — operations on the office, T1's first sentence (contracts.md §7, Amendment 36). The seam is the mailroom's
 * own entries, with performers — and a seat's prompt — that block until the spec lets them go. An operation, a wake
 * too, begins once the judgement that asked for it is on disk and runs outside the mailroom's lock: what arrives
 * meanwhile is judged and written. Operations on one checkout, or on one seat, are performed one after another, in the
 * order requested; on two checkouts, side by side.
 */
class OperationsOutsideTheLockSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "while worktree.create runs, Bora's approval of another stage is delivered, and its judged line is on disk before the operation's result" {
        val office = Office(seed = "proposed", others = mapOf(OTHER to "proposed"))
        val performer = Held().also { office.performing(it) }
        try {
            val first = within { office.boraNow(APPROVAL) }
            performer.awaitEntered("worktree.create $STAGE")

            val other = within { office.boraNow(APPROVAL_OF_OTHER) }
            performer.release("worktree.create $STAGE")
            office.mailroom.settle()

            other.status shouldBe Status.DELIVERED
            office.readBack().judgedLineOf(other.id) shouldBeLessThan office.readBack().resultOf("worktree.create", first.id)
        } finally {
            performer.releaseAll()
            office.mailroom.settle()
        }
    }

    "a wake that takes as long as the spec holds it delays no other arrival (issue #42)" {
        val office = Office(seed = "proposed")
        val seats = HeldSeats().also { office.performing(Operations { _, done -> done(OperationResult.Ok()) }, it) }
        office.facts.set("seat.context_used", 0.95)
        try {
            val compact = within { office.sendNow("pool-1", COMPACT) }
            seats.awaitPrompted("coder-1")

            val approval = within { office.boraNow(APPROVAL) }

            approval.status shouldBe Status.DELIVERED
            ReadBack(office.project).ops(compact.id) shouldContainExactly listOf("seat.compact requested", "seat.wake requested", "seat.compact ok")
        } finally {
            seats.releaseAll()
            office.mailroom.settle()
        }
    }

    "two operations on different checkouts run side by side: both performers are entered before either is let go" {
        val office = Office(seed = "proposed", others = mapOf(OTHER to "proposed"))
        val performer = Held(on = { request -> "the worktree of ${request.stage()}" }).also { office.performing(it) }
        try {
            within { office.boraNow(APPROVAL) }
            within { office.boraNow(APPROVAL_OF_OTHER) }

            performer.awaitEntered("worktree.create $STAGE")
            performer.awaitEntered("worktree.create $OTHER")
            performer.events shouldContainExactly listOf("enter worktree.create $STAGE", "enter worktree.create $OTHER")
        } finally {
            performer.releaseAll()
            office.mailroom.settle()
        }
    }

    "two operations on one checkout end in the order they were requested, though the second performer is the faster" {
        val office = Office(seed = "proposed", others = mapOf(OTHER to "proposed"))
        val performer = Held(on = { "the main checkout of keporkak" }, holds = { it == "worktree.create $STAGE" }).also { office.performing(it) }
        try {
            val first = within { office.boraNow(APPROVAL) }
            performer.awaitEntered("worktree.create $STAGE")
            val second = within { office.boraNow(APPROVAL_OF_OTHER) }

            performer.release("worktree.create $STAGE")
            office.mailroom.settle()

            performer.events shouldContainExactly listOf("enter worktree.create $STAGE", "end worktree.create $STAGE", "enter worktree.create $OTHER", "end worktree.create $OTHER")
            office.readBack().resultOf("worktree.create", first.id) shouldBeLessThan office.readBack().resultOf("worktree.create", second.id)
        } finally {
            performer.releaseAll()
            office.mailroom.settle()
        }
    }

    "two operations on one seat are performed one after the other, in the order requested: its compaction, then its wake" {
        val office = Office(seed = "proposed")
        val events = CopyOnWriteArrayList<String>()
        val performer = Held(events).also { office.performing(it, HeldSeats(events, holds = false)) }
        office.facts.set("seat.context_used", 0.95)
        try {
            val compact = within { office.sendNow("pool-1", COMPACT) }
            performer.awaitEntered("seat.compact $STAGE")

            performer.release("seat.compact $STAGE")
            office.mailroom.settle()

            events shouldContainExactly listOf("enter seat.compact $STAGE", "end seat.compact $STAGE", "prompt coder-1")
            office.readBack().ops(compact.id) shouldContainExactly listOf("seat.compact requested", "seat.wake requested", "seat.compact ok", "seat.wake ok")
        } finally {
            performer.releaseAll()
            office.mailroom.settle()
        }
    }
})

/** [block], run on a thread of its own: its value once it returns — failing when it has not within [seconds]. */
fun <T> within(seconds: Long = 10, block: () -> T): T {
    val value = AtomicReference<Result<T>>()
    val runner = thread(name = "kp-spec-within") { value.set(runCatching(block)) }
    runner.join(TimeUnit.SECONDS.toMillis(seconds))
    if (runner.isAlive) fail("it did not return in $seconds s: the mailroom waited for an operation to end")
    return value.get().getOrThrow()
}

/** Bora's message, submitted, and the seat's: answered once their lines are on disk, whatever runs after them. */
fun Office.boraNow(input: String): Answer = mailroom.submit(Sender.Bora(true), json(input)).answered().also { submitted += it.id }
fun Office.sendNow(seat: String, input: String): Answer = mailroom.submit(Sender.Seat(seat), json(input)).answered().also { submitted += it.id }

/** The office's mailroom started again with [operations] as its performers and [seats] as its seats. */
fun Office.performing(operations: Operations, seats: Deliveries = this.seats) {
    stop()
    mailroom = Mailroom.start(project, panda, texts, facts, operations, seats, clock, journal = journal::add)
}

/** The stage a request is about: its `with.stage`, as the rules hand it over. */
private fun OperationRequest.stage(): String = (with.fields["stage"] as? Datum.Text)?.value ?: "no stage"

/**
 * A performer that blocks in each operation [holds] picks — named `<operation> <stage>` — until the spec lets it go,
 * and answers every other at once; each entry and each end is in [events], in order. [on] says what each operation
 * works on: nothing another one works on, unless the spec says.
 */
class Held(
    val events: MutableList<String> = CopyOnWriteArrayList(),
    private val on: (OperationRequest) -> String? = { null },
    private val holds: (String) -> Boolean = { true },
) : Operations {
    private val entered = ConcurrentHashMap<String, CountDownLatch>()
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    override fun on(request: OperationRequest): String? = on.invoke(request)

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
        val name = "${request.operation} ${request.stage().takeUnless { it == "no stage" } ?: STAGE}"
        events += "enter $name"
        latch(entered, name).countDown()
        if (holds(name)) latch(gates, name).await(60, TimeUnit.SECONDS)
        events += "end $name"
        done(OperationResult.Ok())
    }

    fun awaitEntered(name: String) {
        if (!latch(entered, name).await(10, TimeUnit.SECONDS)) fail("$name was not performed in 10 s — performed so far: $events")
    }

    fun release(name: String) = latch(gates, name).countDown()

    fun releaseAll() = gates.values.forEach(CountDownLatch::countDown)

    private fun latch(of: ConcurrentHashMap<String, CountDownLatch>, name: String) = of.computeIfAbsent(name) { CountDownLatch(1) }
}

/** Seats, each idle, whose prompt blocks until the spec lets it go — unless not [holds]; each prompt is in [events]. */
class HeldSeats(private val events: MutableList<String> = CopyOnWriteArrayList(), private val holds: Boolean = true) : Deliveries {
    private val prompted = ConcurrentHashMap<String, CountDownLatch>()
    private val gate = CountDownLatch(1)

    override fun idle(seat: String) = true

    override fun wake(seat: String, message: Message): Boolean {
        events += "prompt $seat"
        prompted.computeIfAbsent(seat) { CountDownLatch(1) }.countDown()
        if (holds) gate.await(60, TimeUnit.SECONDS)
        return true
    }

    fun awaitPrompted(seat: String) {
        if (!prompted.computeIfAbsent(seat) { CountDownLatch(1) }.await(10, TimeUnit.SECONDS)) fail("$seat was not prompted in 10 s — $events")
    }

    fun releaseAll() = gate.countDown()
}

/** The `seq` of the `judged` line that closes the judgement of the message [id]. */
fun ReadBack.judgedLineOf(id: String): Int {
    val message = messages.single { it.id.value == id }.seq.toLong()
    return lines.first { it.plain()["event"] == "judged" && (it.plain()["first"] as java.math.BigDecimal).toLong() == message }.seq.toInt()
}

/** The `seq` of the result — `ok` or `failed` — of [operation] for the message [cause]. */
fun ReadBack.resultOf(operation: String, cause: String): Int =
    records<Op>().first { it.op == operation && it.cause == cause && it.phase != "requested" }.seq.toInt()
