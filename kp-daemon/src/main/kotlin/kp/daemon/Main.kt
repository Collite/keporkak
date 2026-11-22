package kp.daemon

import kp.daemon.seats.ClaudeAgent
import kp.daemon.seats.Panes
import kp.daemon.seats.Roots
import kp.daemon.seats.Seated
import kp.daemon.seats.Seats
import kp.driver.claude.ClaudeAdapter
import kp.driver.home.CompiledAnswerer
import kp.mailroom.Clock
import kp.log.LogRefusal
import kp.mailroom.MailroomRefusal
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

private const val USAGE = """kp-daemon run [--root DIR] [--office NAME] [--project DIR] [--socket PATH] [--listen HOST:PORT]
kp-daemon rehearse <walk.yaml> [--root DIR] [--office NAME] [--socket PATH] [--seats scripted|fake --fake-classpath CP|real]
kp-daemon facts <stage> [--root DIR] [--office NAME] [--project DIR]
kp-daemon apps [--root DIR] [--office NAME]
  apps: what GitHub says of each App of pool/offices.yaml's block github: (`just apps`); it prints no token
  --root: the keporkak checkout whose rules are in force (default: the working folder)
  --seats: the walk's seats scripted (default), S2.1's fake agent run from --fake-classpath (`just rehearse <walk> --seats fake`),
           or Claude seats on the pinned adapter and the office's token (`just rehearse <walk> --seats real`: it spends tokens)
  facts: every fact of the project and of git for <stage>, its phase and its effort (`just facts <stage>`): it writes nothing
  --office: the office (default: the short host name) · --project: its project checkout (default: pool/offices.yaml's)
  --listen: where the seat API listens (default: the office's listen.mcp in pool/offices.yaml)
  --socket: the control socket (default: <runtime folder>/keporkak/kp.sock; the runtime folder is ${'$'}XDG_RUNTIME_DIR, or /run/user/<uid>)"""

/**
 * The daemon's entry (architecture.md §7). `run` is the office's daemon — the systemd user service: `just run`,
 * `just stop`, `just logs`. `rehearse` is `just rehearse <walk>`. Either stops cleanly on SIGTERM or Ctrl-C. `facts` is
 * `just facts <stage>` (contracts.md §12): it prints and ends, and needs no running daemon.
 */
fun main(args: Array<String>) {
    val words = args.toMutableList()
    val command = words.removeFirstOrNull()
    fun option(name: String): String? = words.indexOf(name).takeIf { it >= 0 }?.let { at -> words.removeAt(at); words.removeAtOrNull(at) ?: fail("$name needs a value") }
    val root = Path.of(option("--root") ?: ".").toAbsolutePath().normalize()
    val office = option("--office") ?: hostName().substringBefore('.')
    val project = option("--project")?.let(Path::of)
    val socket = option("--socket")?.let(Path::of) ?: defaultSocket()
    val seats = option("--seats") ?: "scripted"
    val fakeClasspath = option("--fake-classpath")
    val listen = option("--listen")
    if (command == "facts") {
        val stage = words.singleOrNull() ?: fail("facts names one stage")
        val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
        if (rules == null) fail("the rules are not valid:\n" + loaded.problems.joinToString("\n"))
        StageFacts.lines(rules, office, project ?: projectOf(root, office), stage).forEach(::println)
        return
    }
    if (command == "apps") {
        if (words.isNotEmpty()) fail("unknown words: $words")
        val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
        exitProcess(Apps.run(rules ?: fail("the rules are not valid:\n" + loaded.problems.joinToString("\n")), office) { println(it) })
    }
    try {
        val running: AutoCloseable = when (command) {
            "run" -> {
                if (words.isNotEmpty()) fail("unknown words: $words")
                service(root, office, project ?: projectOf(root, office), socket, listen)
            }
            "rehearse" -> {
                val walk = words.singleOrNull()?.let(Path::of) ?: fail("rehearse names one walk")
                val throwaway = Files.createTempDirectory(root.resolve("build/rehearsal").createDirectories(), "${walk.fileName.toString().removeSuffix(".yaml")}-")
                val seating = when (seats) {
                    "scripted" -> null
                    "fake" -> FakeSeating(fakeClasspath ?: fail("--seats fake needs --fake-classpath"))
                    "real" -> RealSeating()
                    else -> fail("--seats is scripted, fake or real")
                }
                Rehearsal.start(root, walk, throwaway, office, socket, SoPeerCred, seating = seating) { println(it) }.also { rehearsal ->
                    // With Claude seats the rehearsal ends with the walk's play — its seats stop with it, and spend no more.
                    if (seating is RealSeating) Thread.ofPlatform().name("kp-rehearsal-end").start {
                        rehearsal.awaitPlayed()
                        exitProcess(if (rehearsal.passed == true) 0 else 1)
                    }
                }
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
/** The service's clock: the machine's, to the second. */
internal val serviceClock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }

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
 * The office's daemon (S2.4 T8): the mailroom on the project's log, the seats in front of the driver — the Claude
 * adapter `just adapter` installed under the checkout's `office/adapter` — the seat API on `listen.mcp`, the watch every
 * `watch.every`, and the panes. Its world is `kp-facts` over the project checkout it runs on and the office's main
 * checkouts, with the fold (S3.1, [ProjectWorld]), and GitHub read as `kp-merger` (S3.2, [GitHubWorld]) — both handed
 * the fold before the start carries any judgement again, and a view's ask writing no journal line (S3.8, Amendment 34);
 * beside it, Bora's presence. It acts on GitHub as `kp-merger` — `git.merge`, `pr.close`, `issue.file` (S3.9,
 * [GitHubOperations]), handed the fold as the world is. Until the other performers exist (P3), it ends `notify.bora`
 * at once — the inbox is the notification, and `kp inbox --follow` raises it — and ends every other operation that is
 * not a seat's `failed`, saying no performer exists yet.
 */
private fun service(root: Path, office: String, project: Path, socket: Path, listening: String?): Daemon {
    val github = GitHubOperations.of(office, project, Operations { request, done ->
        done(if (request.operation == "notify.bora") OperationResult.Ok() else OperationResult.Failed("no performer for ${request.operation} on this office yet"))
    })
    val operations: Operations = github
    val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
    val entry = rules?.ruleSet?.pool?.offices?.get(office)
        ?: fail(if (rules == null) "the rules are not valid:\n" + loaded.problems.joinToString("\n") else "pool/offices.yaml names no office '$office'")
    val roots = Roots.of(entry.roots)
    val listen = listening ?: entry.listen["mcp"] ?: fail("pool/offices.yaml gives office '$office' no listen.mcp")
    val clock = serviceClock
    val world = ServiceWorld(ProjectWorld.of(checkNotNull(rules), office, project), GitHubWorld.of(office, project))
    val seated = Seated.start(office, project, root, world, operations, clock, listen, folded = { world.reading(it); github.reading(it) }) { starting ->
        Seats(root, office, project, roots, ClaudeAgent(root.resolve("office/adapter")), clock, Panes(say = Journal::say), Journal::say, starting) { listener, time ->
            ClaudeAdapter.driver(roots.secrets.resolve("claude-token"), roots.seats, time, CompiledAnswerer(roots.seats), listener)
        }
    }
    Journal.say(world.project.reads)
    try {
        seated.start()
    } catch (error: Exception) {
        seated.close()
        seated.office.close()
        throw error
    }
    Journal.say("seats: the seat API listens on ${seated.listen}; seats' homes under ${roots.seats}")
    return Daemon.start(seated.office, world.view, socket, SoPeerCred, beside = listOf(seated))
}
