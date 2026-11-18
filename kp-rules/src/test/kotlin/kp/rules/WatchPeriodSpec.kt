package kp.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** S2.4 — `pool/offices.yaml`'s `watch` (contracts.md §9, Amendment 22): how often the daemon reports its seats' facts. */
class WatchPeriodSpec : StringSpec({
    "an office says how often its seats are watched, as a duration; master's watches Panda's every 30s" {
        filesOnMaster().let { Rules.load(it) as Loaded.Valid }.ruleSet.pool.offices.getValue("panda").watchEvery shouldBe "30s"
    }

    "a watch period that is missing, no duration, zero, or beside an unknown field is a problem" {
        problemsAfter("pool/offices.yaml", "    watch: {every: 30s}\n", "") shouldBe listOf("pool/offices.yaml: panda: watch.every must be a duration such as 30s")
        problemsAfter("pool/offices.yaml", "watch: {every: 30s}", "watch: {every: 30}") shouldBe listOf("pool/offices.yaml: panda: watch.every must be a duration such as 30s")
        problemsAfter("pool/offices.yaml", "watch: {every: 30s}", "watch: {every: 0s}") shouldBe listOf("pool/offices.yaml: panda: watch.every must be a duration such as 30s")
        problemsAfter("pool/offices.yaml", "watch: {every: 30s}", "watch: {every: 30s, limit: 5m}") shouldBe listOf("pool/offices.yaml: panda: unknown field 'watch.limit'")
    }
})
