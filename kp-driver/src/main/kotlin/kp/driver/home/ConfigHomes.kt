package kp.driver.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kp.driver.Answer
import kp.driver.McpServer
import kp.driver.Outcome
import kp.model.SeatId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The values of one session that fill the packaging data's placeholders (contracts.md §10): its working directory —
 * the stage's worktree, or the project checkout for a seat with no stage — its scratch, the project checkout, the one
 * project file a `…-beyond-own-…` word leaves writable, the office's code checkouts, and the messaging server's
 * address. The caller gives them; the builder reads no office file.
 */
data class Places(
    val worktree: Path,
    val scratch: Path,
    val project: Path,
    val ownFile: Path? = null,
    val codeRepos: List<Path> = emptyList(),
    val mcpListen: String? = null,
)

/** The defaults of `engagement.yaml:permissions` as the caller read them: the answer inside and outside the working directory. */
data class PermissionDefaults(val inside: Answer, val outside: Answer)

/** A built config home, and the MCP servers its persona names, by address, for the caller to inject per session. */
data class Home(val path: Path, val servers: List<McpServer>)

/**
 * Builds a seat's config home, `<seatsRoot>/<seat>/`, at every start (contracts.md §10, T3), from the keporkak
 * checkout: `renders/<role>.md`, the skills the persona lists, `agents/packaging/claude.yaml`, the profile's
 * frontmatter and the hooks in `hooks/`.
 *
 * The home holds `CLAUDE.md` (the render, byte for byte), `skills/<name>/` for each skill the persona lists,
 * `settings.json` (the deny rules as the early no, and the three hooks by absolute path — no MCP server, no token)
 * and `permissions.json` (the same rules compiled, each with its border's name, and the defaults the caller gives).
 * Those four are replaced at every build; what Claude Code keeps in the home itself — its sessions — stays. The home
 * is the office user's only: the folder and its folders 0700, its files 0600. A persona the packaging data cannot
 * package is reported with every problem, and nothing is built or changed.
 */
