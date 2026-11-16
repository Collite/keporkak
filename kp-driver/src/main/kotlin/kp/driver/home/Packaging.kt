package kp.driver.home

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kp.driver.Answer
import kp.driver.McpServer
import kp.driver.Outcome
import java.nio.file.Path

/**
 * The session's placeholders, filled by the caller's [Places] (contracts.md §10). A session placeholder the session
 * leaves empty fills nothing: a word that needs it is a problem, and no home is built.
 */
internal object SessionPlaceholders {
    val names = listOf("worktree", "scratch", "project", "own_file", "code_repos", "mcp_listen")

    fun fill(places: Places): Map<String, Any?> = mapOf(
        "worktree" to places.worktree.toString(),
        "scratch" to places.scratch.toString(),
        "project" to places.project.toString(),
        "own_file" to places.ownFile?.toString(),
        "code_repos" to places.codeRepos.map(Path::toString),
        "mcp_listen" to places.mcpListen,
    )
}

/** A persona's packaging part: what its frontmatter lists (agents/<role>/AGENT.md). */
internal class Persona(val role: String, val skills: List<String>, val mcp: List<String>, val deny: List<String>) {
    companion object {
        fun read(role: String, text: String): Outcome<Persona> {
            val fields = Frontmatter.of(text) ?: return Outcome.Failed("persona $role: no frontmatter")
            val map = fields as? Map<*, *> ?: return Outcome.Failed("persona $role: its frontmatter is not a mapping")
            val tools = map["tools"] as? Map<*, *>
            return Outcome.Ok(Persona(role, strings(map["skills"]), strings(tools?.get("mcp")), strings(map["deny"])))
        }
    }
}

/** A profile's machine part: the values its frontmatter gives the packaging data's placeholders (contracts.md §10). */
class Profile private constructor(val name: String, val values: Map<String, Any?>) {
    companion object {
        fun read(name: String, text: String): Outcome<Profile> {
            val fields = Frontmatter.of(text) as? Map<*, *> ?: return Outcome.Failed("profile $name: no frontmatter")
            return Outcome.Ok(Profile(name, fields.entries.associate { (key, value) -> key.toString() to value }))
        }
    }
}

/**
 * The packaging data, `agents/packaging/claude.yaml` (contracts.md §10, T2): each neutral `deny:` word as rules with
 * named placeholders, the MCP servers a persona may name, by address, and the three hooks' events. It compiles a
 * persona's words for one session — `permissions.json`, the rules of `settings.json` — and its servers.
 */
