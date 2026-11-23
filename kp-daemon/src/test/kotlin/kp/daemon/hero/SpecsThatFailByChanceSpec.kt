@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.checkout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.10 T1 — `just hero` compares whole ends (Bora reads this). The seam: `just hero`'s report of a walk played in
 * every order of its unordered events, each order through the mailroom on a daemon of its own, beside the walker's end
 * of the same order (`scripts/check-rules.py --trace`, its `END` line).
 */
class SpecsThatFailByChanceSpec : StringSpec({
    extension(HeroReport) // what `just hero` prints

    "`just hero` holds each order's end against the walker's whole end — states, counters, deliveries and refusals, operations requested and waiting: an order that differs only in a counter is red" {
        val orders = Orders.of("a-killed-seats-ends").also(HeroReport::orders)

        orders.played.first().end shouldBe Orders.End(
            stages = mapOf("S3.6" to "building"),
            seats = mapOf("coder-1" to "ready"),
            counters = mapOf("S3.6" to mapOf("seat_gone_events" to 1)),
            delivered = mapOf("kill" to 1, "re-seated" to 1, "seat-gone" to 1),
            refused = emptyMap(),
            operations = listOf("seat.kill (kill coder-1)", "seat.reseat (seat-gone coder-1)"),
            waiting = emptyList(),
        )
        orders.report() shouldBe "a-killed-seats-ends: 6 orders through the mailroom, each ending as the walker's"

        // The walker on rules that count a seat gone twice: every order ends in the same states, with another counter.
        val twice = Orders.of(Hero.walkFile("a-killed-seats-ends"), walker = rulesCountingASeatGoneTwice())
        twice.played.map { it.walkers?.stages to it.walkers?.seats }.distinct() shouldBe listOf(mapOf("S3.6" to "building") to mapOf("coder-1" to "ready"))
        twice.report() shouldStartWith "a-killed-seats-ends: 6 orders through the mailroom, 6 ending otherwise than the walker's"
        twice.report() shouldContain "counters {S3.6={seat_gone_events=1}}, the walker's {S3.6={seat_gone_events=2}}"
    }
})

/** A root for the walker — its own copy of `check-rules.py` and of `rules/`, the rest the checkout's — whose seat-gone counts its counter twice. */
private fun rulesCountingASeatGoneTwice(): Path {
    val root = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "walker-")
    checkout.resolve("rules").copyToRecursively(root.resolve("rules"), followLinks = false, overwrite = false)
    checkout.resolve("scripts/check-rules.py").copyTo(root.resolve("scripts").createDirectories().resolve("check-rules.py"))
    for (folder in listOf("robots", "pool", "agents")) root.resolve(folder).createSymbolicLinkPointingTo(checkout.resolve(folder))
    val lifecycle = root.resolve("rules/stage-lifecycle.yaml")
    val once = "  - count: seat_gone_events\n  - do: seat.reseat\n"
    lifecycle.readText() shouldContain once
    lifecycle.writeText(lifecycle.readText().replaceFirst(once, "  - count: seat_gone_events\n  - count: seat_gone_events\n  - do: seat.reseat\n"))
    Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
    return root
}
