@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * GitHub's git over https, on this machine (S3.11 T1, T5): a TLS server that answers for `github.com` with a certificate
 * of the spec's own, made by the JDK's `keytool` — [authority] is that certificate, which a seat's process is told to
 * trust — behind a proxy that tunnels `CONNECT github.com:443` to it and refuses any other target. A seat's process is
 * handed the two ([network]); nothing else of its environment changes, and nothing leaves the machine.
 *
 * The server answers a push as GitHub's smart HTTP does as far as the credential: `info/refs` with none is 401 Basic; with
 * one, an empty repository's advertisement — git then takes the credential as good and tells its helpers to keep it —
 * and the pack itself is refused, 403. What each request asked and the password of its credential are kept ([seen]),
 * and every target the proxy was asked to reach ([connects]).
 */
class GitOverHttps : AutoCloseable {
    /** One request the server answered: its method, its path and query, and the password of its credential, if it held one. */
    data class Seen(val method: String, val target: String, val password: String?)

    private val folder: Path = Files.createTempDirectory("kp-github-tls-")
    private val keystore: Path = folder.resolve("github.p12")
    val authority: Path = folder.resolve("github.pem")
    val seen = CopyOnWriteArrayList<Seen>()
    val connects = CopyOnWriteArrayList<String>()
    private val server: HttpsServer
    private val proxy = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        keytool("-genkeypair", "-alias", "github", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=github.com",
            "-ext", "san=dns:github.com", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
        keytool("-exportcert", "-rfc", "-alias", "github", "-keystore", keystore.toString(), "-storepass", PASS, "-file", authority.toString())
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keystore).use { load(it, PASS.toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASS.toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(tls)
            createContext("/") { exchange -> exchange.use { answer(it) } }
            start()
        }
        thread(isDaemon = true, name = "kp-spec-proxy") {
            while (!proxy.isClosed) {
                val client = runCatching { proxy.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { tunnel(client) }
            }
        }
    }

    /** What a seat's process is handed to reach this GitHub: the proxy, and the authority to trust — git's and `gh`'s ways of taking them. */
    fun network(): Map<String, String> {
        val proxied = "http://127.0.0.1:${proxy.localPort}"
        return mapOf("https_proxy" to proxied, "HTTPS_PROXY" to proxied, "GIT_SSL_CAINFO" to authority.toString(), "SSL_CERT_FILE" to authority.toString())
    }

    /** The passwords the server was handed with a request, in order. */
    fun passwords(): List<String> = seen.mapNotNull { it.password }

    private fun answer(exchange: HttpExchange) {
        val target = exchange.requestURI.toString()
        val password = exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Basic ") }
            ?.let { String(Base64.getDecoder().decode(it.removePrefix("Basic ").trim())).substringAfter(':') }
        seen += Seen(exchange.requestMethod, target, password)
        exchange.requestBody.readAllBytes()
        when {
            target.contains("/info/refs?service=git-receive-pack") && password == null -> {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"GitHub\"")
                send(exchange, 401, "text/plain", "the spec's GitHub asks for a credential\n".toByteArray())
            }
            target.contains("/info/refs?service=git-receive-pack") ->
                send(exchange, 200, "application/x-git-receive-pack-advertisement", (pkt("# service=git-receive-pack\n") + FLUSH +
                    pkt("$ZERO capabilities^{}\u0000report-status delete-refs ofs-delta\n") + FLUSH).toByteArray())
            else -> send(exchange, 403, "text/plain", "the spec's GitHub takes no pack\n".toByteArray())
        }
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    /** A proxy's connection: `CONNECT github.com:443` is tunnelled to the server; any other target is refused, 403. */
    private fun tunnel(client: Socket) = client.use {
        val input = client.getInputStream()
        val head = generateSequence { line(input) }.takeWhile { it.isNotEmpty() }.toList()
        val target = head.firstOrNull()?.split(' ')?.getOrNull(1).orEmpty()
        connects += target
        val output = client.getOutputStream()
        if (head.firstOrNull()?.startsWith("CONNECT ") != true || target != "github.com:443") {
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return@use
        }
        Socket(InetAddress.getLoopbackAddress(), server.address.port).use { upstream ->
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            val back = thread(isDaemon = true) { pipe(upstream.getInputStream(), output) }
            pipe(input, upstream.getOutputStream())
            back.join(TimeUnit.SECONDS.toMillis(30))
        }
    }

    override fun close() {
        proxy.close()
        server.stop(0)
        folder.deleteRecursively()
    }

    private companion object {
        const val PASS = "kp-spec-only"
        const val FLUSH = "0000"
        val ZERO = "0".repeat(40)

        fun pkt(text: String): String = "%04x".format(text.toByteArray().size + 4) + text

        /** One header line of the proxy's request, without its line end; null at the end of the stream. */
        fun line(input: InputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1)
                if (b == '\n'.code) return bytes.toString(Charsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(b)
            }
        }

        fun pipe(from: InputStream, to: OutputStream) {
            runCatching {
                val buffer = ByteArray(16384)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            }
            runCatching { to.close() }
        }

        fun keytool(vararg args: String) {
            val tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(listOf(tool) + args).redirectErrorStream(true).start()
            val said = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool ${args.first()} failed: $said" }
        }
    }
}

