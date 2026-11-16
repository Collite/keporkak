package kp.driver.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kp.driver.Answer
import kp.driver.Answerer
import kp.driver.Explained
import kp.driver.PermissionRequest
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The answerer of S2.1's driver, read from the seat's `permissions.json` (contracts.md §10, T4): a deny rule denies,
 * naming its border; no rule — the default, by the paths in the request's input, inside or outside what the session
 * may write; a request whose paths cannot be read is denied. It decides nothing else and reads no rule file.
 */
class CompiledAnswerer(private val seatsRoot: Path) : Answerer {
    override suspend fun answer(request: PermissionRequest): Answer = explained(request).answer

    override suspend fun explained(request: PermissionRequest): Explained {
        val file = seatsRoot.resolve(request.seat.value).resolve("permissions.json")
        val table = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return Explained(Answer.DENY, "the seat's permissions.json cannot be read")
        val name = request.name ?: return Explained(Answer.DENY, "the request does not name its tool")
        return PermissionTable.of(table)?.decide(name, request.input) ?: Explained(Answer.DENY, "the seat's permissions.json is not in its form")
    }
}

/**
 * `permissions.json`, as the `deny` hook reads it too (hooks/deny): the same rules, matched the same way. A rule
 * denies a call when its `tool` expression matches the tool's whole name, its `command` expression is found in one of
 * the [lines] of the input's `command`, and one of the call's paths matches `path` and not `except`. A call's paths
 * are its input's `file_path`, `path` and `notebook_path`, resolved against the working directory, `.` and `..` removed.
 */
internal class PermissionTable private constructor(private val workingDirectory: String, private val writable: Regex, private val inside: Answer, private val outside: Answer, private val rules: List<Rule>) {
    private class Rule(val border: String, val tool: Regex, val command: Regex?, val path: Regex?, val except: Regex?)

    fun decide(tool: String, input: JsonElement?): Explained {
        val fields = input as? JsonObject ?: return Explained(Answer.DENY, "its paths cannot be read: the input is not an object")
        val given = PATH_FIELDS.mapNotNull { fields[it] }
        if (given.any { (it as? JsonPrimitive)?.isString != true || it.content.isEmpty() }) return Explained(Answer.DENY, "its paths cannot be read")
        val paths = given.map { normalize((it as JsonPrimitive).content) }
        val lines = (fields["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::lines)
        rules.firstOrNull { rule ->
            rule.tool.matches(tool) &&
                (rule.command == null || (lines != null && lines.any { rule.command.containsMatchIn(it) })) &&
                (rule.path == null || paths.any { rule.path.containsMatchIn(it) && rule.except?.containsMatchIn(it) != true })
        }?.let { return Explained(Answer.DENY, "border ${it.border}") }
        return if (paths.all { writable.matches(it) }) Explained(inside, "inside what the session may write")
        else Explained(outside, "outside what the session may write")
    }

    /** [path] made absolute against the working directory, with `.`, `..` and empty names removed — as hooks/deny does. */
    private fun normalize(path: String): String {
        val names = ArrayDeque<String>()
        for (name in (if (path.startsWith("/")) path else "$workingDirectory/$path").split('/')) {
            when (name) {
                "", "." -> {}
                ".." -> names.removeLastOrNull()
                else -> names.addLast(name)
            }
        }
        return "/" + names.joinToString("/")
    }

    companion object {
        val PATH_FIELDS = listOf("file_path", "path", "notebook_path")

        /** A backslash that is not itself escaped, at a line's end: the shell joins the line to the next. */
        private val CONTINUED = Regex("""(?<!\\)((?:\\\\)*)\\\n""")

        /**
         * A command's lines, each judged on its own, so a command on any line is seen and no pattern runs across a
         * newline (review-011, B1): split at each newline, after the shell's continued lines are joined — as hooks/deny.
         */
        fun lines(command: String): List<String> = CONTINUED.replace(command, "$1").split('\n')

        fun of(table: JsonObject): PermissionTable? = runCatching {
            val defaults = table.getValue("defaults").jsonObject
            fun answer(key: String) = when ((defaults.getValue(key) as JsonPrimitive).content) {
                "allow" -> Answer.ALLOW
                "deny" -> Answer.DENY
                else -> error("$key is neither allow nor deny")
            }
            val rules = table.getValue("rules").jsonArray.map { item ->
                val rule = item.jsonObject
                fun regex(key: String) = (rule[key] as? JsonPrimitive)?.contentOrNull?.let(::Regex)
                Rule((rule.getValue("border") as JsonPrimitive).content, regex("tool")!!, regex("command"), regex("path"), regex("except"))
            }
            val directory = (table.getValue("working_directory") as JsonPrimitive).content.trimEnd('/').ifEmpty { "/" }
            val writable = Regex((table.getValue("writable") as JsonPrimitive).content)
            PermissionTable(directory, writable, answer("inside_working_directory"), answer("outside_working_directory"), rules)
        }.getOrNull()
    }
}
