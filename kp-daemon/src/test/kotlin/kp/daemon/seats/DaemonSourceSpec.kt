package kp.daemon.seats

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.yamlMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.daemon.checkout
import kp.driver.scan.comparisons
import kp.driver.scan.timers
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * S2.4 DONE — the daemon's source compares no observation with a limit and holds no kind name: scanned as S2.1's spec
 * scans the driver's (`SeatFactsSpec`, the scan in the driver's test fixtures). The daemon reports what it observes and
 * performs what the rules ask for; a limit and a kind are the rules'.
 */
class DaemonSourceSpec : StringSpec({
    val sources = Files.walk(checkout.resolve("kp-daemon/src/main")).use { files -> files.filter { it.extension == "kt" }.toList() }.sortedBy { it.toString() }

    "the daemon's source compares no observation with a limit" {
        // Every line of the daemon's source that compares, in any form, is listed as written with what it compares: a new
        // one — an observation against a limit among them — or a listed one changed turns this red until it is listed
        // again, and a reviewer reads why.
        val found = sources.flatMap { file -> comparisons(file.readText()).map { "${file.name}: $it" } }

        found.sorted() shouldBe NOT_A_LIMIT.keys.sorted()
    }

    "the daemon's source holds no timer that stands for a limit" {
        // A timer compares nothing, so the scan above does not see it — S2.4's two-second grace before a closed protocol
        // was reported was one (S2.9 T3). Every line that sets a timer or waits for a time is listed, as written, with
        // why it is none: a new one, or a listed one changed, turns this red until it is listed again.
        val found = sources.flatMap { file -> timers(file.readText()).map { "${file.name}: $it" } }

        found.sorted() shouldBe NOT_A_LIMIT_TIMER.keys.sorted()
    }

    "the daemon's source holds no kind's name, but where Bora's commands are the kinds they send" {
        val kinds = Yaml.default.parseToYamlNode(checkout.resolve("rules/message-kinds.yaml").readText()).yamlMap.get<YamlMap>("kinds")!!.entries.keys.map { it.content }
        val named = sources.flatMap { file -> kinds.filter { "\"$it\"" in file.readText() }.map { "${file.name}: $it" } }

        kinds shouldContain "assign"
        named.map { it.substringBefore(':') }.distinct().sorted() shouldBe KINDS_NAMED.keys.sorted()
    }
})

/** The files that name kinds, and why: each maps one of Bora's commands to the kind it sends (contracts.md §6). */
private val KINDS_NAMED = mapOf(
    "KpCommands.kt" to "`kp approve`, `decline`, `rule`, `reverse`, `queue`, `away`: each of Bora's commands is the kind it sends (contracts.md §6)",
    "Rehearsal.kt" to "a rehearsal waiting for Bora names the `kp` command that sends the kind the walk asks of him",
)

