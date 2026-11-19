package kp.daemon.seats

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * The driver's calls are `suspend` (architecture.md §5); the daemon brings no coroutine library of its own. A call is
 * started from the standard library and its end is a future: [later] does not wait for it, [blocking] does. Each step
 * of the call resumes on a thread of the daemon's own, never inside the driver's collector that completed what it
 * waited for: the call may then wait on the driver again without standing in its way.
 */
internal fun <T> later(call: suspend () -> T): CompletableFuture<T> {
    val done = CompletableFuture<T>()
    call.startCoroutine(Continuation(OwnThreads) { result -> result.fold({ done.complete(it) }, { done.completeExceptionally(it) }) })
    return done
}

internal fun <T> blocking(call: suspend () -> T): T = try {
    later(call).get()
} catch (failed: ExecutionException) {
    throw failed.cause ?: failed
}

/** Resumes each step of a call on a thread of the daemon's own. */
private object OwnThreads : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    private val threads = Executors.newCachedThreadPool { Thread(it, "kp-seats-call").apply { isDaemon = true } }

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = object : Continuation<T> {
        override val context: CoroutineContext get() = continuation.context
        override fun resumeWith(result: Result<T>) = threads.execute { continuation.resumeWith(result) }
    }
}
