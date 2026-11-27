package kp.driver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.driver.acp.VendorSample

/**
 * KP/P3/S3.15 T1, T6 — the handshake's section on the vendor's words, played with no token (contracts.md §12, Amendment
 * 38). Bora reads these. The seam: the section as the handshake's evidence holds it ([VendorSample.section]), from words
 * the spec gives in place of what a run's transcripts heard; no agent is started.
 */
class TheVendorSampleSpec : StringSpec({
    "the handshake says of the vendor's words: the sample's own — the sample's shape; one window's utilization gone and a new key — another shape, naming each; every window's utilization gone — the window as any window; nothing heard — the sample unchecked" {
        // The sample's own words: the section as the handshake has written it since S3.14.
        VendorSample.section(listOf(VendorSample.SAMPLE)) shouldBe """
            ### The vendor's words against the contract's sample

            Heard in the sample's shape: each window's fraction, reset and status under `unifiedWindows`.

            The sample — the pinned adapter's `_meta["_claude/rateLimit"]` on the office, word for word from the handshake of 2026-11-24: `${VendorSample.SAMPLE}`

        """.trimIndent() + "\n"

        // The five-hour window without its fraction, and a key the sample has not: each named.
        val other = VendorSample.SAMPLE.replace(""""five_hour":{"utilization":0.46,""", """"five_hour":{""")
            .replace(""""isUsingOverage":false,""", """"isUsingOverage":false,"isUsingExtraUsage":true,""")
        verdict(other) shouldBe "Heard in another shape than the sample's — no unifiedWindows.five_hour.utilization number; a new isUsingExtraUsage boolean: " +
            "what the driver reads of it may have moved."

        // No window with its fraction: said of any window, as before.
        val none = VendorSample.SAMPLE.replace(""""utilization":0.46,""", "").replace(""""utilization":0.23,""", "")
        verdict(none) shouldBe "Heard in another shape than the sample's — no unifiedWindows.*.utilization number: what the driver reads of it may have moved."

        // Two runs' words, one the sample's own: a line each.
        VendorSample.section(listOf(VendorSample.SAMPLE, other)).lines()[2] shouldBe "Heard in the sample's shape: each window's fraction, reset and status under `unifiedWindows`."
        VendorSample.section(listOf(VendorSample.SAMPLE, other)).lines()[3] shouldBe verdict(other)

        VendorSample.section(emptyList()).lines()[2] shouldBe "Not heard in this run: the sample stands, unchecked."
        VendorSample.section(listOf("five_hour 0.46")).lines()[2].substringBefore(" — ") shouldBe "Heard in another shape than the sample's"
    }
})

/** What the section says of one run's [words]: its line after the heading. */
private fun verdict(words: String): String = VendorSample.section(listOf(words)).lines()[2]
