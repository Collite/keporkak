package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.PostInstantiationExtension
import io.kotest.core.spec.Spec
import kp.driver.FakeAgents
import kotlin.time.Duration

/**
 * When the specs end, no fake agent — and no command one started — is left running (S2.1 DONE).
 *
 * A spec that never ends fails by the default timeout (S3.0 T4; the retro of P2, candidate 8): `kp.spec.timeout`, which
 * the build gives every test task — 5m, or what KP_SPEC_TIMEOUT says. The default is the build's alone (S3.8 T6): a
 * run outside the build has Kotest's own. Each spec runs on a thread of its own, so the timeout ends one blocked in a
 * sleep, a wait or a read as it ends a suspended one; a spec that says otherwise keeps it.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration? = System.getProperty("kp.spec.timeout")?.let(Duration::parse)
    override val extensions: List<Extension> = listOf(object : PostInstantiationExtension {
        override suspend fun instantiated(spec: Spec): Spec = spec.also { if (it.blockingTest == null) it.blockingTest = true }
    })

    override suspend fun afterProject() {
        val alive = FakeAgents.alive()
        FakeAgents.clear()
        check(alive.isEmpty()) { "fake agents or their commands still running after the specs: pids $alive" }
    }
}
