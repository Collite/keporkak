package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.log.LogReader
import kp.log.counted
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Fact
import kp.model.Held
import kp.model.Message
import kp.model.OfficeId
import kp.model.Op
import kp.model.Refused
import kp.ops.GitCommand
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A rehearsal on the office (contracts.md §12, Amendment 46; S3.5): what is read before one starts — what a walk would
 * supply of the world, which it may not; the five conditions of a start; and how far the office's log already went with
 * the walk. Each reads and changes nothing.
 */
object OnTheOffice {
    /** A refusal to start: nothing was started, and [message] says why. */
    class Refusal(message: String) : RuntimeException(message)

    /**
     * What [walk] gives of the world, each named — a fact of its table, a stage's or a step's, an operation's result, a fact
     * it reports other than a seat's death — none of which a rehearsal on the office takes: every fact is read and every
     * operation performed as the service does. Empty when it gives none.
     */
    fun supplied(walk: Walk): List<String> = buildList {
        walk.facts.keys.forEach { add("its table gives $it") }
        walk.stages.forEach { (name, setting) -> setting.facts.keys.forEach { add("its stage $name gives $it") } }
        walk.steps.forEachIndexed { index, step ->
            val at = "step ${index + 1}" + (step.id?.let { " ($it)" } ?: "")
            step.facts.keys.forEach { add("$at gives $it") }
            if (step.operation != null && step.fields.containsKey(RESULT)) add("$at gives ${step.operation}'s result")
            val reported = (step.fields(EVENT)[FACT] as? Datum.Text)?.value
            if (step.fields.containsKey(EVENT) && reported !in Script.DEATHS_OF_A_SEAT) add("$at reports ${reported ?: "an event"}")
        }
    }

    /**
     * The five conditions of a start (Amendment 46), read and not changed: the service is stopped — nothing answers on
     * [socket] and no writer holds the log — the walk's stage [stage] has a list, the project checkout [project] is clean,
     * every main checkout of the office's `repos:` is clean, and Bora is present in the office's log. What is missing,
     * each said; empty when the rehearsal may start.
     *
     * The office's log, `mail/`, is the daemon's own and committed by `project.commit_push` at a stage's exit: a change
     * in it does not make the project checkout unclean. Started [again] for a stage the log already holds, neither does
     * what the run itself changed there before its `project.commit_push`: that stage's list — the coder's ticks — a file
     * [walk] `writes:`, and `REGISTER.md`, which `register.rebuild` writes.
     */
    fun missing(rules: InForceRules, office: String, project: Path, socket: Path, stage: String, again: Boolean = false, walk: Walk? = null): List<String> = buildList {
        if (answers(socket)) add("the service is running: a daemon answers on $socket — `just stop` first")
        if (writerHeld(project)) add("the service is running: a writer holds the log ${project.resolve(MAIL)} — `just stop` first")
        when (val list = ProjectWorld.of(rules, office, project) {}.observation(FactRef(STAGE_HAS_LIST, mapOf(STAGE to Datum.Text(stage))), stage)?.second) {
            is kp.facts.Observation.Value -> if (list.value != true) add("$stage has no list in $project: ${list.evidence}")
            is kp.facts.Observation.Unknown -> add("whether $stage has a list is unknown: ${list.missing}")
            null -> add("whether $stage has a list is unknown: the project's facts do not answer $STAGE_HAS_LIST")
        }
        val written = if (!again) emptySet() else walk?.steps?.mapNotNull { (it.fields(WRITES)[FILE] as? Datum.Text)?.value }.orEmpty().toSet() + REGISTER
        changes(project) { path -> path.startsWith("$MAIL/") || path in written || (again && isListOf(path, stage)) }?.let { add("the project checkout $project is not clean: $it") }
        val entry = rules.ruleSet.pool.offices[office]
        if (entry == null) add("pool/offices.yaml names no office '$office'")
        entry?.repos.orEmpty().toSortedMap().forEach { (name, path) ->
            val checkout = home(path)
            if (!checkout.isDirectory()) add("the main checkout of $name, $checkout, does not exist") else changes(checkout)?.let { add("the main checkout of $name, $checkout, is not clean: $it") }
        }
        val presence = presence(project, office)
        if (presence != PRESENT) add("Bora is $presence in the office's log: run any `sudo kp …` on the running service, then `just stop`")
    }

    /** Bora's presence as the office's log has it: present until it moves (contracts.md §4.1, the presence machine's first state). */
    fun presence(project: Path, office: String): String {
        if (!project.resolve(MAIL).isDirectory()) return PRESENT
        val lines = LogReader(project, OfficeId(office)).read().lines.counted().map { it.entry }
        return kp.engine.fold(lines).presence(BORA) ?: PRESENT
    }

