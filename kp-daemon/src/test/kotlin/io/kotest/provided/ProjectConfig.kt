package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.spec.Spec
import kp.daemon.Judgements

/**
 * After each of `kp-daemon`'s specs — the heroes' too, which run from the same classes — every line of every log its
 * daemons wrote is in a judgement, as after each of `kp-mailroom`'s (S1.10's DONE; S2.7 T7, issue #39 item 5).
 */
object ProjectConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(object : AfterSpecListener {
        override suspend fun afterSpec(spec: Spec) {
            val problems = Judgements.drain()
            check(problems.isEmpty()) { "${spec::class.simpleName}: a line of a log outside a judgement —\n" + problems.joinToString("\n") }
        }
    })
}
