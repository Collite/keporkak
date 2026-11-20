package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.model.Rules
import org.slf4j.LoggerFactory
import org.slf4j.spi.SLF4JServiceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** S1.5 T7 — the process: started through the launcher `just install` installs, answering `kp`, stopped cleanly by SIGTERM. */
class DaemonProcessSpec : FreeSpec({
    "a daemon started through its launcher folds the log, answers kp, refuses a second daemon on its log or its socket, and on SIGTERM lets go of both" {
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "service-")
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        val daemon = Launched(project, socket)
        daemon.awaitLine("started: office panda")

        val status = kp(socket, "status")
        val sameLog = Launched(project, Files.createTempDirectory("kp-").resolve("kp.sock")).ended()
        val sameSocket = Launched(Files.createTempDirectory(checkout.resolve("build"), "service-"), socket).ended()
        // SIGTERM, as `systemctl --user stop` sends it; Process.destroy() would also close the streams the journal is read from.
        daemon.process.toHandle().destroy()
        val ended = daemon.process.waitFor(60, TimeUnit.SECONDS)

        status.code shouldBe 0
        status.text shouldStartWith "office panda · rules "
        sameLog.journal() shouldContain "would not start: another writer holds $project/mail"
        sameLog.process.exitValue() shouldBe 1
        sameSocket.journal() shouldContain "would not start: a daemon already answers on $socket"
        sameSocket.process.exitValue() shouldBe 1
        ended shouldBe true
        // The exit a stop gives, which office/systemd/keporkak.service counts as clean (S3.0 T6, `SuccessExitStatus=143`).
        daemon.process.exitValue() shouldBe 143
        daemon.awaitLine("stopped: office panda")
        socket.exists() shouldBe false
        TestOffice(project = project).use { again -> again.office.lines().map { it.entry }.filterIsInstance<Rules>().size shouldBe 2 }
    }

    "the service drives the seats (S2.4 T8): through main, its seat API listens where it is told — `listen.mcp`, unless `--listen` says otherwise — and answers a call without a live token 401" {
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "service-")
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        val daemon = Launched(project, socket)
        val listening = daemon.awaitLine("seats: the seat API listens on ").substringAfter("listens on ").substringBefore(';')
        daemon.awaitLine("started: office panda")

        val connection = java.net.URI.create("http://$listening/hook/stop").toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write("""{"stop_hook_active": false}""".toByteArray()) }
        val status = connection.responseCode
        daemon.process.toHandle().destroy()
        daemon.process.waitFor(60, TimeUnit.SECONDS)

        listening shouldStartWith "127.0.0.1:"
        status shouldBe 401
    }

    "a daemon started through its launcher reads its peer from the socket: `kp away` from this spec's own user is refused, logged, and leaves Bora present" {
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "service-")
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        val daemon = Launched(project, socket)
        daemon.awaitLine("started: office panda")

        val away = kp(socket, "away")
        val presence = kp(socket, "status").presence
        daemon.process.toHandle().destroy()
        daemon.process.waitFor(60, TimeUnit.SECONDS)
        val log = Log(project.resolve("mail"))

        away.code shouldBe 1
        away.text shouldContain "(unsigned: Bora's answers count only under sudo — sudo kp …)"
        log.messages("away").map { "signed=${it.signed} ${log.outcome(it)}" } shouldBe listOf("signed=false refused: no action from Bora's CLI for N hours (default 4), or `kp away`")
        presence shouldBe "present"
    }

    "at start the daemon says which project checkout and which main checkouts it reads (S3.1 T7): through main, its journal names the project it runs on and each repo `pool/offices.yaml` gives the office" {
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "service-")
        val daemon = Launched(project, Files.createTempDirectory("kp-").resolve("kp.sock"))
        daemon.awaitLine("started: office panda")
        daemon.process.toHandle().destroy()
        val repos = InForceRules.of(RuleFiles(checkout).read()).first!!.ruleSet.pool.offices.getValue("panda").repos.toSortedMap()

        val journal = daemon.journal()
        journal shouldContain "facts: the project checkout $project ("
        journal shouldContain "; main checkouts " + repos.entries.joinToString(", ") { (name, path) -> "$name ${path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))}" } + "\n"
    }

    "a daemon whose log has a torn line says why and exits 1" {
        val socket = Files.createTempDirectory("kp-").resolve("kp.sock")
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "torn-")
        project.resolve("mail/_office").createDirectories().resolve("panda-2026-11.jsonl").toFile().writeText("{\"v\":1")
        val daemon = Launched(project, socket)

        daemon.process.waitFor(60, TimeUnit.SECONDS) shouldBe true
        daemon.process.exitValue() shouldBe 1
        daemon.journal() shouldContain "would not start: $project/mail/_office/panda-2026-11.jsonl:1: the last line is torn"
    }

    "the daemon brings no SLF4J provider, so the ACP SDK writes nothing at trace — a frame carries a seat's token" {
        val installed = checkout.resolve("kp-daemon/build/install/kp-daemon/lib").listDirectoryEntries().map { it.name }

        ServiceLoader.load(SLF4JServiceProvider::class.java).toList().shouldBeEmpty()
        LoggerFactory.getLogger("com.agentclientprotocol.client.ClientSession").isTraceEnabled shouldBe false
        installed.filter { name -> PROVIDERS.any(name::startsWith) }.shouldBeEmpty()
        installed.any { it.startsWith("acp") } shouldBe true
    }

    "the office's project is the one pool/offices.yaml names, ~ being the office user's home" {
        projectOf(checkout, "panda") shouldBe Path.of(System.getProperty("user.home"), "Dev/keporkak/project")
    }
})

/** The jars that would give SLF4J somewhere to write. */
private val PROVIDERS = listOf("slf4j-simple", "slf4j-jdk14", "slf4j-reload4j", "slf4j-nop", "logback-classic", "log4j-slf4j", "slf4j-log4j")

/** The daemon as the unit starts it: the installed launcher, `run`, on [project], listening on [socket]. */
private class Launched(project: Path, socket: Path) {
    private val lines = LinkedBlockingQueue<String>()
    private val seen = StringBuilder()
    val process: Process = ProcessBuilder(
        checkout.resolve("kp-daemon/build/install/kp-daemon/bin/kp-daemon").toString(), "run",
        "--root", checkout.toString(), "--office", "panda", "--project", project.toString(), "--socket", socket.toString(), "--listen", "127.0.0.1:0",
    ).redirectErrorStream(true).start().also { process ->
        Thread.ofVirtual().start { process.inputStream.bufferedReader().forEachLine { lines += it } }
    }

    fun awaitLine(part: String): String {
        while (true) {
            val line = lines.poll(60, TimeUnit.SECONDS) ?: error("no line with '$part' in:\n$seen")
            seen.appendLine(line)
            if (part in line) return line
        }
    }

    /** This, once it has ended — or killed, when it has not within a minute. */
    fun ended(): Launched = apply { if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly() }

    /** Everything it wrote, once it has ended. */
    fun journal(): String {
        process.waitFor(60, TimeUnit.SECONDS)
        Thread.sleep(300)
        while (true) seen.appendLine(lines.poll() ?: break)
        return seen.toString()
    }
}

private fun kp(socket: Path, vararg args: String): Ran {
    val process = ProcessBuilder(listOf(checkout.resolve("scripts/kp").toString()) + args).redirectErrorStream(true)
        .apply { environment()["KP_SOCKET"] = socket.toString() }.start()
    val text = process.inputStream.bufferedReader().readText()
    process.waitFor(60, TimeUnit.SECONDS)
    return Ran(process.exitValue(), text)
}
