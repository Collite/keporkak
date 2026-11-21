package kp.daemon.hero

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

/**
 * KP/P3/S3.6 T1 — `just hero` plays every order (Bora reads this). The seam: the daemon as a hero plays a walk — the real
 * loader, log, engine, mailroom and robots on a throwaway project — once per order of the events a step marks as
 * unordered, each order on a daemon of its own; and `scripts/check-rules.py --trace`, whose `END` line gives the walker's
 * end of each order. An end here is every stage's state and every seat's.
 */
class TheOrdersSpec : FreeSpec({
    extension(HeroReport) // what `just hero` prints

    "events a walk marks as unordered are played through the mailroom in every order, each ending as the walker's" - {
        "a fixture walk with three of them — a killed seat's two end reports and the kill's result — in its six orders" {
            val orders = Orders.of("a-killed-seats-ends").also(HeroReport::orders)

            orders.played.map { it.labels } shouldBe listOf(
                listOf("process-gone", "protocol-closed", "killed"), listOf("process-gone", "killed", "protocol-closed"),
                listOf("protocol-closed", "process-gone", "killed"), listOf("protocol-closed", "killed", "process-gone"),
                listOf("killed", "process-gone", "protocol-closed"), listOf("killed", "protocol-closed", "process-gone"),
            )
            orders.played.map { it.end }.distinct() shouldBe listOf(Orders.End(mapOf("S3.6" to "building"), mapOf("coder-1" to "ready")))
            orders.played.filter { it.end != it.walkers } shouldBe emptyList()
        }
        "every walk on master that marks unordered events or binds two seats to a stage" {
            val walks = Orders.ofTheWalksOnMaster().onEach(HeroReport::orders)

            walks.map { it.walk } shouldBe listOf("seat-deaths-paused-D-81", "seat-ends-by-operation-D-85")
            walks.map { it.played.size } shouldBe listOf(1, 6)
            walks.flatMap { it.played }.filter { it.end != it.walkers } shouldBe emptyList()
        }
    }
})
