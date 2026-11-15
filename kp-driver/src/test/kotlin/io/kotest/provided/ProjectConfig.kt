package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import kp.driver.FakeAgents

/** When the specs end, no fake agent — and no command one started — is left running (S2.1 DONE). */
object ProjectConfig : AbstractProjectConfig() {
    override suspend fun afterProject() {
        val alive = FakeAgents.alive()
        FakeAgents.clear()
        check(alive.isEmpty()) { "fake agents or their commands still running after the specs: pids $alive" }
    }
}
