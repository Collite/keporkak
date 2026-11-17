package kp.seatapi

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.yamlMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** S2.3 T5 — `whoami` (contracts.md §5): `open`, `left`, and no kind's name in the API's or the engine's source. */
class WhoamiSpec : StringSpec({

    "open names the threads that wait for the seat and those it waits on, until an answer closes them" {
        SeatOffice().use { office ->
            val teamlead = office.seat("teamlead", role = "teamlead")
            val coder = office.seat("coder-1", role = "coder")
            val reviewer = office.seat("reviewer-1", role = "reviewer")
            val assign = teamlead.call("send", """{"kind": "assign", "subject": "S2.3 is yours", "args": {"seat": "coder-1"}}""").json!!.text("id")

            val waiting = listOf(coder, teamlead, reviewer).map { it.call("whoami").json!!.open() }
            coder.call("reply", """{"re": "$assign", "kind": "started"}""")
            val answered = listOf(coder, teamlead).map { it.call("whoami").json!!.open() }

            waiting shouldBe listOf(listOf(assign!!), listOf(assign), emptyList())
            answered shouldBe listOf(emptyList(), emptyList())
        }
    }

    "left counts the firings the kind's window has seen" {
        SeatOffice(seed = "building").use { office ->
            val coder = office.seat("coder-1", role = "coder")

            val before = coder.call("whoami").json!!.left("stop")
            coder.call("send", """{"kind": "stop", "subject": "the contract must change", "args": {"sc": "SC-2"}}""")
            val after = coder.call("whoami").json!!.left("stop")

            listOf(before, after) shouldBe listOf("2", "1")
        }
    }

    "the API's and the engine's sources hold no kind's name" {
        val kinds = Yaml.default.parseToYamlNode(File(root, "rules/message-kinds.yaml").readText()).yamlMap.get<YamlMap>("kinds")!!.entries.keys.map { it.content }
        val sources = listOf("kp-seat-api/src/main", "kp-engine/src/main").flatMap { File(root, it).walk().filter { file -> file.extension == "kt" }.toList() }

        val named = sources.flatMap { source -> kinds.filter { "\"$it\"" in source.readText() }.map { "${source.name}: $it" } }

        kinds shouldContain "started"
        named shouldBe emptyList()
    }
})

private fun JsonObject.open(): List<String> = (this["open"] as JsonArray).map { it.jsonPrimitive.content }

private fun JsonObject.left(kind: String): String? =
    (this["allowed"] as JsonArray).map { it as JsonObject }.single { it.text("kind") == kind }.text("left")