/** Every line of the daemon's source that compares, by file and as written, and why none is an observation against a limit. */
private val NOT_A_LIMIT = mapOf(
    "Check.kt: (all.size - lines.size).takeIf { it > 0 }?.let { skipped ->" to
        "`kp check`: how many lines of a log it skipped \u2014 a count it reports",
    "Check.kt: hand.forEach { (file, lines) -> handLog(file, lines.sortedBy { it.entry.seq }) }" to
        "`kp check`: a hand log's lines in `seq` order",
    "Check.kt: if (daemon.any { it.value.isNotEmpty() }) daemonLog(daemon.flatMap { it.value }.sortedBy { it.entry.seq })" to
        "`kp check`: a daemon log's lines in `seq` order",
    "Check.kt: path.isDirectory() -> path.walk().filter { it.isRegularFile() && it.name.endsWith(\".jsonl\") }.sorted().toList()" to
        "`kp check`: a folder's log files in the order of their names",
    "ControlSocket.kt: if (channel.read(byte) < 0) return Read.Bad(\"the request ended before its line did\")" to
        "the control socket: a read that hit the end of its stream",
    "ControlSocket.kt: if (line.size() >= LIMIT) return Read.Bad(\"the request is longer than \$LIMIT bytes\")" to
        "the control socket: a request's length against the protocol's own bound (S1.5) \u2014 an input, not an observation",
    "Daemon.kt: if (office.nextDeadline()?.let { !it.isAfter(office.clock.now()) } == true) office.tick()" to
        "the timers: a deadline the rules set, against the clock \u2014 when to fire it",
    "Daemon.kt: if (wait > 0) Thread.sleep(wait)" to
        "the timers: how long to sleep until the next deadline or reload",
    "Daemon.kt: val next = listOfNotNull(office.nextDeadline(), now.plus(RELOAD_EVERY)).min()" to
        "the timers: the earlier of the next deadline and the next reload",
    "KpCommands.kt: \"approve\" -> answer(out, peer, args, 1..1, \"kp approve <ticket>\") { item -> message(APPROVAL, item) }" to
        "`kp approve`: how many words the command takes",
    "KpCommands.kt: \"decline\" -> answer(out, peer, args, 1..Int.MAX_VALUE, \"kp decline <ticket> [reason]\") { item -> message(DECLINE, item, body = args.drop(1)) }" to
        "`kp decline`: how many words the command takes",
    "KpCommands.kt: \"rule\" -> answer(out, peer, args, 2..Int.MAX_VALUE, \"kp rule <ticket | message id> <outcome> [key=value \u2026] [text]\", byId = true) { item ->" to
        "`kp rule`: how many words the command takes",
    "KpCommands.kt: answer(out, peer, args, 2..2, \"kp reverse <digest item> minor|serious\", byId = true) { item ->" to
        "`kp reverse`: how many words the command takes (S2.7 T6: it names its item as `kp rule` does)",
    "KpCommands.kt: \"status\" -> if (args.size > 1) usage(out, \"kp status [effort | stage | seat]\") else print(out, views.status(args.firstOrNull()))" to
        "`kp status`: how many words the command takes",
    "Levels.kt: val above = roster.order.withIndex().filter { (index, line) -> index >= rank && roster.order.indexOfFirst { it.model == line.model } >= floor }.map { it.value }" to
        "a seat's line (S2.4): the roster's order at or above another role's and the review floor \u2014 configuration, not an observation",
    "Levels.kt: val rank = roster.order.indexOf(base).takeIf { it >= 0 } ?: return null" to
        "a seat's line (S2.4): whether a line is in the roster's order at all",
    "Main.kt: fun option(name: String): String? = words.indexOf(name).takeIf { it >= 0 }?.let { at -> words.removeAt(at); words.removeAtOrNull(at) ?: fail(\"\$name needs a value\") }" to
        "the command line: whether an option is among its words",
    "Main.kt: private fun <T> MutableList<T>.removeAtOrNull(index: Int): T? = if (index < size) removeAt(index) else null" to
        "the command line: an option's value, if one follows it",
    "Office.kt: ACTED -> Datum.Bool(state?.invoke()?.machine(PRESENCE, BORA)?.since?.let { signed.isAfter(instant(it)) } ?: true)" to
        "`bora.acted` as the vocabulary defines it: a signed command came after presence last moved \u2014 two times of the log, no limit",
    "Office.kt: if (left <= 0) break" to
        "a wait for the office's next change: its time is up",
    "Office.kt: last = state.delivered.values.filter { it.signed }.maxOfOrNull { instant(it.at) }" to
        "Bora's presence: the last signed command on the log",
    "Rehearsal.kt: if (left <= 0) error(\"the rehearsal neither waited for Bora nor ended in \${millis}ms\")" to
        "a rehearsal's wait for Bora: its time is up",
    "Rehearsal.kt: if (seeding) seeds.keys.sorted().forEach { seat -> seated.seats.seed(seat)?.let { out(\"seat \$seat did not start: \$it\") } }" to
        "a rehearsal's fake seats (S2.4), started in the order of their names",
    "RuleFiles.kt: private fun Path.children(keep: (Path) -> Boolean): List<Path> = if (!isDirectory()) emptyList() else listDirectoryEntries().filter(keep).sortedBy { it.name }" to
        "the rule files, read in the order of their names",
    "Script.kt: caughtUp = (kept.keys.maxOrNull() ?: -1) + 1" to
        "the walk player: the last step a restarted daemon had kept",
    "Script.kt: fun throughStep(number: Int): List<Played> = buildList { while (next < number) add(step()) }" to
        "the walk player: steps played up to a number",
    "Script.kt: if (at < 0) null else waiting.removeAt(at).second" to
        "the walk player: whether a request waits for its step",
    "Script.kt: if (index >= caughtUp) check(played)" to
        "the walk player: a step a restarted daemon had not kept is checked",
    "Script.kt: if (left <= 0 || office.closed) throw WalkFailure(at(number, step) + \": waited \${WAIT_MILLIS / 1000}s for \$what\")" to
        "the walk player (S2.4): a step's wait for the seats is up",
    "Script.kt: lines().filterIsInstance<kp.model.Fact>().firstOrNull { it.seq.toLong() > before && it.fact in DEATHS && it.subject == seat }" to
        "the walk player (S2.4): a seat's death reported after the step began \u2014 `seq`, not an observation",
    "Script.kt: played.message == null -> message.re == null && message.seq.toLong() in played.first..played.last" to
        "the walk player: the lines a step wrote",
    "Script.kt: played.step.operation != null -> message.re?.value == played.message && message.seq.toLong() in played.first..played.last" to
        "the walk player: the lines a step wrote",
    "Script.kt: played.step.operation != null || played.message == null -> op.seq.toLong() in played.first..played.last && (played.message == null || op.cause == played.message)" to
        "the walk player: the lines a step wrote",
    "Script.kt: return kp.engine.fold(lines.filter { it.seq <= end }).settled()" to
        "the walk player: the log through a step's own judgement",
    "Script.kt: val after = lines.dropWhile { it.seq < anchor.seq }.drop(1)" to
        "the walk player: the lines after a step's record",
    "Script.kt: val done: Boolean get() = next >= walk.steps.size" to
        "the walk player: every step played",
    "Script.kt: val sent = lines.filterIsInstance<Message>().filter { it.seq.toLong() > before && it.from.value == BORA && it.kind == kind }.map { it.id.value }" to
        "the walk player: Bora's messages after a step began",
    "Script.kt: val window = lines.filter { it.seq.toLong() in played.first..played.last }" to
        "the walk player: the lines a step wrote",
    "Script.kt: version = office.changes.await(version, minOf(left, POLL_MILLIS))" to
        "the walk player (S2.4): how long to wait for the next change",
    "Script.kt: walk.stageNames.sortedBy { if (NUMBERED.matches(it)) 0 else 1 }.forEach { name ->" to
        "the walk player: numbered stage names placed first",
    "Script.kt: } ?: return kp.engine.fold(lines.filter { it.seq.toLong() < played.first }).settled()" to
        "the walk player: the log before a step",
    "Script.kt: }.maxOfOrNull { it.key }" to
        "the walk player: the newest open ticket of an item",
    "Seats.kt: if (!closing && !now.isBefore(next)) {" to
        "the watch (S2.9 T5): whether the injected clock has reached the next `watch.every` — when to report, no limit",
    "Seats.kt: log.live.filterNot(held::containsKey).sorted().forEach { seat ->" to
        "the seats at a start (S2.4): those reported gone, in the order of their names",
    "Seats.kt: return folder.listDirectoryEntries(\"*.md\").sortedBy { it.name }.firstOrNull { file ->" to
        "a seat's profile (S2.4): the profiles read in the order of their names",
    "Seats.kt: val list = folder?.takeIf { it.isDirectory() }?.listDirectoryEntries(\"tasks-p\$phase-s\$number-*.md\")?.minByOrNull { it.name }" to
        "a stage's list (S2.4): the first by name",
    "Views.kt: add(\"open tickets: \" + state.openTickets().keys.sorted().joinToString(\", \") { \"#\$it\" }.ifEmpty { \"none\" })" to
        "`kp status`: the open tickets in order",
    "Views.kt: return numbers.sorted().flatMap { number -> tickets[number]?.let { item(number, it, lines) }.orEmpty() }" to
        "`kp inbox`: its items in the order of their tickets",
)

