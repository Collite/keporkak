package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kp.driver.Answer
import kp.driver.Explained
import kp.driver.PermissionRequest
import kp.driver.OfferedOption
import kp.driver.Outcome
import kp.driver.ok
import kp.model.SeatId
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S2.2's five specs, for Bora. Seam: the builder and the answerer in `kp-driver`, given master's personas, renders,
 * skills and packaging data, and a temporary seats' root; the hook scripts of `hooks/` run as processes with the
 * probe's JSON on stdin (Hooks.kt).
 */
class PackagingAndHooksSpec : StringSpec({
    val allow = PermissionDefaults(writeInside = Answer.ALLOW, writeOutside = Answer.DENY, read = Answer.ALLOW)

    "a config home holds, for its role, the render as CLAUDE.md, the skills its persona lists and no other, settings.json and permissions.json — and what an earlier build put there and this one does not is gone" {
        Sources().use { sources ->
            val homes = ConfigHomes(sources.root, sources.seats)

            val home = homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, allow).ok().path

            home.resolve("CLAUDE.md").readBytes() shouldBe sources.root.resolve("renders/coder.md").readBytes()
            sources.names(home.resolve("skills")) shouldContainExactly listOf("context7-mcp", "diagnosing-bugs", "handoff-pr", "tdd")
            sources.names(home) shouldContainExactly listOf("CLAUDE.md", "permissions.json", "settings.json", "skills")

            sources.persona("coder") { it.replace("skills: [context7-mcp, tdd, diagnosing-bugs, handoff-pr]", "skills: [tdd]") }
            home.resolve("projects").also(Files::createDirectories).resolve("session.jsonl").writeText("Claude Code's own")
            homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, allow).ok()

            sources.names(home.resolve("skills")) shouldContainExactly listOf("tdd")
            home.resolve("projects/session.jsonl").readText() shouldBe "Claude Code's own"
        }
    }

    "a call a deny: word covers is denied with the border's name — by the hook from permissions.json, and by the answerer when it arrives as a permission request — and the two agree on every row of one table" {
        Sources().use { sources ->
            val table = TableRows.rows()
            table.map { it.word }.distinct() shouldContainExactlyInAnyOrder listOf("side-channels", "push-master", "pin-files", "outside-worktree",
                "live-target", "browser", "write-code", "write-project", "write-project-beyond-own-list", "write-project-beyond-own-report", "demo-cluster")

            for (row in table) {
                val permissions = sources.permissions(row.word, row.profile, row.worktree, allow)

                Hooks.deny(permissions, row.tool, row.input) shouldBe row.expected
                sources.answer(permissions, row.tool, row.input) shouldBe row.expected
            }
        }
    }

    "a call no rule covers is answered by the default: allowed inside the working directory, denied outside" {
        Sources().use { sources ->
            // The principal has no stage: its working directory is the project checkout, and no rule of its covers a write.
            val noStage = sources.places.copy(worktree = sources.places.project)
            ConfigHomes(sources.root, sources.seats).build(SeatId("principal-1"), "principal", "keporkak", noStage, allow).ok()
            val answerer = CompiledAnswerer(sources.seats)
            fun write(path: String) = PermissionRequest(SeatId("principal-1"), "t1", "Write", "edit", Json.parseToJsonElement("""{"file_path":"$path","content":"x"}"""),
                listOf(OfferedOption("allow-once", "Yes", "allow_once"), OfferedOption("reject", "No", "reject_once")), name = "Write")

            answerer.explained(write("${sources.places.project}/notes.md")) shouldBe Explained(Answer.ALLOW, "inside the working directory")
            answerer.explained(write("/tmp/elsewhere/notes.md")) shouldBe Explained(Answer.DENY, "outside the working directory")
        }
    }

    "the stop hook relays a block with its reason, and lets the turn end when the daemon does not answer" {
        StopStandIn(answer = """{"block": true, "reason": "an unread message waits: call inbox"}""").use { daemon ->
            val relayed = Hooks.stopGate(limitSeconds = 3, url = daemon.url, token = "session-token-1")

            relayed shouldBe """{"decision":"block","reason":"an unread message waits: call inbox"}"""
            daemon.authorizations shouldContainExactly listOf("Bearer session-token-1")
        }
        StopStandIn(answer = null).use { silent ->
            val started = System.nanoTime()
            val relayed = Hooks.stopGate(limitSeconds = 2, url = silent.url, token = "session-token-2")

            relayed shouldBe ""
            (System.nanoTime() - started) / 1_000_000 shouldBeLessThan 5_000L
        }
    }

    "a persona that uses a deny: word the packaging data does not map is reported, and no home is built for it" {
        Sources().use { sources ->
            sources.persona("reviewer") { it.replace("deny: [side-channels,", "deny: [teleport, side-channels,") }

            val built = ConfigHomes(sources.root, sources.seats).build(SeatId("reviewer-1"), "reviewer", "keporkak", sources.places, allow)

            (built as Outcome.Failed).reason shouldContain "persona reviewer: deny word 'teleport' is not mapped by the packaging data"
            sources.seats.resolve("reviewer-1").exists() shouldBe false
        }
    }
})
