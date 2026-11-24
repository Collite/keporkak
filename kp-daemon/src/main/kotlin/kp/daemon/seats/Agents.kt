package kp.daemon.seats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kp.driver.home.ConfigHomes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * A seat's environment for GitHub (contracts.md §4.4, Amendments 35, 43), over its agent's: the folder of its home's two
 * programs first on its path — so that its `gh` is the home's — and nothing of the office user's GitHub. A seat runs as
 * the office's user (D-19), so its environment points every tool away from that user's configuration:
 * - `gh`'s configuration is a folder of the seat's home that holds no login: the office's `gh`, run by any path, finds
 *   none and acts only with the token the home's `gh` gives it;
 * - `git` reads no configuration of the user's or the system's — no helper, rewrite, identity, hook path or ssh command
 *   of theirs: its global configuration is the seat's own, written here into its home, and the system's is not read;
 * - the seat commits as its App — author and committer `<app> <<app>@keporkak.invalid>`;
 * - `git` uses the home's credential helper and no other (the helpers of every configuration it does read are emptied
 *   first), reaches GitHub over https for every form of a remote written for ssh, and its own ssh command refuses every
 *   call — a remote that would reach GitHub through an ssh host alias is refused, not followed — and never prompts.
 * No GitHub token is in it: the two programs ask the daemon for one at each use. What an environment cannot close stays
 * open and is the persona's border: a seat can read the office user's files, and `ssh` run by the seat itself.
 */
object SeatEnvironment {
    /**
     * [base] — the agent's environment — for a seat whose home is [home], acting as the App [app]: the one `github.seats`
     * gives its role, else the role's name. Writes the seat's own git configuration and makes its `gh` folder in [home].
     */
    fun of(base: Map<String, String>, home: Path, app: String): Map<String, String> {
        val programs = home.resolve(ConfigHomes.PROGRAMS)
        val gitconfig = home.resolve(GITCONFIG)
        Files.writeString(gitconfig, "# The seat's own git configuration (contracts.md §4.4, Amendment 43), written by the daemon at each start:\n" +
            "# git reads no configuration of the office user's or the system's.\n[user]\n\tname = $app\n\temail = ${email(app)}\n")
        val gh = home.resolve(GH).createDirectories()
        val config = listOf("credential.helper" to "", "credential.helper" to programs.resolve(ConfigHomes.HELPER).toString()) +
            SSH_FORMS.map { form -> "url.$GITHUB_HTTPS.insteadOf" to form }
        return base.filterKeys { it !in DROPPED } + mapOf(
            "PATH" to "$programs:${base["PATH"] ?: DEFAULT_PATH}",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to gitconfig.toString(),
            "GIT_AUTHOR_NAME" to app,
            "GIT_AUTHOR_EMAIL" to email(app),
            "GIT_COMMITTER_NAME" to app,
            "GIT_COMMITTER_EMAIL" to email(app),
            "GIT_SSH_COMMAND" to REFUSING_SSH,
            "GIT_SSH_VARIANT" to "simple",
            "GH_CONFIG_DIR" to gh.toString(),
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to "",
            "SSH_ASKPASS" to "",
            "GIT_CONFIG_COUNT" to config.size.toString(),
        ) + config.flatMapIndexed { index, (key, value) -> listOf("GIT_CONFIG_KEY_$index" to key, "GIT_CONFIG_VALUE_$index" to value) }
    }

    /** What a seat's ssh says, and is: refused. Git appends the host and the command after it; the comment swallows them. */
    const val REFUSAL = "a seat reaches GitHub over https only: git's ssh is refused (contracts.md §4.4, Amendment 43)"

    /** An App's address, no person's: `<app>@keporkak.invalid`. */
    fun email(app: String): String = "$app@keporkak.invalid"

    private const val GITCONFIG = "gitconfig"
    private const val GH = "gh"
    private const val GITHUB_HTTPS = "https://github.com/"
    private const val REFUSING_SSH = "echo \"keporkak: $REFUSAL\" >&2; exit 128 #"

