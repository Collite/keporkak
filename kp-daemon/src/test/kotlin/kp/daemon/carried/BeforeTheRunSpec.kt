package kp.daemon.carried

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kp.daemon.OnTheOffice
import kp.ops.git
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists

/**
 * S3.5 T7 — before the run, `just rehearse first-carried-stage --world office --check` (`OnTheOffice.check`): the five
 * conditions and the walk's way read against the office as it is — the list, the plan's row, the slots, the Apps, the
 * toy's rulesets as GitHub tells them — changing nothing, and saying what is missing.
 */
class BeforeTheRunSpec : StringSpec({
    "on an office ready for the run it says ready, and changes nothing" {
        FixtureOffice().use { office ->
            val printed = ArrayList<String>()
            val head = git(office.project, "rev-parse", "HEAD")
            val code = OnTheOffice.check(office.root, FIRST_CARRIED_STAGE, FIXTURE_OFFICE, office.project, office.socket, office.apps.api) { printed += it }
            val text = printed.joinToString("\n")
            withClue(text) { code shouldBe 0 }
            text shouldContain "the service is stopped; TOY/P0/S0.2 has a list; the project checkout and every main checkout are clean; Bora is present"
            text shouldContain "it holds none of the walk's steps: a start plays the walk from its first"
            text shouldContain "list.tasks_total(stage=TOY/P0/S0.2) = 6"
            text shouldContain "ruleset 1 'master' holds master to update — kp-merger may bypass it: never; kp-coder: never"
            text shouldContain "ruleset 2 'master: green checks' holds master to required_status_checks — kp-merger may bypass it: never"
            text shouldContain "ready: nothing is missing"
            text shouldNotContain "ghs_"
            // Read, nothing changed: no log, no seat, the project as it was.
            office.project.resolve("mail").exists() shouldBe false
            git(office.project, "rev-parse", "HEAD") shouldBe head
            office.status(office.project) shouldBe ""
            office.github.requests.none { it.method != "GET" } shouldBe true
        }
    }

    "on an office that is not, it says what is missing" {
        FixtureOffice().use { office ->
            office.list.deleteExisting()
            office.toy.resolve("notes.md").toFile().writeText("by hand\n")
            val printed = ArrayList<String>()
            OnTheOffice.check(office.root, FIRST_CARRIED_STAGE, FIXTURE_OFFICE, office.project, office.socket, office.apps.api) { printed += it } shouldBe 1
            val text = printed.joinToString("\n")
            text shouldContain "  missing: TOY/P0/S0.2 has no list in ${office.project}"
            text shouldContain "  missing: the main checkout of kp-toy, ${office.toy}, is not clean: ?? notes.md"
            text shouldContain "missing ("
        }
    }
})
