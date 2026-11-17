package kp.daemon

import kp.mailroom.Clock
import kp.mailroom.Deliveries
import kp.mailroom.FactSource
import kp.log.LogRefusal
import kp.mailroom.MailroomRefusal
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.model.Message
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

private const val USAGE = """kp-daemon run [--root DIR] [--office NAME] [--project DIR] [--socket PATH]
kp-daemon rehearse <walk.yaml> [--root DIR] [--office NAME] [--socket PATH]
  --root: the keporkak checkout whose rules are in force (default: the working folder)
  --office: the office (default: the short host name) · --project: its project checkout (default: pool/offices.yaml's)
  --socket: the control socket (default: <runtime folder>/keporkak/kp.sock; the runtime folder is ${'$'}XDG_RUNTIME_DIR, or /run/user/<uid>)"""

/**
 * The daemon's entry (architecture.md §7). `run` is the office's daemon — the systemd user service: `just run`,
 * `just stop`, `just logs`. `rehearse` is `just rehearse <walk>`. Either stops cleanly on SIGTERM or Ctrl-C.
 */
fun main(args: Array<String>) {
    val words = args.toMutableList()
    val command = words.removeFirstOrNull()
    fun option(name: String): String? = words.indexOf(name).takeIf { it >= 0 }?.let { at -> words.removeAt(at); words.removeAtOrNull(at) ?: fail("$name needs a value") }
    val root = Path.of(option("--root") ?: ".").toAbsolutePath().normalize()
    val office = option("--office") ?: hostName().substringBefore('.')
    val project = option("--project")?.let(Path::of)
    val socket = option("--socket")?.let(Path::of) ?: defaultSocket()
    try {
        val running: AutoCloseable = when (command) {
            "run" -> {
                if (words.isNotEmpty()) fail("unknown words: $words")
                service(root, office, project ?: projectOf(root, office), socket)
            }
            "rehearse" -> {
                val walk = words.singleOrNull()?.let(Path::of) ?: fail("rehearse names one walk")
                val throwaway = Files.createTempDirectory(root.resolve("build/rehearsal").createDirectories(), "${walk.fileName.toString().removeSuffix(".yaml")}-")
                Rehearsal.start(root, walk, throwaway, office, socket, SoPeerCred) { println(it) }
            }
            else -> fail(if (command == null) "a command" else "no command '$command'")
        }
        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            running.close()
            stopped.countDown()
        })
        stopped.await()
    } catch (error: RuntimeException) {
        // What would not let it start: the log (a torn line, another writer), the rules, or a daemon on the socket.
        if (error !is MailroomRefusal && error !is LogRefusal && error !is SocketRefusal) throw error
        Journal.say("would not start: ${error.message}")
        exitProcess(1)
    }
}

private fun fail(why: String): Nothing {
    System.err.println("kp-daemon: $why\n$USAGE")
    exitProcess(2)
}

/** The short host name the office check also defaults to: the kernel's, without a lookup that may fail. */
private fun hostName(): String =
    runCatching { Files.readString(Path.of("/proc/sys/kernel/hostname")).trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: InetAddress.getLocalHost().hostName

private fun <T> MutableList<T>.removeAtOrNull(index: Int): T? = if (index < size) removeAt(index) else null

/** The control socket in the office user's runtime folder (contracts.md §6): where `kp` looks for it. */
internal fun defaultSocket(): Path {
    val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: "/run/user/${Files.getAttribute(Path.of("/proc/self"), "unix:uid")}"
    return Path.of(runtime, "keporkak", "kp.sock")
}

/** The office's one project checkout (contracts.md §9: `pool/offices.yaml`), `~` being the office user's home. */
internal fun projectOf(root: Path, office: String): Path {
    val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
    val checkout = rules?.ruleSet?.pool?.offices?.get(office)?.project?.checkout
        ?: fail(if (rules == null) "the rules are not valid:\n" + loaded.problems.joinToString("\n") else "pool/offices.yaml names no office '$office'")
    return Path.of(checkout.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))
}

/**
 * The office's daemon. Until its observers and performers exist (S2–S3), it observes nothing but Bora's presence, ends
 * `notify.bora` at once — the inbox is the notification, and `kp inbox --follow` raises it — and ends every other
 * operation `failed`, saying no performer exists yet; and no seat is idle, so a message waits in its seat's inbox.
 */
private fun service(root: Path, office: String, project: Path, socket: Path): Daemon {
    val nothing = FactSource { _, _ -> null }
    val operations = Operations { request, done ->
        done(if (request.operation == "notify.bora") OperationResult.Ok() else OperationResult.Failed("no performer for ${request.operation} on this office yet"))
    }
    val seats = object : Deliveries {
        override fun idle(seat: String) = false
        override fun wake(seat: String, message: Message) = false
    }
    val started = Office.start(office, project, root, nothing, operations, seats, Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) })
    return Daemon.start(started, nothing, socket, SoPeerCred)
}
