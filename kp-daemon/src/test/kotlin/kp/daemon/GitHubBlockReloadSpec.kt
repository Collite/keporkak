package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kp.mailroom.Reload
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** S3.2 T2 — the block `github:` is hot-reloaded like any pool file: a broken one is refused and the block in force stays. */
class GitHubBlockReloadSpec : FreeSpec({
    "a hot reload with a broken block is refused, with the line; the block in force stays, and a sound one then takes its place" {
        val rules = TestOffice.copyOfTheRules()
        TestOffice(root = rules).use { office ->
            val before = office.office.rules.hash
            val offices = rules.resolve("pool/offices.yaml")
            val master = offices.readText()

            offices.writeText(master.replace("    coder: {app: kp-coder,", "    coder: {app: kp-coders,"))
            val refused = office.office.reloadIfChanged()

            refused.shouldBeInstanceOf<Reload.Rejected>()
            refused.problems.map { it.toString() } shouldContain
                "pool/offices.yaml: github: seats.coder names the App 'kp-coders', which github.apps does not hold (line ${lineOf(offices, "    coder: {app: kp-coders,")})"
            office.office.rules.hash shouldBe before
            office.office.rules.ruleSet.pool.github!!.seats.getValue("coder").app shouldBe "kp-coder"

            offices.writeText(master.replace("    kp-coder: {app: 5849303, installation: 184046595}", "    kp-coder: {app: 5849303, installation: 184046596}"))
            office.office.reloadIfChanged().shouldBeInstanceOf<Reload.Accepted>()
            office.office.rules.ruleSet.pool.github!!.apps.getValue("kp-coder").installation shouldBe 184046596
        }
    }
})
