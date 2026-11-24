@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kp.daemon.GitHubOffice
import kp.daemon.checkout
import kp.driver.Answer
import kp.driver.Outcome
import kp.driver.home.ConfigHomes
import kp.driver.home.PermissionDefaults
import kp.driver.home.Places
import kp.model.SeatId
import kp.seatapi.Binding
import kp.seatapi.SeatApi
import kp.seatapi.Tokens
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

/**
 * S3.7 T1's seam for an ask and for the home's two programs: the seat API's listener over HTTP, its registry holding the
 * session tokens the spec makes, in front of S3.2's local GitHub ([GitHubOffice]: the App endpoints and the recorded
 * responses on one local server; the four Apps with keys of the spec's; a project whose toy stage `TOY/P0/S0.1` names
 * `kp-toy`). The daemon's [GitHubIdentity] answers each ask; its journal lines are kept in [journal], and the tokens it
 * says to hide in [hidden]. A seat's home is built by the builder for its role, and its programs run as processes in the
 * seat's environment — the one [SeatEnvironment] makes from the agent's, with the driver's `KP_HOOK_URL` and
 * `KP_SESSION_TOKEN` — beside an office's `gh` of the spec's ([officeGh]) and the office user's own git configuration.
 */
class IdentityOffice(
    /** What the office's environment adds to the agent's, as `/etc/gitconfig` would be pointed at (S3.11). */
    private val base: Map<String, String> = emptyMap(),
) : AutoCloseable {
    val github = GitHubOffice()
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "identity-")
    val tokens = Tokens()
    val journal = CopyOnWriteArrayList<String>()
    val hidden = CopyOnWriteArrayList<String>()
    val identity = GitHubIdentity("panda", github.project, { github.office.rules }, github.secrets.resolve("apps"), github.apps.api, journal::add) { _, token -> hidden += token }
    private var api: SeatApi? = github.office.seatApi(tokens, "127.0.0.1:0", identity)
    private val port = api!!.address.port
    /** The office user's home, as a seat's process sees it: its own git configuration is there. */
    val home: Path = folder.resolve("home").createDirectories()
    /** Where the office's `gh` is: a stub that notes each argument and the SHA-256 of its GH_TOKEN, and ends [officeGh]'s way. */
    private val officeBin: Path = folder.resolve("office-bin").createDirectories()

    /** A seat bound to [role] in [stage], with a live session token and a home built for its role. */
    fun seat(name: String, role: String, stage: String?): SpecSeat {
        val token = "kp-spec-" + ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        tokens.register(token, Binding(name, role, "panda", stage, "claude-opus-5-5", "medium"))
        return SpecSeat(name, token, built(name, role))
    }

    /** The App a token the local GitHub minted was minted for; null for a token it never minted. */
    fun issuedTo(token: String): String? = github.apps.issued.firstOrNull { it.token == token }?.app?.identity

    /** A session token never issued. */
    fun stranger(): SpecSeat = SpecSeat("stranger", "kp-spec-never-issued", folder)

    /** The listener stops: an ask from now on is answered by nothing. */
    fun down() {
        api?.close()
        api = null
    }

    /** The office user's own git configuration: what a seat's git must not use. */
    fun userGitConfig(text: String) = home.resolve(".gitconfig").writeText(text)

    /** The office's `gh`: it notes each argument, NUL-ended, and the SHA-256 of GH_TOKEN — never the token — and exits [exit]. */
    fun officeGh(exit: Int) {
        val stub = officeBin.resolve("gh")
        stub.writeText(
            """
            #!/bin/sh
            for argument in "${'$'}@"; do printf '%s\0' "${'$'}argument"; done > "${officeBin.resolve("arguments")}"
            printf '%s' "${'$'}{GH_TOKEN:-}" | sha256sum | cut -d' ' -f1 > "${officeBin.resolve("token.sha256")}"
            echo run >> "${officeBin.resolve("runs")}"
            exit $exit
            """.trimIndent() + "\n",
        )
        stub.toFile().setExecutable(true)
    }

    /** The arguments the office's `gh` was last run with. */
    fun officeGhArguments(): List<String> = officeBin.resolve("arguments").readText().split('\u0000').dropLast(1)

    /** The SHA-256 of the GH_TOKEN the office's `gh` last ran with. */
    fun officeGhToken(): String = officeBin.resolve("token.sha256").readText().trim()

    /** How often the office's `gh` ran. */
    fun officeGhRuns(): Int = officeBin.resolve("runs").takeIf { it.isRegularFile() }?.readText()?.lines()?.count { it == "run" } ?: 0

    /** Every file of the spec's folder and of the project that holds [text]. */
    fun filesHolding(text: String): List<Path> =
        (folder.walk() + github.project.walk()).filter { it.isRegularFile() && text in String(Files.readAllBytes(it), Charsets.ISO_8859_1) }.toList()

    override fun close() {
        down()
        github.close()
    }

    private fun built(seat: String, role: String): Path {
        val worktree = folder.resolve("work/$seat").createDirectories()
        val places = Places(worktree, folder.resolve("scratch/$seat").createDirectories(), github.project, github.project.resolve("toy/tasks-p0-s0.1-skeleton.md"), listOf(folder.resolve("code")), "127.0.0.1:$port")
        val built = when (val made = ConfigHomes(checkout, folder.resolve("seats")).build(SeatId(seat), role, "toy", places, PermissionDefaults(Answer.ALLOW, Answer.DENY, Answer.ALLOW))) {
            is Outcome.Ok -> made.value
            is Outcome.Failed -> error("the home of $seat cannot be built: ${made.reason}")
        }
        environments[seat] = SeatEnvironment.of(mapOf("LANG" to "C.UTF-8", "HOME" to home.toString(), "PATH" to "$officeBin:/usr/bin:/bin") + base, built.programs)
        return worktree
    }

    private val environments = HashMap<String, Map<String, String>>()

    /** What an ask answered: its status, the token and its expiry when one was handed, the reason when none was. */
    data class Asked(val status: Int, val token: String?, val expires: String?, val error: String?)

    /** What a program ran in a seat's environment did: its exit code, its standard output and its standard error. */
    data class Ran(val exit: Int, val out: String, val err: String)

    inner class SpecSeat(val name: String, private val token: String, private val worktree: Path) {
        /** `POST /hook/github-token` with this seat's session token, no body, as the home's programs ask. */
        fun ask(): Asked {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/hook/github-token"))
                .header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.noBody()).build()
            val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            val body = runCatching { Json.parseToJsonElement(answer.body()).jsonObject }.getOrNull()
            fun text(json: JsonObject?, key: String) = (json?.get(key) as? JsonPrimitive)?.contentOrNull
            val error = text(body, "error") ?: text(body?.get("error") as? JsonObject, "message")
            return Asked(answer.statusCode(), text(body, "token"), text(body, "expires"), error)
        }

        /** Its session ended: the token is dead. */
        fun dies() = tokens.revoke(token)

        /**
         * [command] run in this seat's environment, in its worktree, with [input] on its standard input — then closed — and
         * the driver's two variables beside; it must end within [seconds]. The program is found on the seat's path, as a
         * seat's shell finds it — not on the spec's. [network] is how the machine reaches GitHub: S3.11's local one, given
         * after the seat's environment.
         */
        fun run(vararg command: String, input: String = "", seconds: Long = 30, network: Map<String, String> = emptyMap()): Ran {
            val builder = ProcessBuilder(listOf("/usr/bin/env", "--") + command).directory(worktree.toFile())
            builder.environment().clear()
            builder.environment().putAll(environments.getValue(name) + mapOf("KP_HOOK_URL" to "http://127.0.0.1:$port", "KP_SESSION_TOKEN" to token) + network)
            val process = builder.start()
            process.outputStream.use { it.write(input.toByteArray()) }
            val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
            val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("${command.joinToString(" ")} did not end in ${seconds}s")
            }
            return Ran(process.exitValue(), out.get(), err.get())
        }
    }

    companion object {
        /** The SHA-256 of [text], in hex. */
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
