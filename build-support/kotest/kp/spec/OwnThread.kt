package kp.spec

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.config.TestConfigResolver
import io.kotest.engine.test.TestResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Each test case on a thread of its own, interrupted at its timeout while it runs there (S3.10 T2, D-106): a spec that
 * never ends fails by its timeout, blocked or suspended, whatever ran before it. Compiled into every module that has
 * specs (the root build), and named by each module's own ProjectConfig with that config: the timeout is the one Kotest
 * resolves for the case — the build's `kp.spec.timeout` unless the case or its spec says another.
 *
 * Kotest's `blockingTest` did this until S3.10, and could leave a suspended case waiting for ever (S3.8's Found lines): at
 * the timeout it shuts its executor down with `shutdownNow()` at the same moment its coroutine timeout hands the suspended
 * case's resumption to that executor, and `shutdownNow()` throws away what is queued. Here the executor is never shut down
 * while its case runs: a suspended case is ended by Kotest's coroutine timeout, its resumption run on this thread; a case
 * blocked on this thread — a sleep, a wait, a read — by the interrupt, sent only while the thread runs it. A spec or case
 * that sets `blockingTest` itself keeps Kotest's.
 */
class OwnThread(private val config: AbstractProjectConfig) : TestCaseExtension {
    override suspend fun intercept(testCase: TestCase, execute: suspend (TestCase) -> TestResult): TestResult {
        val said = testCase.config?.blockingTest ?: testCase.spec.blockingTest ?: testCase.spec.defaultTestConfig?.blockingTest
        if (testCase.type != TestType.Test || said != null) return execute(testCase)
        val timeout = TestConfigResolver(config).timeout(testCase)
        val worker = AtomicReference<Thread>()
        val pool = Executors.newSingleThreadExecutor { task -> Thread(task, "kp-spec ${testCase.name.name}").also { it.isDaemon = true; worker.set(it) } }
        val busy = AtomicBoolean(false)
        val own = Executor { task ->
            pool.execute {
                busy.set(true)
                try {
                    task.run()
                } finally {
                    busy.set(false)
                }
            }
        }
        val interrupt = TIMEOUTS.schedule({ if (busy.get()) worker.get()?.interrupt() }, timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        return try {
            withContext(own.asCoroutineDispatcher()) { execute(testCase) }
        } finally {
            interrupt.cancel(false)
            pool.shutdown()
        }
    }

    private companion object {
        /** One thread that sends every case's interrupt; a case that ends first takes its interrupt back. */
        val TIMEOUTS = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "kp-spec-timeouts").also { it.isDaemon = true } }
    }
}
