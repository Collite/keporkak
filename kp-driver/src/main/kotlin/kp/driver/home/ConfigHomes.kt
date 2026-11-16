package kp.driver.home

import kp.driver.Answer
import kp.driver.McpServer
import kp.driver.Outcome
import kp.model.SeatId
import java.nio.file.Path

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
 */
class ConfigHomes(private val keporkak: Path, private val seatsRoot: Path) {
    fun build(seat: SeatId, role: String, profile: String, places: Places, defaults: PermissionDefaults): Outcome<Home> = TODO("T3")
}