    /** `~` as the office user's home (contracts.md §9). */
    fun home(path: String): Path = Path.of(path.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))

    /** Whether a daemon answers on [socket]. */
    private fun answers(socket: Path): Boolean {
        if (!socket.exists()) return false
        return runCatching { SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) } }.isSuccess
    }

    /** Whether a writer holds [project]'s log (contracts.md §3: `mail/.writer.lock`). */
    private fun writerHeld(project: Path): Boolean {
        val lock = project.resolve(MAIL).resolve(".writer.lock")
        if (!lock.exists()) return false
        return FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
            try {
                channel.tryLock()?.let { it.release(); false } ?: true
            } catch (_: OverlappingFileLockException) {
                true
            }
        }
    }

    /** What `git status` says changed in [checkout] but the paths [ignored] lets pass, shortened; null when nothing did. */
    private fun changes(checkout: Path, ignored: (String) -> Boolean = { false }): String? {
        val ran = GitCommand().run(checkout, "status", "--porcelain=v1", "--untracked-files=all")
        if (!ran.ok) return "git status failed: ${ran.output.trim()}"
        val changed = ran.output.lines().filter { it.isNotBlank() }.filterNot { ignored(it.drop(STATUS_CODE).substringAfter(" -> ").trim('"')) }
        if (changed.isEmpty()) return null
        return changed.joinToString(", ", limit = SHOWN, truncated = "…") { it.trim() }
    }

    /**
     * How far the office's log already went with [script]'s walk (Amendment 46; S3.5 T4): each step whose own line is on
     * the log — a message the walk sends, Bora's, a seat's own, a robot's or the mailroom's it looks for; an operation's
     * result; a seat's death — from the first step on, up to the first it does not hold. A log that went another way than
     * the walk — a message of the stage from the walk's script, from Bora or from one of its Claude seats that is not the
     * step's, or a held step refused when the walk expects it delivered — is [Course.Otherwise], naming the step.
     */
    fun course(script: Script, lines: List<Envelope>): Course {
        val walk = script.walk
        val stage = script.stageId(walk.stage)
        val messages = lines.filterIsInstance<Message>()
        val stages = messages.filter { it.stage?.value == stage }
        val claude = walk.claudeSeats?.seats.orEmpty()
        val used = HashSet<String>()
        val ids = HashMap<String, String>()
        val kept = LinkedHashMap<Int, Pair<String?, String?>>()
        // Where the last message a step held stands on the log: a death or a robot's message a later step holds comes after it.
        var from = 0
        for ((index, step) in walk.steps.withIndex()) {
            val number = index + 1
            val at = "step $number (" + listOfNotNull(step.id, step.who ?: step.text(BY), step.kind ?: step.operation ?: (step.fields(EVENT)[FACT] as? Datum.Text)?.value).joinToString(" · ") + ")"
            val who = step.who
            val line: Pair<String?, String?>? = when {
                step.operation != null -> {
                    val cause = step.text(REQUEST)?.let { ids[it] }
                    lines.filterIsInstance<Op>().firstOrNull { it.op == step.operation && cause != null && it.cause == cause && it.phase != REQUESTED }?.let { cause to it.phase }
                }
                step.fields.containsKey(EVENT) -> {
                    val seat = (step.fields(TRIGGER)[SEAT] as? Datum.Text)?.value
                    lines.subList(from, lines.size).filterIsInstance<Fact>().firstOrNull { it.fact in Script.DEATHS_OF_A_SEAT && it.subject == seat }?.let { null to null }
                }
                who == MAILROOM || step.text(BY)?.startsWith(ROBOT) == true -> {
                    // A robot's message by its kind and the args the step names — the rule that sent it may be the step's
                    // twin on a seat's other end (S2.9 T3) — or the mailroom's, after the line the step before it held.
                    val by = step.text(BY)
                    val re = step.text(RE)?.let { ids[it] ?: it }
                    val named = step.fields(ARGS)
                    lines.subList(from, lines.size).filterIsInstance<Message>().firstOrNull { m ->
                        m.id.value !in used && m.kind == step.kind && (if (by != null) m.by.startsWith(ROBOT) else m.from.value == MAILROOM) &&
                            (re == null || m.re?.value == re) && (step.text(TO)?.let { to -> m.to.value == to } ?: true) &&
                            named.all { (key, value) -> (m.json[ARGS] as? kotlinx.serialization.json.JsonObject)?.get(key) == kotlinx.serialization.json.Json.parseToJsonElement(value.json()) }
                    }?.let { it.id.value to outcome(lines, it.id.value) }
                }
                who != null && step.kind != null -> {
                    val by = when {
                        who == BORA -> BORA_CLI
                        who in claude -> "seat:$who"
                        else -> "$SCRIPT${walk.name}"
                    }
                    // Bora's refused tries — an unsigned `kp approve` — are his to send again: his step is the first that went through.
                    val next = stages.firstOrNull { it.id.value !in used && it.by == by && (who == BORA || it.from.value == who) && (who != BORA || outcome(lines, it.id.value) != REFUSED_OUTCOME) }
                    when {
                        next == null -> null
                        next.kind != step.kind || next.from.value != who ->
                            return Course.Otherwise(number, "$at: the log holds ${next.kind} ${next.id.value} from ${next.from.value} (${next.by}) where the walk has ${step.kind} from $who")
                        by.startsWith(SCRIPT) && !sameArgs(next, step, script) ->
                            return Course.Otherwise(number, "$at: the log holds ${next.kind} ${next.id.value} with the args ${next.json["args"] ?: "{}"} where the walk sends ${step.fields["args"]?.json() ?: "{}"}")
                        else -> {
                            val outcome = outcome(lines, next.id.value)
                            if (outcome == REFUSED_OUTCOME && step.expect[REFUSED] != Datum.Bool(true))
                                return Course.Otherwise(number, "$at: the log holds ${next.kind} ${next.id.value} refused where the walk expects it delivered")
                            next.id.value to outcome
                        }
                    }
                }
                else -> null to null
            }
            if (line == null) {
                // What the walk still sends must not be on the log already: a later step's message there is a log that went another way.
                val sent = stages.filter { it.id.value !in used && it.by == "$SCRIPT${walk.name}" }
                sent.firstOrNull()?.let { extra -> return Course.Otherwise(number, "$at is not on the log, yet the log holds the walk's later ${extra.kind} ${extra.id.value} from ${extra.from.value}") }
                return Course.Kept(kept)
            }
            line.first?.let { id ->
                if (step.operation == null) used += id
                step.id?.let { ids[it] = id }
                lines.withIndex().firstOrNull { (_, line) -> line is Message && line.id.value == id }?.let { from = it.index + 1 }
            }
            kept[index] = line
        }
        return Course.Kept(kept)
    }

    /** Whether [message] carries the args [step] sends — a stage the walk names read as the stage id it is played as. */
    private fun sameArgs(message: Message, step: Walk.Step, script: Script): Boolean {
        fun played(value: Datum): Datum = when (value) {
            is Datum.Text -> script.stages[value.value]?.let(Datum::Text) ?: value
            is Datum.Items -> Datum.Items(value.items.map(::played))
            is Datum.Fields -> Datum.Fields(value.fields.mapValues { played(it.value) })
            else -> value
        }
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(played(Datum.Fields(step.fields(ARGS))).json())
        return (message.json["args"] ?: kotlinx.serialization.json.JsonObject(emptyMap())) == sent
    }

    /** How far the office's log went with a walk: the steps it holds, by index — or where it went another way. */
    sealed interface Course {
        class Kept(val steps: Map<Int, Pair<String?, String?>>) : Course
        class Otherwise(val step: Int, val why: String) : Course
    }

    /** The log lines of [project]'s log as they count. */
    fun lines(project: Path, office: String): List<Envelope> =
        if (!project.resolve(MAIL).isDirectory()) emptyList() else LogReader(project, OfficeId(office)).read().lines.counted().map { it.entry }

    private fun outcome(lines: List<Envelope>, id: String): String = when (lines.lastOrNull { (it is Delivered && it.id == id) || (it is Held && it.id == id) || (it is Refused && it.id == id) }) {
        is Delivered -> "delivered"
        is Held -> "held"
        is Refused -> REFUSED_OUTCOME
        else -> "written"
    }

    private const val MAIL = "mail"
    private const val STAGE = "stage"
    private const val STAGE_HAS_LIST = "plan.stage_has_list"
    private const val BORA = "bora"
    private const val BORA_CLI = "bora-cli"
    private const val PRESENT = "present"
    private const val RESULT = "result"
    private const val EVENT = "event"
    private const val FACT = "fact"
    private const val TRIGGER = "trigger"
    private const val SEAT = "seat"
    private const val REQUEST = "request"
    private const val REQUESTED = "requested"
    private const val MAILROOM = "mailroom"
    private const val ROBOT = "robot:"
    private const val BY = "by"
    private const val RE = "re"
    private const val TO = "to"
    private const val REFUSED = "refused"
    private const val SCRIPT = "script:"
    private const val ARGS = "args"
    private const val REFUSED_OUTCOME = "refused"
    private const val SHOWN = 5
    private const val REGISTER = "REGISTER.md"
    private const val WRITES = "writes"
    private const val FILE = "file"
    /** The two letters and the space before a path in `git status --porcelain=v1`. */
    private const val STATUS_CODE = 3

    /** Whether [path] — relative to the project checkout — is [stage]'s list: `<effort folder>/tasks-p<n>-s<n.m>-*.md`. */
    private fun isListOf(path: String, stage: String): Boolean {
        val (phase, number) = Regex("^[A-Z][A-Z0-9-]*/P([0-9]+)/S([0-9]+\\.[0-9]+)$").matchEntire(stage)?.destructured ?: return false
        return Regex("(^|/)tasks-p$phase-s${Regex.escape(number)}-[^/]*\\.md$").containsMatchIn(path)
    }
}
