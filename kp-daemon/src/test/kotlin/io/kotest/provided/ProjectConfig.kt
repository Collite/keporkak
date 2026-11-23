package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.spec.Spec
import kp.daemon.Judgements
import kp.spec.OwnThread
import kotlin.time.Duration

/**
 * After each of `kp-daemon`'s specs — the heroes' too, which run from the same classes — every line of every log its
 * daemons wrote is in a judgement, as after each of `kp-mailroom`'s (S1.10's DONE; S2.7 T7, issue #39 item 5).
 *
 * A spec that never ends fails by the default timeout (S3.0 T4; the retro of P2, candidate 8): `kp.spec.timeout`, which
 * the build gives every test task — 5m, or what KP_SPEC_TIMEOUT says. The default is the build's alone (S3.8 T6): a
 * run outside the build has Kotest's own. Each case runs on a thread of its own ([OwnThread], S3.10 T2), so the timeout
 * ends one blocked in a sleep, a wait or a read as it ends a suspended one, whatever ran before it; a spec that sets
 * `blockingTest` itself keeps Kotest's.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration? = System.getProperty("kp.spec.timeout")?.let(Duration::parse)
    override val extensions: List<Extension> = listOf(
        OwnThread(this),
        object : AfterSpecListener {
            override suspend fun afterSpec(spec: Spec) {
                val problems = Judgements.drain()
                check(problems.isEmpty()) { "${spec::class.simpleName}: a line of a log outside a judgement —\n" + problems.joinToString("\n") }
            }
        },
    )
}
