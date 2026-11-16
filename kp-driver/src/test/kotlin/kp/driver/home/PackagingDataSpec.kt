package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kp.driver.Answer
import kp.driver.McpServer
import kp.driver.Outcome
import kp.driver.ok
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/** T2 — the packaging data, its reader and its compile, for the next developer. */
class PackagingDataSpec : StringSpec({
    val defaults = PermissionDefaults(Answer.ALLOW, Answer.DENY, Answer.ALLOW)
    val places = Places(
        worktree = Path.of("/office/_wt/keporkak-S2.2"),
        scratch = Path.of("/office/_scratch/keporkak/S2.2"),
        project = Path.of("/office/project"),
        ownFile = Path.of("/office/project/tasks-p2-s2.2.md"),
        codeRepos = listOf(Path.of("/office/keporkak"), Path.of("/office/_wt")),
        mcpListen = "127.0.0.1:7711",
    )
    fun texts(folder: String) = root(folder).listDirectoryEntries("*.md").associate { it.nameWithoutExtension to it.readText() }
    val packaging = Packaging.read(root("agents/packaging/claude.yaml").readText()).ok()
    fun profile(name: String) = Profile.read(name, root("docs/profiles/$name.md").readText()).ok()
    fun rules(permissions: JsonObject) = permissions.getValue("rules").jsonArray.map { it.jsonObject }
    fun JsonObject.field(key: String) = (this[key] as? JsonPrimitive)?.content

    "the reader reports exactly the shared fixture's expected lines, as render-agents.py does" {
        val fixture = root("agents/packaging/fixture")
        val personas = fixture.resolve("personas").listDirectoryEntries("*.md").associate { it.nameWithoutExtension to it.readText() }
        val profiles = fixture.resolve("profiles").listDirectoryEntries("*.md").associate { it.nameWithoutExtension to it.readText() }

        Packaging.problems(personas, fixture.resolve("claude.yaml").readText(), profiles) shouldContainExactly fixture.resolve("expected.txt").readText().lines().filter { it.isNotEmpty() }
    }

    "master's packaging data maps every persona's words and servers, and every profile fills its placeholders" {
        val personas = root("agents").listDirectoryEntries().filter { it.resolve("AGENT.md").toFile().isFile }.associate { it.name to it.resolve("AGENT.md").readText() }

        Packaging.problems(personas, root("agents/packaging/claude.yaml").readText(), texts("docs/profiles")).shouldBeEmpty()
    }

    "every deny word of contracts.md §10 is in the data, each with a rule" {
        val words = listOf("side-channels", "push-master", "pin-files", "outside-worktree", "live-target", "browser", "write-code", "write-project",
            "write-project-beyond-own-list", "write-project-beyond-own-report", "demo-cluster")

        for (word in words) rules(packaging.permissions(listOf(word), profile("tatrman"), places, defaults).ok()).map { it.field("border") }.distinct() shouldBe listOf(word)
    }

    "a list fills one pattern per value; a profile's null leaves the rule out" {
        val pins = rules(packaging.permissions(listOf("pin-files"), profile("toy"), places, defaults).ok()).single()

        pins.field("path") shouldBe "^(/office/_wt/keporkak-S2\\.2/gradle/libs\\.versions\\.toml|/office/_wt/keporkak-S2\\.2/gradle/wrapper/gradle-wrapper\\.properties)$"
        packaging.early(listOf("pin-files"), profile("toy"), places).ok() shouldBe
            listOf("Edit(//office/_wt/keporkak-S2.2/gradle/libs.versions.toml)", "Edit(//office/_wt/keporkak-S2.2/gradle/wrapper/gradle-wrapper.properties)")
        rules(packaging.permissions(listOf("demo-cluster"), profile("keporkak"), places, defaults).ok()).shouldBeEmpty()
        rules(packaging.permissions(listOf("live-target"), profile("keporkak"), places, defaults).ok()).first().field("command") shouldBe
            "(^|[;&|(`])\\s*((kubectl|kustomize|helm))(\\s|$|[;&|`])"
    }

    "a session placeholder the session leaves empty is a problem, not a rule left out" {
        val outcome = packaging.permissions(listOf("write-project-beyond-own-list"), profile("keporkak"), places.copy(ownFile = null), defaults)

        (outcome as Outcome.Failed).reason shouldBe "placeholder '{own_file}' of deny word 'write-project-beyond-own-list' is filled by nothing: the session gives none"
        (packaging.permissions(listOf("write-code"), profile("keporkak"), places.copy(codeRepos = emptyList()), defaults) as Outcome.Failed).reason shouldBe
            "placeholder '{code_repos}' of deny word 'write-code' is filled by nothing: the session gives none"
    }

    "what a session may write is its worktree, its scratch and its own file; a seat with no own file has none" {
        val withList = packaging.permissions(listOf("push-master"), profile("keporkak"), places, defaults).ok()
        val without = packaging.permissions(listOf("push-master"), profile("keporkak"), places.copy(ownFile = null), defaults).ok()

        withList.field("writable") shouldBe "^(/office/_wt/keporkak-S2\\.2/.*|/office/_scratch/keporkak/S2\\.2/.*|/office/project/tasks-p2-s2\\.2\\.md)$"
        without.field("writable") shouldBe "^(/office/_wt/keporkak-S2\\.2/.*|/office/_scratch/keporkak/S2\\.2/.*)$"
    }

    "outside-worktree denies a write to any path outside what the session may write" {
        val permissions = packaging.permissions(listOf("outside-worktree"), profile("keporkak"), places, defaults).ok()
        val rule = rules(permissions).single()

        rule.field("path") shouldBe "^(/.*)$"
        rule.field("except") shouldBe permissions.field("writable")
    }

    "a rule's outside names the writable set and nothing else" {
        val data = root("agents/packaging/claude.yaml").readText().replace("outside: writable", "outside: readable")

        (Packaging.read(data) as Outcome.Failed).reason shouldBe "deny word 'outside-worktree': outside names 'readable', not writable"
    }

    "permissions.json carries the working directory and the defaults the caller gives" {
        val permissions = packaging.permissions(listOf("push-master"), profile("keporkak"), places, PermissionDefaults(Answer.DENY, Answer.DENY, Answer.ALLOW)).ok()

        permissions.field("working_directory") shouldBe "/office/_wt/keporkak-S2.2"
        permissions.getValue("defaults").jsonObject.toString() shouldBe """{"inside_working_directory":"deny","outside_working_directory":"deny"}"""
        rules(permissions).single().field("denies") shouldBe "a push to the default branch (the ruleset is the fence; this is the early no)"
    }

    "the servers a persona names come back by address, the messaging server carrying the session token" {
        packaging.servers(listOf("messaging", "context7"), places).ok() shouldBe listOf(
            McpServer("messaging", "http://127.0.0.1:7711/mcp", carriesToken = true),
            McpServer("context7", "https://mcp.context7.com/mcp"),
        )
        (packaging.servers(listOf("messaging"), places.copy(mcpListen = null)) as Outcome.Failed).reason shouldContain "'{mcp_listen}'"
    }

    "data that cannot be read, or lacks a hook, is refused with the reason" {
        (Packaging.read("deny: [") as Outcome.Failed).reason shouldContain "not readable YAML"
        (Packaging.read("deny: {x: {rules: [{tools: []}]}}\nhooks: {inbox-first: {event: SessionStart}, deny: {event: PreToolUse}}") as Outcome.Failed).reason shouldBe
            "deny word 'x': a rule names no tool; hook 'stop-gate' is not in the packaging data"
    }

    "globs and tool names become anchored expressions; literal text is escaped" {
        globs(listOf("/a/**", "/b/*.yml", "/c/?")) shouldBe "^(/a/.*|/b/[^/]*\\.yml|/c/[^/])$"
        tools(listOf("Bash", "mcp__playwright__*")) shouldBe "^(Bash|mcp__playwright__.*)$"
        regexLiteral("a.b(c)[d]{e}|f*g+h?i^j\$k\\l") shouldBe "a\\.b\\(c\\)\\[d\\]\\{e\\}\\|f\\*g\\+h\\?i\\^j\\\$k\\\\l"
    }
})