/**
 * The office user's home as a seat's process finds it, with everything planted that a seat must not use (S3.11 T1, T5;
 * contracts.md §4.4, Amendment 43): a login of `gh`'s; a global git configuration with a credential helper, a rewrite of
 * GitHub's https to ssh, an identity, an ssh command and a hook path; a system configuration — what `/etc/gitconfig`
 * would hold, handed as the office's environment would hand it ([system]) — with a helper and a rewrite; an ssh
 * configuration with a host alias of GitHub's. Each planted program notes in [used] that it ran, and nothing else.
 */
class PlantedHome(val home: Path) {
    val used: Path = home.resolve("planted-used")
    private val systemConfig: Path = home.resolve("planted-system-gitconfig")

    init {
        Files.createDirectories(home.resolve(".config/gh"))
        home.resolve(".config/gh/hosts.yml").writeText("github.com:\n    oauth_token: gho_plantedOfficeUsersLogin000000000000\n    user: planted-office-user\n    git_protocol: ssh\n")
        Files.createDirectories(home.resolve(".config/gh")).resolve("config.yml").writeText("git_protocol: ssh\n")
        val ssh = program("planted-ssh", "ssh")
        val helper = program("planted-helper", "helper")
        val hooks = Files.createDirectories(home.resolve("planted-hooks"))
        listOf("pre-commit", "post-commit", "pre-push").forEach { program("planted-hooks/$it", "hook $it") }
        home.resolve(".gitconfig").writeText(
            """
            [user]
            	name = Planted Office User
            	email = planted@office.invalid
            [credential]
            	helper = store
            	helper = $helper
            [url "git@github.com:"]
            	insteadOf = https://github.com/
            [core]
            	sshCommand = $ssh
            	hooksPath = $hooks
            """.trimIndent() + "\n",
        )
        systemConfig.writeText(
            """
            [user]
            	name = Planted System
            	email = system@office.invalid
            [credential]
            	helper = $helper
            [url "ssh://git@github.com/"]
            	insteadOf = https://github.com/
            [core]
            	sshCommand = $ssh
            """.trimIndent() + "\n",
        )
        Files.createDirectories(home.resolve(".ssh"))
        home.resolve(".ssh/config").writeText("Host gh-planted\n    HostName github.com\n    User git\n    IdentityFile ~/.ssh/planted_key\n")
    }

    /** The system configuration, as the office's environment would point git at `/etc/gitconfig`. */
    fun system(): Map<String, String> = mapOf("GIT_CONFIG_SYSTEM" to systemConfig.toString())

    /** What of the planted ran, one line each: `ssh`, `helper`, `hook <name>`. */
    fun ran(): List<String> = if (used.exists()) used.readLines().filter { it.isNotBlank() } else emptyList()

    /** `~/.git-credentials`, where git's `store` helper keeps what it is told to. */
    val credentials: Path get() = home.resolve(".git-credentials")

    private fun program(name: String, note: String): Path = home.resolve(name).also { file ->
        Files.createDirectories(file.parent)
        file.writeText("#!/bin/sh\necho '$note' >> '$used'\ncat >/dev/null 2>&1 </dev/null\nexit 1\n")
        file.toFile().setExecutable(true)
    }
}
