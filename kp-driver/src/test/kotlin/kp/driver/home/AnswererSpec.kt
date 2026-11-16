package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kp.driver.Answer
import kp.driver.Explained
import kp.driver.OfferedOption
import kp.driver.Office
import kp.driver.PermissionRequest
import kp.driver.ok
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** T4 — the answerer, read from permissions.json, through the driver and the scripted fake agent, for the next developer. */
class AnswererSpec : StringSpec({
    val seat = SeatId("coder-1")
    val defaults = PermissionDefaults(Answer.ALLOW, Answer.DENY, Answer.ALLOW)

    /** The coder's words compiled into the office's home for the seat, where the answerer reads them. */
    fun Office.permissions(vararg words: String) {
        val packaging = Packaging.read(root("agents/packaging/claude.yaml").readText()).ok()
        val places = Places(worktree, root.resolve("scratch"), root.resolve("project"), root.resolve("project/tasks-p2-s2.2.md"), listOf(root.resolve("repos")), "127.0.0.1:7711")
        val profile = Profile.read("keporkak", root("docs/profiles/keporkak.md").readText()).ok()
        seats.resolve(seat.value).also(Files::createDirectories).resolve("permissions.json").writeText(packaging.permissions(words.toList(), profile, places, defaults).ok().toString())
    }
    fun request(input: String?, name: String? = "Write") = PermissionRequest(seat, "t1", "Write x", "edit", input?.let(Json::parseToJsonElement),
        listOf(OfferedOption("allow-once", "Yes", "allow_once"), OfferedOption("reject", "No", "reject_once")), name)

    "a request a deny rule covers is answered reject_once, and the border's name goes to the transcript" {
        Office().use { office ->
            office.permissions("push-master", "outside-worktree")
            val agent = office.agent("""{"turns": [[
                {"do": "tool", "id": "p1", "name": "Bash", "title": "Terminal", "input": {"command": "git push origin master"}},
                {"do": "ask", "id": "p1", "name": "Bash", "title": "git push origin master", "options": ["allow_once", "reject_once"]},
                {"do": "end"}]]}""")
            val driver = office.driver(answerer = CompiledAnswerer(office.seats))
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "push").ok()

            agent.received("permission").single()["kind"] shouldBe JsonPrimitive("reject_once")
            office.transcript("coder-1").last { " permission p1: " in it }.substringAfter("→ ") shouldBe "reject_once (border push-master)"
        }
    }

    "a request no rule covers inside the working directory is answered allow_once, by the default" {
        Office().use { office ->
            office.permissions("push-master", "outside-worktree")
            val agent = office.agent("""{"turns": [[
                {"do": "ask", "id": "p2", "name": "Write", "title": "Write a.md", "kind": "edit", "input": {"file_path": "${office.worktree}/a.md", "content": "x"}, "options": ["allow_once", "allow_always", "reject_once"]},
                {"do": "end"}]]}""")
            val driver = office.driver(answerer = CompiledAnswerer(office.seats))
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "write").ok()

            agent.received("permission").single()["kind"] shouldBe JsonPrimitive("allow_once")
            office.transcript("coder-1").last { " permission p2: " in it }.substringAfter("→ ") shouldBe "allow_once (inside what the session may write)"
        }
    }

    "a request whose paths cannot be read is denied" {
        Office().use { office ->
            office.permissions("push-master")
            val answerer = CompiledAnswerer(office.seats)

            answerer.explained(request("""{"file_path": 42}""")) shouldBe Explained(Answer.DENY, "its paths cannot be read")
            answerer.explained(request("""{"file_path": ""}""")) shouldBe Explained(Answer.DENY, "its paths cannot be read")
            answerer.explained(request(null)) shouldBe Explained(Answer.DENY, "its paths cannot be read: the input is not an object")
            answerer.explained(request("""["${office.worktree}/a.md"]""")) shouldBe Explained(Answer.DENY, "its paths cannot be read: the input is not an object")
        }
    }

    "a request that does not name its tool, or a seat whose permissions.json cannot be read, is denied" {
        Office().use { office ->
            val answerer = CompiledAnswerer(office.seats)

            answerer.explained(request("""{"file_path": "${office.worktree}/a.md"}""")) shouldBe Explained(Answer.DENY, "the seat's permissions.json cannot be read")
            office.permissions("push-master")
            answerer.explained(request("""{"file_path": "${office.worktree}/a.md"}""", name = null)) shouldBe Explained(Answer.DENY, "the request does not name its tool")
            office.seats.resolve("coder-1/permissions.json").writeText("""{"rules": "none"}""")
            answerer.explained(request("""{"file_path": "${office.worktree}/a.md"}""")) shouldBe Explained(Answer.DENY, "the seat's permissions.json is not in its form")
        }
    }

    "a call with no path is judged inside the working directory; a relative path is resolved against it" {
        Office().use { office ->
            office.permissions("push-master")
            val answerer = CompiledAnswerer(office.seats)

            answerer.explained(request("""{"command": "just check"}""", name = "Bash")) shouldBe Explained(Answer.ALLOW, "inside what the session may write")
            answerer.explained(request("""{"to": "teamlead", "kind": "started"}""", name = "mcp__messaging__send")) shouldBe Explained(Answer.ALLOW, "inside what the session may write")
            answerer.explained(request("""{"file_path": "docs/a.md"}""")) shouldBe Explained(Answer.ALLOW, "inside what the session may write")
            answerer.explained(request("""{"file_path": "../elsewhere/a.md"}""")) shouldBe Explained(Answer.DENY, "outside what the session may write")
            answerer.explained(request("""{"notebook_path": "${office.worktree}/n.ipynb", "file_path": "/etc/hosts"}""")) shouldBe Explained(Answer.DENY, "outside what the session may write")
        }
    }

    "the driver hands the answerer the tool's name the agent gave on the wire" {
        Office().use { office ->
            val agent = office.agent("""{"turns": [[
                {"do": "tool", "id": "p1", "name": "Bash", "title": "Terminal", "input": {"command": "ls"}},
                {"do": "ask", "id": "p1", "title": "ls", "options": ["allow_once", "reject_once"]},
                {"do": "ask", "id": "p2", "name": "Write", "title": "Write a.md", "kind": "edit", "input": {"file_path": "/x/a.md"}, "options": ["allow_once", "reject_once"]},
                {"do": "ask", "id": "p3", "title": "Mystery", "input": {}, "options": ["allow_once", "reject_once"]},
                {"do": "end"}]]}""")
            val names = mutableListOf<String?>()
            val driver = office.driver(answerer = { names += it.name; Answer.DENY })
            driver.start(office.session("coder-1", agent)).ok()

            driver.prompt(seat, "go").ok()

            names shouldBe listOf("Bash", "Write", null)
        }
    }
})
