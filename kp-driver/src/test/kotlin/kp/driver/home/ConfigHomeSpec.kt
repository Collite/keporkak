package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kp.driver.Answer
import kp.driver.McpServer
import kp.driver.Outcome
import kp.driver.ok
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** T3 — the config home, for the next developer. */
class ConfigHomeSpec : StringSpec({
    val defaults = PermissionDefaults(Answer.ALLOW, Answer.DENY, Answer.ALLOW)
    val roles = root("agents").listDirectoryEntries().filter { it.resolve("AGENT.md").exists() }.map { it.name }.sorted()

    "for every persona on master a home builds: its CLAUDE.md is the render byte for byte, and it has exactly the skills the persona lists" {
        Sources().use { sources ->
            roles shouldContainExactly listOf("coder", "deputy", "gardener", "pool", "principal", "qa", "retro", "reviewer", "teamlead")
            for (role in roles) {
                val home = ConfigHomes(sources.root, sources.seats).build(SeatId("$role-1"), role, "keporkak", sources.places, defaults).ok().path
                val listed = Persona.read(role, sources.root.resolve("agents/$role/AGENT.md").readText()).ok().skills

                home.resolve("CLAUDE.md").readBytes() shouldBe root("renders/$role.md").readBytes()
                sources.names(home.resolve("skills")) shouldBe listed.sorted()
                for (skill in listed) home.resolve("skills/$skill/SKILL.md").readBytes() shouldBe
                    (root("skills/$skill/SKILL.md").takeIf { it.exists() } ?: root("skills/_imported/$skill/SKILL.md")).readBytes()
            }
        }
    }

    "no file of any home names an MCP server or holds a token; only the seat's two programs say how a bearer is sent (S3.7)" {
        Sources().use { sources ->
            val token = "sk-ant-oat01-" + "x".repeat(40)
            sources.root.resolve("secrets").also(Files::createDirectories).resolve("claude-token").writeText(token)
            for (role in roles) ConfigHomes(sources.root, sources.seats).build(SeatId("$role-1"), role, "keporkak", sources.places, defaults).ok()

            val files = Files.walk(sources.seats).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
            (files.size >= roles.size * 3) shouldBe true
            for (file in files) {
                val text = file.readText()
                // The programs ask the daemon with the session token as a bearer (Amendment 35), read from the environment.
                val program = file.parent.fileName.toString() == "bin"
                for (forbidden in listOf(token, "mcpServers", "127.0.0.1:7711", "mcp.context7.com") + if (program) emptyList() else listOf("Bearer", "Authorization")) {
                    (forbidden in text) shouldBe false
                }
            }
        }
    }

    "the home is the office user's only: its folders 0700, its files 0600, its two programs 0700 (S3.7)" {
        Sources().use { sources ->
            val home = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, defaults).ok().path

            Files.walk(home).use { paths ->
                paths.forEach { path ->
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) shouldBe if (path.isDirectory() || path.parent == home.resolve("bin")) "rwx------" else "rw-------"
                }
            }
        }
    }

    "settings.json holds the three hooks by absolute path, and nothing else" {
        Sources().use { sources ->
            val home = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, defaults).ok().path
            val settings = Json.parseToJsonElement(home.resolve("settings.json").readText()).jsonObject
            val hooks = settings.getValue("hooks").jsonObject
            fun command(event: String) = hooks.getValue(event).jsonArray.single().jsonObject.getValue("hooks").jsonArray.single().jsonObject
            val hooksDir = sources.root.resolve("hooks").toAbsolutePath().normalize()

            settings.keys shouldBe setOf("hooks")
            hooks.keys shouldBe setOf("SessionStart", "PreToolUse", "Stop")
            command("SessionStart").text("command") shouldBe "'/bin/sh' '$hooksDir/inbox-first'"
            command("PreToolUse").text("command") shouldBe "'/bin/sh' '$hooksDir/deny' '$home/permissions.json'"
            hooks.getValue("PreToolUse").jsonArray.single().jsonObject.text("matcher") shouldBe "*"
            command("Stop").text("command") shouldBe "'/bin/sh' '$hooksDir/stop-gate' '10'"
            command("Stop").text("timeout") shouldBe "30"
        }
    }

    "the servers the persona names come back for injection" {
        Sources().use { sources ->
            ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, defaults).ok().servers shouldBe
                listOf(McpServer("messaging", "http://127.0.0.1:7711/mcp", carriesToken = true), McpServer("context7", "https://mcp.context7.com/mcp"))
            ConfigHomes(sources.root, sources.seats).build(SeatId("pool-1"), "pool", "keporkak", sources.places, defaults).ok().servers shouldBe
                listOf(McpServer("messaging", "http://127.0.0.1:7711/mcp", carriesToken = true))
        }
    }

    "a skill the persona lists that does not exist, or a missing own file, is reported, and an earlier home is left as it was" {
        Sources().use { sources ->
            val homes = ConfigHomes(sources.root, sources.seats)
            val home = homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, defaults).ok().path
            val before = home.resolve("settings.json").readText()
            sources.persona("coder") { it.replace("skills: [context7-mcp,", "skills: [juggling, context7-mcp,") }

            (homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, defaults) as Outcome.Failed).reason shouldBe
                "persona coder: skill 'juggling' is in neither skills/ nor skills/_imported/"
            sources.persona("coder") { it.replace("skills: [juggling, context7-mcp,", "skills: [context7-mcp,") }
            (homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places.copy(ownFile = null), defaults) as Outcome.Failed).reason shouldContain
                "persona coder: placeholder '{own_file}' of deny word 'write-project-beyond-own-list' is filled by nothing"
            home.resolve("settings.json").readText() shouldBe before
            sources.names(home.resolve("skills")) shouldContainExactly listOf("context7-mcp", "diagnosing-bugs", "handoff-pr", "tdd")
        }
    }

    "the own file is in what a seat may write only when its persona has a …-beyond-own-… word (issue #24)" {
        Sources().use { sources ->
            /** Whether the session's own file is in what [role]'s home lets it write: `permissions.json`'s `writable`, one pattern. */
            fun writesItsOwnFile(role: String) = Regex((Json.parseToJsonElement(ConfigHomes(sources.root, sources.seats).build(SeatId("$role-1"), role, "keporkak", sources.places, defaults)
                .ok().path.resolve("permissions.json").readText()).jsonObject.getValue("writable") as JsonPrimitive).content).matches(sources.places.ownFile.toString())

            writesItsOwnFile("coder") shouldBe true // write-project-beyond-own-list
            writesItsOwnFile("retro") shouldBe true // write-project-beyond-own-report
            writesItsOwnFile("reviewer") shouldBe false
            writesItsOwnFile("teamlead") shouldBe false
        }
    }

    "a persona, a render or a profile that does not exist is reported, and nothing is built" {
        Sources().use { sources ->
            val homes = ConfigHomes(sources.root, sources.seats)

            (homes.build(SeatId("x-1"), "juggler", "keporkak", sources.places, defaults) as Outcome.Failed).reason shouldBe
                "persona juggler: renders/juggler.md does not exist; persona juggler: agents/juggler/AGENT.md does not exist"
            (homes.build(SeatId("coder-1"), "coder", "circus", sources.places, defaults) as Outcome.Failed).reason shouldBe
                "profile circus: docs/profiles/circus.md does not exist"
            sources.seats.listDirectoryEntries().shouldBeEmpty()
        }
    }

    "permissions.json of a home compiles the persona's words, in the persona's order" {
        Sources().use { sources ->
            val home = ConfigHomes(sources.root, sources.seats).build(SeatId("qa-1"), "qa", "tatrman", sources.places, defaults).ok().path
            val permissions = Json.parseToJsonElement(home.resolve("permissions.json").readText()).jsonObject

            permissions.getValue("rules").jsonArray.map { (it.jsonObject["border"] as JsonPrimitive).content }.distinct() shouldBe
                listOf("side-channels", "write-code", "write-project", "push-master", "pin-files", "demo-cluster")
            (permissions.getValue("rules").jsonArray.map { it.jsonObject }.none { (it["border"] as JsonPrimitive).content == "live-target" }) shouldBe true
        }
    }
})