/** Every line of the daemon's source that sets a timer or waits for a time, by file and as written, and why none stands for a limit (S2.9 T3). */
private val NOT_A_LIMIT_TIMER = mapOf(
    "ControlSocket.kt: accepting.join(JOIN_MILLIS)" to
        "the control socket's stop: how long it waits for its accepting thread to end",
    "ControlSocket.kt: private const val JOIN_MILLIS = 5_000L" to
        "the control socket's stop: how long it waits for its accepting thread to end",
    "Daemon.kt: if (wait > 0) Thread.sleep(wait)" to
        "the timers: how long until the next deadline the rules set, or the next look at the rule texts",
    "Daemon.kt: private const val JOIN_MILLIS = 10_000L" to
        "the daemon's stop: how long it waits for its timers' thread to end",
    "Daemon.kt: private val RELOAD_EVERY: Duration = Duration.ofSeconds(5)" to
        "how often the rule texts are compared with those in force (hot reload, architecture.md §3)",
    "Daemon.kt: Thread.sleep(RELOAD_EVERY.toMillis())" to
        "the timers after an error: they rest until the next look at the rule texts",
    "Daemon.kt: timers.join(JOIN_MILLIS)" to
        "the daemon's stop: how long it waits for its timers' thread to end",
    "Daemon.kt: val next = listOfNotNull(office.nextDeadline(), now.plus(RELOAD_EVERY)).min()" to
        "the timers: the earlier of the next deadline the rules set and the next look at the rule texts",
    "Daemon.kt: val wait = Duration.between(now, next).toMillis()" to
        "the timers: how long until the next deadline the rules set, or the next look at the rule texts",
    "Journal.kt: fun say(text: String) = System.err.println(\"\${Instant.now().truncatedTo(ChronoUnit.SECONDS)} kp-daemon: \$text\")" to
        "a time written to the second: no wait",
    "KpCommands.kt: const val FOLLOW_MILLIS = 30_000L" to
        "`kp` following the log: how long it waits for the next change before it looks again",
    "KpCommands.kt: version = office.changes.await(version, FOLLOW_MILLIS)" to
        "`kp` following the log: how long it waits for the next change before it looks again",
    "Main.kt: val clock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }" to
        "a time written to the second: no wait",
    "Office.kt: fun await(seen: Long, millis: Long): Long = synchronized(lock) {" to
        "a wait for the office's next change: as long as its caller asks",
    "Office.kt: IDLE_FOR -> Datum.of(ChronoUnit.SECONDS.between(signed, clock.now()))" to
        "`bora.idle_for` as the vocabulary defines it: the time since his last signed command, reported; the rules compare",
    "Office.kt: val end = System.nanoTime() + millis * 1_000_000" to
        "a wait for the office's next change: as long as its caller asks",
    "Office.kt: val left = (end - System.nanoTime()) / 1_000_000" to
        "a wait for the office's next change: as long as its caller asks",
    "Panes.kt: if (process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) text else null" to
        "a tmux command: how long the panes wait for it to answer",
    "Panes.kt: private const val WAIT_SECONDS = 10L" to
        "a tmux command: how long the panes wait for it to answer",
    "Rehearsal.kt: clock: Clock = Clock { Instant.now().truncatedTo(ChronoUnit.SECONDS) }, fake: FakeSeating? = null, out: (String) -> Unit," to
        "a time written to the second: no wait",
    "Rehearsal.kt: player.join(JOIN_MILLIS)" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: private const val JOIN_MILLIS = 10_000L" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: private const val WAIT_MILLIS = 30_000L" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: private fun waitFor(done: () -> Boolean) {" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: val end = System.nanoTime() + millis * 1_000_000" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: val left = (end - System.nanoTime()) / 1_000_000" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Rehearsal.kt: version = office.changes.await(version, WAIT_MILLIS)" to
        "a rehearsal waiting for Bora, or for its player to end",
    "Script.kt: const val POLL_MILLIS = 1_000L" to
        "the walk player's waits for what a step expects",
    "Script.kt: const val WAIT_MILLIS = 120_000L" to
        "the walk player's waits for what a step expects",
    "Script.kt: if (left <= 0 || office.closed) throw WalkFailure(at(number, step) + \": waited \${WAIT_MILLIS / 1000}s for \$what\")" to
        "the walk player's waits for what a step expects",
    "Script.kt: private fun <T : Any> waitFor(number: Int, step: Walk.Step, what: String, found: () -> T?): T {" to
        "the walk player's waits for what a step expects",
    "Script.kt: val at = clock.now().truncatedTo(ChronoUnit.SECONDS).toString()" to
        "a time written to the second: no wait",
    "Script.kt: val end = System.nanoTime() + WAIT_MILLIS * 1_000_000" to
        "the walk player's waits for what a step expects",
    "Script.kt: val left = (end - System.nanoTime()) / 1_000_000" to
        "the walk player's waits for what a step expects",
    "Script.kt: val result = waitFor(number, step, \"\$operation's result for \$cause\") {" to
        "the walk player's waits for what a step expects",
    "Script.kt: version = office.changes.await(version, minOf(left, POLL_MILLIS))" to
        "the walk player's waits for what a step expects",
    "Script.kt: waitFor(number, step, \"the report that \$seat is gone\") {" to
        "the walk player's waits for what a step expects",
    "Seated.kt: fun watchEvery(): Duration? = office.rules.ruleSet.pool.offices[office.name]?.watchEvery?.let(::duration)?.toJavaDuration()" to
        "the watch's period: `watch.every` of offices.yaml (contracts.md §9), the rules' value",
    "Seats.kt: blocking { driver.kill(SeatId(seat), KILL_GRACE) }" to
        "how `seat.kill` and `seat.retire` are performed (contracts.md §4.4): the agent's input closed, a grace, then its process tree by force — the operation's way, no limit on a seat",
    "Seats.kt: private const val REPORTER_WAIT = 10L" to
        "the seats' stop: how long the reporter is given to hand on what it holds",
    "Seats.kt: private const val SEND_WAIT_SECONDS = 60L" to
        "how long a prompt may take to be sent before a wake or a re-seat answers `failed` — issue #42 (4, 5), Known gaps for P2's retro; no report of a seat's end waits on it",
    "Seats.kt: private fun kill(seat: String): OperationResult = when (val killed = blocking { driver.kill(SeatId(seat), KILL_GRACE) }) {" to
        "how `seat.kill` and `seat.retire` are performed (contracts.md §4.4): the agent's input closed, a grace, then its process tree by force — the operation's way, no limit on a seat",
    "Seats.kt: private val KILL_GRACE: Duration = Duration.ofSeconds(5)" to
        "how `seat.kill` and `seat.retire` are performed (contracts.md §4.4): the agent's input closed, a grace, then its process tree by force — the operation's way, no limit on a seat",
    "Seats.kt: read.get(SEND_WAIT_SECONDS, TimeUnit.SECONDS)" to
        "how long a prompt may take to be sent before a wake or a re-seat answers `failed` — issue #42 (4, 5), Known gaps for P2's retro; no report of a seat's end waits on it",
    "Seats.kt: reporter.awaitTermination(REPORTER_WAIT, TimeUnit.SECONDS)" to
        "the seats' stop: how long the reporter is given to hand on what it holds",
    "Seats.kt: sent.get(SEND_WAIT_SECONDS, TimeUnit.SECONDS)" to
        "how long a prompt may take to be sent before a wake or a re-seat answers `failed` — issue #42 (4, 5), Known gaps for P2's retro; no report of a seat's end waits on it",
    "Seats.kt: \"the prompt was not sent in \${SEND_WAIT_SECONDS}s\"" to
        "how long a prompt may take to be sent before a wake or a re-seat answers `failed` — issue #42 (4, 5), Known gaps for P2's retro; no report of a seat's end waits on it",
    "Seats.kt: val ended = if (session != null && !session.gone) blocking { driver.kill(SeatId(seat), KILL_GRACE) } else Outcome.Ok(Unit)" to
        "how `seat.kill` and `seat.retire` are performed (contracts.md §4.4): the agent's input closed, a grace, then its process tree by force — the operation's way, no limit on a seat",
    "Seats.kt: later.scheduleWithFixedDelay({" to
        "the watch (S2.9 T5): it looks at the injected clock every LOOK_MILLIS; when it reports is `watch.every`'s, on that clock",
    "Seats.kt: }, LOOK_MILLIS, LOOK_MILLIS, TimeUnit.MILLISECONDS)" to
        "the watch (S2.9 T5): how often it looks at the injected clock — a look, no limit on a seat",
    "Seats.kt: next = began.plus(every.multipliedBy(Duration.between(began, now).dividedBy(every) + 1))" to
        "the watch (S2.9 T5): the next `watch.every` of the injected clock since the start, after now",
    "Seats.kt: private const val LOOK_MILLIS = 250L" to
        "the watch (S2.9 T5): how often it looks at the injected clock",
)