class Packaging private constructor(
    private val words: Map<String, Word>,
    private val servers: Map<String, Server>,
    internal val hooks: Map<String, HookEvent>,
    private val writable: List<String>,
) {
    internal class Word(val name: String, val denies: String, val rules: List<RawRule>)
    internal class RawRule(val tools: List<String>, val command: String?, val path: List<String>, val except: List<String>, val outside: String?, val early: List<String>)
    internal class Server(val url: String, val sessionToken: Boolean)
    internal class HookEvent(val event: String, val matcher: String?, val timeout: Int, val limit: Int?)

    /** The placeholders each word and server uses, by the word or server: for the check that something fills each. */
    internal fun placeholders(): List<Pair<String, String>> =
        words.values.flatMap { word -> word.rules.flatMap { it.texts() }.flatMap(::placeholdersIn).distinct().map { "deny word '${word.name}'" to it } } +
            servers.entries.flatMap { (name, server) -> placeholdersIn(server.url).distinct().map { "MCP server '$name'" to it } } +
            writable.flatMap(::placeholdersIn).distinct().map { "the writable set" to it }

    internal fun maps(word: String) = word in words
    internal fun serves(server: String) = server in servers

    /** The `deny:` words compiled for one session: the content of `permissions.json`, every border named. */
    fun permissions(words: List<String>, profile: Profile, places: Places, defaults: PermissionDefaults): Outcome<JsonObject> {
        val compiled = when (val rules = compile(words, profile, places)) {
            is Outcome.Ok -> rules.value
            is Outcome.Failed -> return rules
        }
        val writable = when (val set = writable(profile, places)) {
            is Outcome.Ok -> set.value
            is Outcome.Failed -> return set
        }
        return Outcome.Ok(buildJsonObject {
            put("version", 1)
            put("working_directory", places.worktree.toString())
            put("writable", globs(writable))
            put("defaults", buildJsonObject {
                put("inside_working_directory", defaults.writeInside.word())
                put("outside_working_directory", defaults.writeOutside.word())
            })
            put("rules", JsonArray(compiled.map { it.json() }))
        })
    }

    /** The same words in Claude Code's own syntax, for `settings.json` — the early no. */
    internal fun early(words: List<String>, profile: Profile, places: Places): Outcome<List<String>> = when (val rules = compile(words, profile, places)) {
        is Outcome.Ok -> Outcome.Ok(rules.value.flatMap { it.early }.distinct())
        is Outcome.Failed -> rules
    }

    /** The MCP servers a persona names, by address, for injection per session (§5: the messaging server carries the token). */
    internal fun servers(names: List<String>, places: Places): Outcome<List<McpServer>> {
        val values = SessionPlaceholders.fill(places)
        val problems = mutableListOf<String>()
        val built = names.mapNotNull { name ->
            val server = servers[name] ?: return@mapNotNull null.also { problems += "MCP server '$name' is not mapped by the packaging data" }
            val urls = Fill(values, problems, "MCP server '$name'").expand(server.url, ::literal)
            urls?.singleOrNull()?.let { McpServer(name, it, carriesToken = server.sessionToken) }
                ?: null.also { if (urls != null) problems += "MCP server '$name' does not have one address" }
        }
        return if (problems.isEmpty()) Outcome.Ok(built) else Outcome.Failed(problems.joinToString("; "))
    }

    private fun values(profile: Profile, places: Places) = SessionPlaceholders.fill(places) + profile.values.filterKeys { it !in SessionPlaceholders.names }

    private fun compile(names: List<String>, profile: Profile, places: Places): Outcome<List<CompiledRule>> {
        val values = values(profile, places)
        val problems = mutableListOf<String>()
        val writable = when (val set = writable(profile, places)) {
            is Outcome.Ok -> set.value
            is Outcome.Failed -> return set
        }
        val compiled = names.flatMap { name ->
            val word = words[name] ?: return@flatMap emptyList<CompiledRule>().also { problems += "deny word '$name' is not mapped by the packaging data" }
            val fill = Fill(values, problems, "deny word '$name'", profile.name)
            word.rules.mapNotNull { rule -> rule.compile(word, fill, writable) }
        }
        return if (problems.isEmpty()) Outcome.Ok(compiled) else Outcome.Failed(problems.distinct().joinToString("; "))
    }

    /**
     * What the session may write, as globs (contracts.md §10): each glob of the data's `writable` with its placeholders
     * filled. A glob whose session value is empty — the own file of a seat that has none — is left out.
     */
    private fun writable(profile: Profile, places: Places): Outcome<List<String>> {
        val values = values(profile, places)
        val problems = mutableListOf<String>()
        val globs = writable.flatMap { glob ->
            if (placeholdersIn(glob).any { it in SessionPlaceholders.names && values[it] == null }) emptyList()
            else Fill(values, problems, "the writable set", profile.name).expand(glob, ::literal).orEmpty()
        }
        return if (problems.isEmpty()) Outcome.Ok(globs) else Outcome.Failed(problems.distinct().joinToString("; "))
    }

    private fun RawRule.texts() = listOfNotNull(command) + path + except + early

    /** One rule with its placeholders filled; null when a value it needs is empty — the rule is left out. */
    private fun RawRule.compile(word: Word, fill: Fill, writable: List<String>): CompiledRule? {
        val command = command?.let { fill.expandRegex(it) ?: return null }
        val path = (if (outside != null) listOf("/**") else path.map { fill.expand(it, ::literal) ?: return null }.flatten())
        val except = except.map { fill.expand(it, ::literal) ?: return null }.flatten() + (if (outside != null) writable else emptyList())
        val early = early.flatMap { fill.expand(it, ::literal) ?: emptyList() }
        if (fill.failed) return null
        return CompiledRule(word.name, word.denies, tools(tools), command, path.takeIf { it.isNotEmpty() }?.let(::globs), except.takeIf { it.isNotEmpty() }?.let(::globs), early)
    }

    /** A placeholder's value as literal text in a pattern. */
    private fun literal(value: String) = value

    /** Fills placeholders from [values]; a missing one is a problem, an empty one leaves the pattern out (null). */
    private class Fill(private val values: Map<String, Any?>, private val problems: MutableList<String>, private val owner: String, private val profile: String? = null) {
        var failed = false

        /** Every pattern [text] stands for, one per value of each list it names; null when one is null or empty. */
        fun expand(text: String, escape: (String) -> String): List<String>? {
            var results = listOf(text)
            for (name in placeholdersIn(text).distinct()) {
                val choices = choices(name) ?: return null
                results = results.flatMap { partial -> choices.map { partial.replace("{$name}", escape(it)) } }
            }
            return results
        }

        /** A regular expression with each placeholder as literal text — a list as alternatives; null when one is empty. */
        fun expandRegex(text: String): String? {
            var result = text
            for (name in placeholdersIn(text).distinct()) {
                val choices = choices(name) ?: return null
                val alternatives = choices.map(::regexLiteral)
                result = result.replace("{$name}", if (alternatives.size == 1) alternatives.single() else alternatives.joinToString("|", "(", ")"))
            }
            return result
        }

        private fun choices(name: String): List<String>? {
            if (!values.containsKey(name)) {
                failed = true
                problems += "placeholder '{$name}' of $owner is filled by nothing" + (profile?.let { " in profile $it" } ?: "")
                return null
            }
            val choices = when (val value = values[name]) {
                null -> null
                is List<*> -> value.map { it.toString() }.takeIf { it.isNotEmpty() }
                else -> listOf(value.toString())
            }
            if (choices == null && name in SessionPlaceholders.names) {
                failed = true
                problems += "placeholder '{$name}' of $owner is filled by nothing: the session gives none"
            }
            return choices
        }
    }

    /** A rule ready for `permissions.json`: regular expressions over the tool's name, its command and its paths. */
    private class CompiledRule(val border: String, val denies: String, val tool: String, val command: String?, val path: String?, val except: String?, val early: List<String>) {
        fun json() = buildJsonObject {
            put("border", border)
            put("denies", denies)
            put("tool", tool)
            command?.let { put("command", it) }
            path?.let { put("path", it) }
            except?.let { put("except", it) }
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

        internal fun placeholdersIn(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()

        fun read(text: String): Outcome<Packaging> {
            val root = try {
                Frontmatter.plain(Yaml.default.parseToYamlNode(text))
            } catch (e: YamlException) {
                return Outcome.Failed("the packaging data is not readable YAML (line ${e.line}): ${e.message}")
            } as? Map<*, *> ?: return Outcome.Failed("the packaging data is not a mapping")
            val problems = mutableListOf<String>()
            val words = (root["deny"] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
                val name = key.toString()
                val word = value as? Map<*, *>
                val rules = (word?.get("rules") as? List<*>).orEmpty().map { item ->
                    val rule = item as? Map<*, *> ?: emptyMap<Any, Any>()
                    RawRule(strings(rule["tools"]), rule["command"]?.toString(), strings(rule["path"]), strings(rule["except"]), rule["outside"]?.toString(), strings(rule["early"]))
                        .also { if (it.tools.isEmpty()) problems += "deny word '$name': a rule names no tool" }
                        .also { if (it.outside != null && it.outside != WRITABLE) problems += "deny word '$name': outside names '${it.outside}', not $WRITABLE" }
                }
                if (rules.isEmpty()) problems += "deny word '$name' has no rule"
                name to Word(name, word?.get("denies")?.toString() ?: name, rules)
            }
            val servers = (root["mcp"] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
                val server = value as? Map<*, *>
                val url = server?.get("url")?.toString() ?: "".also { problems += "MCP server '$key' has no url" }
                key.toString() to Server(url, server?.get("session_token")?.toString() == "true")
            }
            val hooks = (root["hooks"] as? Map<*, *>).orEmpty().entries.associate { (key, value) ->
                val hook = value as? Map<*, *>
                val event = hook?.get("event")?.toString() ?: "".also { problems += "hook '$key' names no event" }
                key.toString() to HookEvent(event, hook?.get("matcher")?.toString(), hook?.get("timeout")?.toString()?.toIntOrNull() ?: 0, hook?.get("limit")?.toString()?.toIntOrNull())
            }
            for (hook in HOOKS) if (hook !in hooks) problems += "hook '$hook' is not in the packaging data"
            if (problems.isNotEmpty()) return Outcome.Failed(problems.joinToString("; "))
            return Outcome.Ok(Packaging(words, servers, hooks, strings(root[WRITABLE])))
        }

        /** The three hooks of contracts.md §11, by their scripts' names in `hooks/`. */
        internal val HOOKS = listOf("inbox-first", "deny", "stop-gate")

        /** The data's set of what a session may write, and the one name a rule's `outside` may give. */
        private const val WRITABLE = "writable"

        /**
         * Every problem the packaging data has with these personas and profiles (T2) — a `deny:` word or an MCP server
         * a persona uses and the data does not map, and a placeholder nothing fills — sorted, one line each, in the
         * words scripts/render-agents.py reports them in.
         */
        fun problems(personas: Map<String, String>, data: String, profiles: Map<String, String>): List<String> {
            val packaging = when (val read = read(data)) {
                is Outcome.Ok -> read.value
                is Outcome.Failed -> return listOf("packaging: ${read.reason}")
            }
            val problems = mutableListOf<String>()
            for ((role, text) in personas) {
                val persona = when (val read = Persona.read(role, text)) {
                    is Outcome.Ok -> read.value
                    is Outcome.Failed -> { problems += read.reason; continue }
                }
                persona.deny.filterNot(packaging::maps).forEach { problems += "persona $role: deny word '$it' is not mapped by the packaging data" }
                persona.mcp.filterNot(packaging::serves).forEach { problems += "persona $role: MCP server '$it' is not mapped by the packaging data" }
            }
            for ((name, text) in profiles) {
                val profile = when (val read = Profile.read(name, text)) {
                    is Outcome.Ok -> read.value
                    is Outcome.Failed -> { problems += read.reason; continue }
                }
                for ((owner, placeholder) in packaging.placeholders()) {
                    if (placeholder !in SessionPlaceholders.names && placeholder !in profile.values) {
                        problems += "packaging: placeholder '{$placeholder}' of $owner is filled by nothing in profile $name"
                    }
                }
            }
            return problems.distinct().sorted()
        }
    }
}

/** The regular expression's text that matches [text] literally — the same in Java's and in jq's (Oniguruma) dialect. */
internal fun regexLiteral(text: String): String = text.map { if (it in REGEX_SPECIAL) "\\$it" else "$it" }.joinToString("")

private const val REGEX_SPECIAL = "\\^$.|?*+()[]{}"

/** The tool names as one regular expression: a name ending `__*` is every tool of that MCP server. */
internal fun tools(names: List<String>): String =
    names.joinToString("|", "^(", ")$") { if (it.endsWith("__*")) regexLiteral(it.removeSuffix("*")) + ".*" else regexLiteral(it) }

/** Path globs as one anchored regular expression: `**` is any part of a path, `*` any part of one name, `?` one character of a name. */
internal fun globs(globs: List<String>): String = globs.joinToString("|", "^(", ")$") { glob ->
    buildString {
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**", i) -> { append(".*"); i += 2 }
                glob[i] == '*' -> { append("[^/]*"); i++ }
                glob[i] == '?' -> { append("[^/]"); i++ }
                else -> { append(regexLiteral(glob[i].toString())); i++ }
            }
        }
    }
}

private fun Answer.word() = if (this == Answer.ALLOW) "allow" else "deny"

private fun strings(value: Any?): List<String> = (value as? List<*>)?.map { it.toString() } ?: emptyList()

/** A document's YAML frontmatter as plain values (maps, lists, text, null), the way kaml reads it. */
internal object Frontmatter {
    private val FENCE = Regex("\\A---\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)

    /** The frontmatter between the first two `---` lines, or null when there is none or it is not readable. */
    fun of(text: String): Any? {
        val match = FENCE.find(text) ?: return null
        return try { plain(Yaml.default.parseToYamlNode(match.groupValues[1])) } catch (e: YamlException) { null }
    }

    fun plain(node: YamlNode): Any? = when (node) {
        is YamlMap -> node.entries.entries.associate { (key, value) -> key.content to plain(value) }
        is YamlList -> node.items.map(::plain)
        is YamlScalar -> node.content
        is YamlNull -> null
        is YamlTaggedNode -> plain(node.innerNode)
    }
}
