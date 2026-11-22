package kp.mailroom

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the mailroom's operations are performed (contracts.md §7, Amendment 36): each on a thread of its own, outside
 * the mailroom's lock. The operations of one lane — a checkout, a seat — are performed one after another, in the order
 * they were handed over: the next begins once the one before has ended, its result judged. Those of two lanes, and
 * those of none, run side by side.
 */
internal class Lanes : AutoCloseable {
    /** One operation: it performs, and calls `ended` once — when its result has been judged. */
    fun interface Job {
        fun run(ended: () -> Unit)
    }

    private val lock = Object()
    /** Per lane, the operations waiting for the one being performed there. */
    private val waiting = HashMap<String, ArrayDeque<Job>>()
    /** The lanes with an operation being performed. */
    private val running = HashSet<String>()
    /** Operations begun whose performer has not yet returned, and results being judged: what [settle] waits for. */
    private var busy = 0
    private var closed = false
    private val threads: ExecutorService = Executors.newCachedThreadPool { Thread(it, "kp-operation").apply { isDaemon = true } }

    /** Hands [job] to [lane]: begun now when nothing is performed there — on no lane, always — else after what waits. */
    fun hand(lane: String?, job: Job) {
        synchronized(lock) {
            if (closed) return
            if (lane != null && !running.add(lane)) {
                waiting.getOrPut(lane, ::ArrayDeque) += job
                return
            }
            busy++
        }
        begin(lane, job)
    }

    /** [judging] — a result judged — counts as work in hand until it returns: what it hands over is begun before it ends. */
    fun <T> answering(judging: () -> T): T {
        synchronized(lock) { busy++ }
        try {
            return judging()
        } finally {
            done()
        }
    }

    /**
     * Waits, at most [millis], until every operation begun has returned from its performer — one whose answer comes later
     * waits for it without holding anything — and no result is being judged. True when that is so. An operation's own
     * thread does not wait for itself: asked from one, it answers false at once.
     */
    fun settle(millis: Long): Boolean = synchronized(lock) {
        if (performing.get()) return false
        val end = System.nanoTime() + millis * NANOS_PER_MILLI
        while (busy > 0) {
            val left = (end - System.nanoTime()) / NANOS_PER_MILLI
            if (left <= 0) return false
            lock.wait(left)
        }
        true
    }

    /** No operation is begun any more; those being performed run on, and their results are no one's. */
    override fun close() {
        synchronized(lock) {
            closed = true
            waiting.clear()
            lock.notifyAll()
        }
        threads.shutdown()
    }

    private fun begin(lane: String?, job: Job) {
        val ended = AtomicBoolean(false)
        try {
            threads.execute {
                performing.set(true)
                try {
                    job.run { if (ended.compareAndSet(false, true)) next(lane) }
                } finally {
                    performing.set(false)
                    done()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            done()
        }
    }

    /** The operation on [lane] has ended: the next one waiting there begins. */
    private fun next(lane: String?) {
        if (lane == null) return
        val job = synchronized(lock) {
            val job = waiting[lane]?.removeFirstOrNull()
            if (job == null) {
                running -= lane
                waiting -= lane
                null
            } else job.also { busy++ }
        }
        job?.let { begin(lane, it) }
    }

    private fun done() = synchronized(lock) {
        busy--
        lock.notifyAll()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        /** Whether this thread is performing an operation. */
        val performing: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    }
}