    /** Every form of a GitHub remote written for ssh (Amendment 43), each rewritten to https. */
    private val SSH_FORMS = listOf(
        "git@github.com:", "ssh://git@github.com/", "ssh://github.com/", "git+ssh://git@github.com/", "git+ssh://github.com/",
        "ssh+git://git@github.com/", "ssh+git://github.com/", "github.com:",
    )

    /** What would point git or `gh` past this environment, if the agent's carried it: dropped. */
    private val DROPPED = setOf("GIT_CONFIG_SYSTEM", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_SSH", "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GH_HOST")

    /** The path of an agent whose environment names none. */
    private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"
}

/**
 * The Claude adapter as a seat's agent (contracts.md §9, §10; architecture.md §5): `node` running the pinned adapter
 * installed in [adapter] by `just adapter`. Its environment is built, not inherited: `PATH` with node's folder, the office
 * user's `HOME`, `LANG`. The driver's binding adds the config home and the Claude token.
 */
class ClaudeAgent(private val adapter: Path, private val home: Path = Path.of(System.getProperty("user.home"))) : Agent {
    private val node: Path = System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }.map { Path.of(it, "node") }.firstOrNull { it.isExecutable() }
        ?: Path.of("/usr/bin/node")

    override fun command(seat: String, role: String): List<String> = listOf(node.toString(), adapter.resolve("node_modules/$ADAPTER/dist/index.js").toString())

    override fun environment(seat: String): Map<String, String> =
        mapOf("PATH" to "${node.parent}:/usr/local/bin:/usr/bin:/bin", "HOME" to home.toString(), "LANG" to "C.UTF-8")

    companion object {
        const val ADAPTER = "@agentclientprotocol/claude-agent-acp"
    }
}

/**
 * S2.1's fake agent as every seat's agent (`kp.driver.fake.FakeAgentKt`, the driver's test fixtures): what the daemon's
 * specs and `just rehearse <walk> --seats fake` start. Each seat follows its script `<folder>/<seat>.json` — written
 * here when there is none: every prompt ends its turn at once, the roster's efforts are offered, and the process calls
 * the stop hook with its token before anything else — and records what it received in `<folder>/<seat>.jsonl`.
 */
class FakeAgents(private val classpath: String, val folder: Path) : Agent {
    private val java: Path = Path.of(System.getProperty("java.home"), "bin", "java")

    override fun command(seat: String, role: String): List<String> =
        listOf(java.toString(), "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", classpath, MAIN, script(seat).toString())

    override fun environment(seat: String): Map<String, String> = mapOf("LANG" to "C.UTF-8")

    /** [seat]'s script: its own when one was written for it, else the default. */
    fun script(seat: String): Path {
        val file = folder.createDirectories().resolve("$seat.json")
        if (!file.exists()) file.writeText(JsonObject(mapOf("record" to JsonPrimitive(record(seat).toString()), "probe" to JsonPrimitive(true),
            "efforts" to Json.parseToJsonElement(EFFORTS))).toString())
        return file
    }

    /** Where [seat]'s fake agents record what they received, one JSON object a line. */
    fun record(seat: String): Path = folder.resolve("$seat.jsonl")

    /** What [seat]'s fake agents received, in order. */
    fun received(seat: String): List<JsonObject> = record(seat).takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    /** The pid of [seat]'s last fake agent. */
    fun pid(seat: String): Long? = received(seat).lastOrNull { (it["event"] as? JsonPrimitive)?.contentOrNull == "start" }?.let { (it["pid"] as? JsonPrimitive)?.longOrNull }

    /** [seat]'s agent dies — its process tree ended from outside, as a crash would; false when none runs. */
    fun die(seat: String): Boolean {
        val process = pid(seat)?.let { ProcessHandle.of(it).orElse(null) }?.takeIf { it.isAlive } ?: return false
        process.descendants().forEach { it.destroyForcibly() }
        return process.destroyForcibly()
    }

    companion object {
        const val MAIN = "kp.driver.fake.FakeAgentKt"
        private const val EFFORTS = """["low", "medium", "high", "xhigh", "max"]"""

        /** A secrets folder for fake seats: the Claude token file the driver's binding reads, holding no token. */
        fun secrets(folder: Path): Path = folder.createDirectories().also { Files.writeString(it.resolve("claude-token"), "no-token-the-fake-agent-needs-none\n") }
    }
}
