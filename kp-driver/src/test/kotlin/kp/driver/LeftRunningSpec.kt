package kp.driver

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.driver.claude.ClaudeAdapter
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * S2.1 DONE — when the specs end, no fake agent is left running. The check (ProjectConfig) reads the fakes' records;
 * here a fake outlives its office, started by a driver the office never closes — review-008's probe, B2 — and the
 * check still sees it after the office is cleaned up.
 */
class LeftRunningSpec : StringSpec({
    "a fake agent still running when its office is cleaned up is seen by the check when the specs end" {
        val office = Office()
        val agent = office.agent("{}")
        val unclosed = ClaudeAdapter.driver(office.tokenFile, office.seats, office.clock, { Answer.DENY }, office.listener)
        try {
            unclosed.start(office.session("coder-1", agent)).ok()
            office.close()

            office.root.exists() shouldBe false
            FakeAgents.alive() shouldContain agent.pids().single()
        } finally {
            unclosed.close()
        }
        eventually(10.seconds) { agent.pids().filter(::pidAlive) shouldBe emptyList() }
        office.root.toFile().deleteRecursively() // what the closing driver wrote to its transcript, if anything
    }
})
