package kp.driver.handshake

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlMap
import kp.driver.Outcome
import kp.driver.home.ConfigHomes
import kp.driver.home.PermissionDefaults
import kp.driver.home.Places
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * `HomesKt` (S2.6 T6): the driver's builder makes a home for every persona of the checkout and every profile, into a
 * folder the caller gives, with throwaway places — so that the office check reads seats' compiled permissions
 * (contracts.md §6, §12). It starts no session, reads no secret and spends no token. One line per home on stdout,
 * `<seat> <home>`; each home it cannot build on stderr, and exit 1.
 *
 * Usage: HomesKt --root <keporkak checkout> --out <folder>
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it.first() to it.getOrNull(1) }
    val root = Path.of(options["--root"] ?: ".").toAbsolutePath().normalize()
    val out = options["--out"]?.let { Path.of(it).toAbsolutePath().normalize() }
        ?: run { System.err.println("usage: HomesKt --root <keporkak checkout> --out <folder>"); exitProcess(2) }
    val places = out.resolve("places").let { Places(it.resolve("worktree"), it.resolve("scratch"), it.resolve("project"), it.resolve("project/tasks.md"), listOf(it.resolve("code")), "127.0.0.1:1") }
    val defaults = when (val read = permissionDefaults(root)) {
        is Outcome.Ok -> read.value
        is Outcome.Failed -> { System.err.println(read.reason); exitProcess(1) }
    }
    val roles = names(root.resolve("agents")) { it.isDirectory() && it.resolve("AGENT.md").exists() }
    val profiles = names(root.resolve("docs/profiles")) { it.name.endsWith(".md") }.map { it.removeSuffix(".md") }
    var failed = false
    for (role in roles) for (profile in profiles) {
        val seat = "$role-$profile"
        when (val built = ConfigHomes(root, out.resolve("seats")).build(SeatId(seat), role, profile, places, defaults)) {
            is Outcome.Ok -> println("$seat ${built.value.path}")
            is Outcome.Failed -> { System.err.println("$seat: ${built.reason}"); failed = true }
        }
    }
    exitProcess(if (failed || roles.isEmpty()) 1 else 0)
}

/** `engagement.yaml:permissions` of the checkout, read as the daemon will hand it to the builder. */
internal fun permissionDefaults(root: Path): Outcome<PermissionDefaults> {
    val block = runCatching { Yaml.default.parseToYamlNode(root.resolve("rules/engagement.yaml").readText()).yamlMap.get<YamlMap>("permissions") }.getOrNull()
        ?: return Outcome.Failed("rules/engagement.yaml has no permissions block")
    return PermissionDefaults.of(block.entries.entries.associate { (key, value) -> key.content to ((value as? YamlScalar)?.content ?: "") })
}

private fun names(folder: Path, keep: (Path) -> Boolean): List<String> =
    Files.list(folder).use { paths -> paths.filter(keep).map { it.name }.sorted().toList() }