class ConfigHomes(private val keporkak: Path, private val seatsRoot: Path) {
    fun build(seat: SeatId, role: String, profile: String, places: Places, defaults: PermissionDefaults): Outcome<Home> {
        val problems = mutableListOf<String>()
        fun <T> Outcome<T>.or(prefix: String = ""): T? = when (this) {
            is Outcome.Ok -> value
            is Outcome.Failed -> null.also { problems += prefix + reason }
        }
        fun <T> read(path: String, missing: String, parse: (String) -> Outcome<T>): T? =
            text(path)?.let { parse(it).or() } ?: null.also { if (text(path) == null) problems += missing }
        val render = keporkak.resolve("renders/$role.md").takeIf { it.isRegularFile() } ?: null.also { problems += "persona $role: renders/$role.md does not exist" }
        val persona = read("agents/$role/AGENT.md", "persona $role: agents/$role/AGENT.md does not exist") { Persona.read(role, it) }
        val packaging = read(PACKAGING, "packaging: $PACKAGING does not exist") { text -> Packaging.read(text).let { if (it is Outcome.Failed) Outcome.Failed("packaging: ${it.reason}") else it } }
        val values = read("docs/profiles/$profile.md", "profile $profile: docs/profiles/$profile.md does not exist") { Profile.read(profile, it) }
        val hooks = Packaging.HOOKS.associateWith { keporkak.resolve("hooks/$it").toAbsolutePath().normalize() }
        hooks.filterValues { !it.isRegularFile() }.keys.forEach { problems += "hook $it: hooks/$it does not exist" }
        if (persona == null || packaging == null || values == null || render == null || problems.isNotEmpty()) return Outcome.Failed(problems.joinToString("; "))

        val skills = persona.skills.associateWith { name ->
            listOf("skills/$name", "skills/_imported/$name").map(keporkak::resolve).firstOrNull { it.resolve("SKILL.md").isRegularFile() }
                ?: null.also { problems += "persona $role: skill '$name' is in neither skills/ nor skills/_imported/" }
        }
        persona.deny.filterNot(packaging::maps).forEach { problems += "persona $role: deny word '$it' is not mapped by the packaging data" }
        persona.mcp.filterNot(packaging::serves).forEach { problems += "persona $role: MCP server '$it' is not mapped by the packaging data" }
        if (problems.isNotEmpty()) return Outcome.Failed(problems.joinToString("; "))
        val permissions = packaging.permissions(persona.deny, values, places, defaults).or("persona $role: ")
        val early = packaging.early(persona.deny, values, places).or("persona $role: ")
        val servers = packaging.servers(persona.mcp, places).or("persona $role: ")
        if (permissions == null || early == null || servers == null) return Outcome.Failed(problems.distinct().joinToString("; "))

        val home = seatsRoot.resolve(seat.value)
        return try {
            Files.createDirectories(seatsRoot)
            if (!home.exists()) Files.createDirectory(home, FOLDER)
            Files.setPosixFilePermissions(home, FOLDER.value())
            replace(home.resolve("CLAUDE.md"), Files.readAllBytes(render))
            replace(home.resolve("settings.json"), settings(early, packaging, hooks, home).toString().toByteArray())
            replace(home.resolve("permissions.json"), permissions.toString().toByteArray())
            replaceSkills(home, skills.mapValues { it.value!! })
            Outcome.Ok(Home(home, servers))
        } catch (e: IOException) {
            Outcome.Failed("the config home $home cannot be built: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun text(path: String): String? = keporkak.resolve(path).takeIf { it.isRegularFile() }?.readText()

    /**
     * `settings.json`: the deny rules in Claude Code's syntax, and each hook on the event the packaging data names,
     * by absolute path — `deny` with this home's `permissions.json`, `stop-gate` with its limit.
     */
    private fun settings(early: List<String>, packaging: Packaging, hooks: Map<String, Path>, home: Path): JsonObject {
        val arguments = mapOf(
            "inbox-first" to emptyList(),
            "deny" to listOf(home.resolve("permissions.json").toString()),
            "stop-gate" to listOf(packaging.hooks.getValue("stop-gate").limit?.toString() ?: ""),
        )
        val byEvent = Packaging.HOOKS.groupBy { packaging.hooks.getValue(it).event }
        return buildJsonObject {
            put("permissions", buildJsonObject { put("deny", JsonArray(early.map(::JsonPrimitive))) })
            put("hooks", JsonObject(byEvent.mapValues { (_, names) ->
                JsonArray(names.map { name ->
                    val hook = packaging.hooks.getValue(name)
                    buildJsonObject {
                        hook.matcher?.let { put("matcher", it) }
                        put("hooks", JsonArray(listOf(buildJsonObject {
                            put("type", "command")
                            put("command", (listOf("/bin/sh", hooks.getValue(name).toString()) + arguments.getValue(name).filter { it.isNotEmpty() }).joinToString(" ", transform = ::quote))
                            put("timeout", hook.timeout)
                        })))
                    }
                })
            }))
        }
    }

    /** [file] replaced by [bytes] at once: written beside it as 0600, then moved over it. */
    private fun replace(file: Path, bytes: ByteArray) {
        val next = file.resolveSibling(".${file.fileName}.kp-build")
        Files.deleteIfExists(next)
        Files.createFile(next, FILE)
        Files.write(next, bytes)
        Files.move(next, file, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    /** `skills/` replaced by exactly the listed skills: built beside it, then swapped in; the old one removed. */
    private fun replaceSkills(home: Path, skills: Map<String, Path>) {
        val next = home.resolve(".skills.kp-build")
        delete(next)
        Files.createDirectory(next, FOLDER)
        for ((name, source) in skills) {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val target = next.resolve(name).resolve(source.relativize(path).toString())
                    if (path.isDirectory()) Files.createDirectory(target, FOLDER) else Files.createFile(target, FILE).also { Files.write(it, Files.readAllBytes(path)) }
                }
            }
        }
        val old = home.resolve(".skills.kp-old")
        delete(old)
        if (home.resolve("skills").exists()) Files.move(home.resolve("skills"), old, ATOMIC_MOVE)
        Files.move(next, home.resolve("skills"), ATOMIC_MOVE)
        delete(old)
    }

    private fun delete(path: Path) {
        if (path.exists()) path.toFile().deleteRecursively().also { if (!it) throw IOException("$path cannot be removed") }
    }

    private fun quote(word: String) = "'" + word.replace("'", "'\\''") + "'"

    companion object {
        const val PACKAGING = "agents/packaging/claude.yaml"
        private val FOLDER = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }
}
