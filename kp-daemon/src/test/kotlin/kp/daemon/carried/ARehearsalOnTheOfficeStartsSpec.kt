package kp.daemon.carried

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kp.daemon.NoSeats
import kp.daemon.Office
import kp.daemon.OnTheOffice
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.ops.git
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S3.5 T1 — when a rehearsal on the office starts (`Rehearsal.onTheOffice`, `OnTheOffice.missing`; contracts.md §12,
 * Amendment 46): only on a stopped service, a clean project checkout, clean main checkouts, a stage with a list and Bora
 * present — each of the five missing alone, nothing is started and the reason is said — and never with a walk that gives
 * the world: a fact of its table, of a stage's or of a step's facts, a fact an event step reports other than a seat's
 * death, or an operation's result, is refused, named (review-039 B1).
 */
class ARehearsalOnTheOfficeStartsSpec : StringSpec({
    "a rehearsal on the office supplies nothing: a walk with a facts: table or an operation's result is refused naming it" {
        Carried().use { carried ->
            val text = FIRST_CARRIED_STAGE.readText()
            val tabled = carried.office.folder.resolve("tabled.yaml").apply { writeText(text.replace("\nsteps:\n", "\nfacts:\n  list.tasks_total: 6\nsteps:\n")) }
            val resulted = carried.office.folder.resolve("resulted.yaml").apply {
                writeText(text.replace("- id: worktree\n  operation: worktree.create\n  request: approval\n", "- id: worktree\n  operation: worktree.create\n  request: approval\n  result: ok\n"))
            }
            shouldThrow<OnTheOffice.Refusal> { Carried(carried.office, walk = tabled).start() }.message shouldContain "its table gives list.tasks_total"
            shouldThrow<OnTheOffice.Refusal> { Carried(carried.office, walk = resulted).start() }.message shouldContain "step 3 (worktree) gives worktree.create's result"
            carried.nothingStarted()
        }
    }

    "a walk whose stage gives a fact (stages: … facts:) is refused naming it, and nothing is started" {
        Carried().use { carried ->
            val staged = carried.office.folder.resolve("staged.yaml").apply {
                writeText(FIRST_CARRIED_STAGE.readText().replace("\nsteps:\n", "\nstages:\n  TOY/P0/S0.2:\n    facts:\n      list.tasks_total: 6\nsteps:\n"))
            }
            shouldThrow<OnTheOffice.Refusal> { Carried(carried.office, walk = staged).start() }.message shouldContain "its stage TOY/P0/S0.2 gives list.tasks_total"
            carried.nothingStarted()
        }
    }

    "a walk whose step gives a fact (a step's facts:, as H-1′ gives ten) is refused naming it, and nothing is started" {
        Carried().use { carried ->
            val stepped = carried.office.folder.resolve("stepped.yaml").apply {
                writeText(FIRST_CARRIED_STAGE.readText().replace("- id: proposal\n  who: teamlead\n", "- id: proposal\n  who: teamlead\n  facts:\n    list.tasks_total: 6\n"))
            }
            shouldThrow<OnTheOffice.Refusal> { Carried(carried.office, walk = stepped).start() }.message shouldContain "step 1 (proposal) gives list.tasks_total"
            carried.nothingStarted()
        }
    }

    "a walk with an event step that reports a fact other than a seat's death is refused naming it, and nothing is started" {
        Carried().use { carried ->
            val reported = carried.office.folder.resolve("reported.yaml").apply {
                writeText(FIRST_CARRIED_STAGE.readText().replace("- id: worktree\n", "- id: acted\n  who: daemon\n  event:\n    fact: bora.acted\n- id: worktree\n"))
            }
            shouldThrow<OnTheOffice.Refusal> { Carried(carried.office, walk = reported).start() }.message shouldContain "step 3 (acted) reports bora.acted"
            carried.nothingStarted()
        }
    }

    "it starts only on a stopped service:a daemon on the socket, or a writer holding the log — nothing is started, and that is said" {
        Carried().use { carried ->
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { daemon ->
                daemon.bind(UnixDomainSocketAddress.of(carried.office.socket))
                shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldBe
                    "nothing is started: the service is running: a daemon answers on ${carried.office.socket} — `just stop` first"
            }
            Files.deleteIfExists(carried.office.socket)
            val lock = carried.office.project.resolve("mail").createDirectories().resolve(".writer.lock")
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldBe
                        "nothing is started: the service is running: a writer holds the log ${carried.office.project.resolve("mail")} — `just stop` first"
                }
            }
            carried.nothingStarted()
        }
    }

    "it starts only for a stage with a list" {
        Carried().use { carried ->
            carried.office.list.deleteExisting()
            git(carried.office.project, "commit", "--quiet", "-am", "the list of S0.2 taken out")
            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldContain "nothing is started: TOY/P0/S0.2 has no list in ${carried.office.project}"
            carried.nothingStarted()
        }
    }

    "it starts only on a clean project checkout, and clean main checkouts" {
        Carried().use { carried ->
            carried.office.project.resolve("notes.md").writeText("a note\n")
            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldBe
                "nothing is started: the project checkout ${carried.office.project} is not clean: ?? notes.md"
            carried.office.project.resolve("notes.md").deleteExisting()
            carried.office.toy.resolve("README.md").writeText("changed by hand\n")
            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldBe
                "nothing is started: the main checkout of kp-toy, ${carried.office.toy}, is not clean: M README.md"
            carried.nothingStarted()
        }
    }

    "it starts only while Bora is present" {
        Carried().use { carried ->
            // Bora went away on the office's log: `sudo kp away`, on the service as it ran before.
            val office = Office.start(FIXTURE_OFFICE, carried.office.project, carried.office.root, FactSource { _, _ -> null }, Operations { _, _ -> }, NoSeats,
                Clock { Instant.now() })
            office.submit(Sender.Bora(signed = true), Json.parseToJsonElement("""{"kind": "away", "subject": "away", "args": {}}""") as JsonObject)
            office.close()
            OnTheOffice.presence(carried.office.project, FIXTURE_OFFICE) shouldBe "absent"
            val before = carried.lines().size
            shouldThrow<OnTheOffice.Refusal> { carried.start() }.message shouldBe
                "nothing is started: Bora is absent in the office's log: run any `sudo kp …` on the running service, then `just stop`"
            carried.lines().size shouldBe before
            carried.office.seats.listDirectoryEntries().shouldBeEmpty()
        }
    }
})

/** Nothing of a rehearsal was started on the fixture office: no log, no seat, no daemon on its socket. */
private fun Carried.nothingStarted() {
    office.project.resolve("mail").resolve("_office").exists() shouldBe false
    office.seats.listDirectoryEntries().shouldBeEmpty()
    office.socket.exists() shouldBe false
}
