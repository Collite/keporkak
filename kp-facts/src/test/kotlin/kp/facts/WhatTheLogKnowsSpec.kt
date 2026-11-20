package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

/**
 * What the log knows, as the daemon hands it over (Amendment 32): `kp-facts` reads a stage's exit and the stages in an
 * active state from the fold's values, so the daemon compares none.
 */
class WhatTheLogKnowsSpec : FreeSpec({
    "the time of a stage's exit is when it entered `exited`; a stage in another state, or none, has no exit" {
        val log = KnownLog(states = mutableMapOf("KP/P3/S3.0" to "exited", "KP/P3/S3.1" to "building"), exits = mutableMapOf("KP/P3/S3.0" to "2026-11-21T10:00:00Z"))

        log.exitedAt("KP/P3/S3.0") shouldBe "2026-11-21T10:00:00Z"
        log.exitedAt("KP/P3/S3.1") shouldBe null
        log.exitedAt("KP/P3/S3.2") shouldBe null
    }

    "the stages in an active state are those whose state the rules call active" {
        val log = KnownLog(states = mutableMapOf("KP/P3/S3.0" to "exited", "KP/P3/S3.1" to "building", "KP/P3/S3.2" to "planned", "TOY/P0/S0.2" to "assigned"))

        log.active() shouldBe setOf("KP/P3/S3.1", "TOY/P0/S0.2")
    }
})
