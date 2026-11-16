package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kp.driver.Answer
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S2.6's five specs, for Bora. Seam: the builder, the answerer and the hook scripts of S2.2, on master's personas,
 * packaging data and `engagement.yaml:permissions`, beside the one shared table (permission-table.tsv, read by
 * PackagingAndHooksSpec). Each call goes to the `deny` hook as Claude Code runs it and to the answerer as a permission
 * request; each answer is `allow` or `deny <border>`. The coder's places are Sources': its worktree
 * `office/_wt/keporkak-S2.2`, its scratch `office/_scratch/keporkak/S2.2`, the project checkout `office/project` and
 * its own list there, `office/project/tasks-p2-s2.2.md`.
 */
class PermissionsSecondPassSpec : StringSpec({
    "a coder writes its own stage list in the project checkout and a file in its scratch; any other write outside its worktree is denied — by the hook and by the answerer alike" {
        Sources().use { sources ->
            val coder = sources.home("coder-1", "coder")
            val calls = listOf(
                edit("${sources.places.ownFile}", "- [ ] T1", "- [x] T1") to "allow",
                write("${sources.places.scratch}/probe.md") to "allow",
                write("${sources.places.project}/STATUS.md") to "deny outside-worktree",
                write("${sources.places.project}/tasks-p2-s2.3.md") to "deny outside-worktree",
                write("/office/elsewhere/notes.md") to "deny outside-worktree",
            )

            calls.map { (call, _) -> Hooks.deny(coder, call) } shouldBe calls.map { it.second }
            calls.map { (call, _) -> sources.answer("coder-1", call) } shouldBe calls.map { it.second }
        }
    }

    "a read is allowed wherever no deny: word covers it" {
        Sources().use { sources ->
            val coder = sources.home("coder-1", "coder")
            val calls = listOf(
                read("/office/elsewhere/notes.md") to "allow",
                read("${sources.places.project}/contracts.md") to "allow",
                call("Grep", """{"pattern": "D-67", "path": "${sources.places.project}"}""") to "allow",
                write("/office/elsewhere/notes.md") to "deny outside-worktree",
            )

            calls.map { (call, _) -> Hooks.deny(coder, call) } shouldBe calls.map { it.second }
            calls.map { (call, _) -> sources.answer("coder-1", call) } shouldBe calls.map { it.second }
        }
    }

    "a request that names no path is judged by the deny rules alone" {
        Sources().use { sources ->
            // Defaults that deny every write and every read: a request with no path never reaches them.
            val coder = sources.home("coder-1", "coder", defaults = PermissionDefaults(writeInside = Answer.DENY, writeOutside = Answer.DENY, read = Answer.DENY))
            val calls = listOf(
                bash("just check") to "allow",
                call("mcp__messaging__inbox", "{}") to "allow",
                call("WebFetch", """{"url": "https://example.org/", "prompt": "the title"}""") to "allow",
                bash("sudo kp hold KP/P2/S2.6") to "deny side-channels",
            )

            calls.map { (call, _) -> Hooks.deny(coder, call) } shouldBe calls.map { it.second }
            calls.map { (call, _) -> sources.answer("coder-1", call) } shouldBe calls.map { it.second }
        }
    }

    "a deny hook that cannot read permissions.json denies" {
        Sources().use { sources ->
            val coder = sources.home("coder-1", "coder")
            val permissions = coder.resolve("permissions.json")
            Hooks.deny(coder, bash("just check")) shouldBe "allow"

            Hooks.deny(coder, bash("just check"), path = "/nonexistent") shouldBe "deny: jq is missing, so the seat's permissions.json cannot be read"
            permissions.writeText(permissions.readText().dropLast(1))
            Hooks.deny(coder, bash("just check")) shouldBe "deny: the seat's permissions.json cannot be read"
            permissions.deleteExisting()
            Hooks.deny(coder, bash("just check")) shouldBe "deny: the seat's permissions.json cannot be read"
        }
    }

    "a home's settings.json holds the three hooks and no permission rule" {
        Sources().use { sources ->
            val homes = sources.roles().flatMap { role -> sources.profiles().map { profile -> sources.home("$role-$profile", role, profile) } }
            val settings = homes.map { Json.parseToJsonElement(it.resolve("settings.json").readText()).jsonObject }

            homes.size shouldBe 27
            settings.map { it.keys } shouldBe List(27) { setOf("hooks") }
            settings.map { it.getValue("hooks").jsonObject.keys } shouldBe List(27) { setOf("SessionStart", "PreToolUse", "Stop") }
        }
    }
})
