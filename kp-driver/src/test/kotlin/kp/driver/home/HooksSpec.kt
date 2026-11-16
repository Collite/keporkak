package kp.driver.home

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kp.driver.Answer
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** T5 — the three hooks as scripts, run as Claude Code runs them, for the next developer. */
class HooksSpec : StringSpec({
    val defaults = PermissionDefaults(Answer.ALLOW, Answer.DENY)
    val call = """{"command":"git push origin master"}"""

    "inbox-first adds its one line of context and decides nothing" {
        Hooks.inboxFirst() shouldBe """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"Keporkak: call `inbox` before anything else."}}"""
    }

    "deny answers in the form the probe recorded, with the border's name and what it denies" {
        Sources().use { sources ->
            val permissions = sources.permissions("push-master", "keporkak", "/office/_wt/keporkak-S2.2", defaults)
            val out = Hooks.run("deny", listOf(permissions.toString()), """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":$call}""")

            out.trim() shouldBe """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny",""" +
                """"permissionDecisionReason":"Keporkak denied this call: border push-master — a push to the default branch (the ruleset is the fence; this is the early no)."}}"""
        }
    }

    "deny says nothing to a call no rule covers: Claude Code's permissions and the answerer go on from there" {
        Sources().use { sources ->
            val permissions = sources.permissions("push-master", "keporkak", "/office/_wt/keporkak-S2.2", defaults)

            Hooks.run("deny", listOf(permissions.toString()), """{"tool_name":"Bash","tool_input":{"command":"git status"}}""") shouldBe ""
            Hooks.run("deny", listOf(permissions.toString()), """{"tool_name":"mcp__messaging__inbox","tool_input":{}}""") shouldBe ""
        }
    }

    "deny denies every call when the seat's permissions.json or the call cannot be read" {
        Sources().use { sources ->
            val missing = sources.seats.resolve("nobody/permissions.json")
            val broken = Files.createTempFile(sources.root, "permissions", ".json").also { it.writeText("{not json") }
            val good = sources.permissions("push-master", "keporkak", "/office/_wt/keporkak-S2.2", defaults)
            fun reason(out: String) = (Json.parseToJsonElement(out).jsonObjectValue("hookSpecificOutput").getValue("permissionDecisionReason") as JsonPrimitive).content

            reason(Hooks.run("deny", listOf(missing.toString()), """{"tool_name":"Bash","tool_input":{"command":"ls"}}""")) shouldBe
                "Keporkak denied this call: the seat's permissions.json cannot be read."
            reason(Hooks.run("deny", listOf(broken.toString()), """{"tool_name":"Bash","tool_input":{"command":"ls"}}""")) shouldStartWith "Keporkak denied this call: its input or"
            reason(Hooks.run("deny", listOf(good.toString()), "not json")) shouldStartWith "Keporkak denied this call: its input or"
            reason(Hooks.run("deny", emptyList(), """{"tool_name":"Bash","tool_input":{"command":"ls"}}""")) shouldBe
                "Keporkak denied this call: the seat's permissions.json cannot be read."
        }
    }

    "no hook but stop-gate opens a connection, and none starts a JVM" {
        StopStandIn(answer = """{"block": true, "reason": "r"}""").use { daemon ->
            Sources().use { sources ->
                val permissions = sources.permissions("push-master", "keporkak", "/office/_wt/keporkak-S2.2", defaults)
                val environment = mapOf("KP_HOOK_URL" to daemon.url, "KP_SESSION_TOKEN" to "t")

                Hooks.inboxFirst(environment)
                Hooks.run("deny", listOf(permissions.toString()), """{"tool_name":"Bash","tool_input":$call}""", environment)
                daemon.connections.get() shouldBe 0
                Hooks.run("stop-gate", listOf("3"), Hooks.STOP.toString(), environment)
                daemon.connections.get() shouldBe 1
            }
        }
        for (hook in listOf("inbox-first", "deny", "stop-gate")) {
            val text = root("hooks/$hook").readText()
            text.lines().first() shouldBe "#!/bin/sh"
            Regex("\\b(java|kotlin|gradle|jshell)\\b").containsMatchIn(text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")) shouldBe false
            val networked = Regex("\\b(curl|wget|nc|ncat|socat)\\b").containsMatchIn(text.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n"))
            networked shouldBe (hook == "stop-gate")
        }
    }

    "the hooks are POSIX shell: /bin/sh reads each without a syntax error" {
        for (hook in listOf("inbox-first", "deny", "stop-gate")) {
            val check = ProcessBuilder("/bin/sh", "-n", root("hooks/$hook").toString()).redirectErrorStream(true).start()
            val out = check.inputStream.bufferedReader().readText()
            check.waitFor() shouldBe 0
            out shouldBe ""
        }
        root("hooks/deny").readText() shouldContain "jq "
    }
})

private fun kotlinx.serialization.json.JsonElement.jsonObjectValue(key: String) =
    (this as kotlinx.serialization.json.JsonObject).getValue(key) as kotlinx.serialization.json.JsonObject
