package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import kp.spec.OwnThread
import kotlin.time.Duration

/**
 * A spec that never ends fails by the default timeout (S3.0 T4; the retro of P2, candidate 8): `kp.spec.timeout`, which
 * the build gives every test task — 5m, or what KP_SPEC_TIMEOUT says. The default is the build's alone (S3.8 T6): a
 * run outside the build has Kotest's own. Each case runs on a thread of its own ([OwnThread], S3.10 T2), so the timeout
 * ends one blocked in a sleep, a wait or a read as it ends a suspended one, whatever ran before it; a spec that sets
 * `blockingTest` itself keeps Kotest's.
 */
object ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration? = System.getProperty("kp.spec.timeout")?.let(Duration::parse)
    override val extensions: List<Extension> = listOf(OwnThread(this))
}
