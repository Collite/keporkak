package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.PostInstantiationExtension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.spec.Spec
import kp.daemon.Judgements
import kotlin.time.Duration

/**
 * After each of `kp-daemon`'s specs — the heroes' too, which run from the same classes — every line of every log its
 * daemons wrote is in a judgement, as after each of `kp-mailroom`'s (S1.10's DONE; S2.7 T7, issue #39 item 5).
 *
 * A spec that never ends fails by the default timeout (S3.0 T4; the retro of P2, candidate 8): `kp.spec.timeout`, which
 * the build gives every test task — 5m, or what KP_SPEC_TIMEOUT says. Each spec runs on a thread of its own, so the
 * timeout ends one blocked in a sleep, a wait or a read as it ends a suspended one; a spec that says otherwise keeps it.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration = Duration.parse(System.getProperty("kp.spec.timeout") ?: "5m")
    override val extensions: List<Extension> = listOf(
        object : PostInstantiationExtension {
            override suspend fun instantiated(spec: Spec): Spec = spec.also { if (it.blockingTest == null) it.blockingTest = true }
        },
        object : AfterSpecListener {
            override suspend fun afterSpec(spec: Spec) {
                val problems = Judgements.drain()
                check(problems.isEmpty()) { "${spec::class.simpleName}: a line of a log outside a judgement —\n" + problems.joinToString("\n") }
            }
        },
    )
}
