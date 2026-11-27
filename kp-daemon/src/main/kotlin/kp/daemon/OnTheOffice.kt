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
import java.nio.file.Files
import java.nio.file.Path
import kp.daemon.seats.Roots
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

    /**
     * `just rehearse <walk> --world office --check` (S3.5 T7): whether the walk may be played on the office as it is now, read
     * and not changed — the walk's own shape, the five conditions of a start, how far the office's log already went with it,
     * the stage's facts the way's first step reads (its list, the plan's row, the slots) against the caps, the Apps as
     * GitHub at [api] answers for them, and the rules GitHub holds the stage's repository's default branch to. Each is
     * printed through [print], then what is missing; 0 when nothing is.
     */
    fun check(root: Path, walkFile: Path, office: String, project: Path, socket: Path, api: java.net.URI = kp.ops.GitHubApps.GITHUB, print: (String) -> Unit): Int {
        val missing = ArrayList<String>()
        val walk = Walk.read(walkFile)
        print("rehearse ${walk.name} --world office --check: office $office, the project checkout $project — read, nothing changed")
        supplied(walk).forEach { missing += "the walk: $it" }
        if (walk.claudeSeats == null) missing += "the walk names no claude_seats"
        if (!Script.STAGE_ID.matches(walk.stage)) missing += "the walk names its stage ${walk.stage}, not by its whole id"
        val (rules, loaded) = InForceRules.of(RuleFiles(root).read())
        if (rules == null) {
            print("the rules are not valid: ${loaded.problems.joinToString("; ")}")
            return 1
        }
        val stage = walk.stage
        val script = Script(walk, stage.substringBefore('/'), onTheOffice = true)
        val lines = lines(project, office)
        val again = lines.any { it is Message && it.stage?.value == stage }
        print("")
        print("the five conditions of a start:")
        val conditions = missing(rules, office, project, socket, stage, again = again, walk = walk)
        if (conditions.isEmpty()) print("  the service is stopped; $stage has a list; the project checkout and every main checkout are clean; Bora is present")
        conditions.forEach { print("  missing: $it"); missing += it }
        print("")
        print("the office's log:")
        when (val course = course(script, lines)) {
            is Course.Kept -> print(if (course.steps.isEmpty()) "  it holds none of the walk's steps: a start plays the walk from its first" else "  it holds the walk's first ${course.steps.size} steps: a start checks them and plays on from step ${course.steps.size + 1}")
            is Course.Otherwise -> "it went another way than the walk at ${course.why}".let { print("  missing: $it"); missing += it }
        }
        print("")
        print("the stage's facts, as the service reads them now (`just facts $stage`):")
        StageFacts.lines(rules, office, project, stage).filter { line -> WAY.any { line.startsWith(it) } }.forEach { print("  $it") }
        val read = StageFacts.observed(rules, office, project, stage, listOf("list.tasks_total", "list.lines", "plan.predecessors_exited", "list.fence_complete", "stage.rolls", "stage.live"))
        fun value(name: String): Any? = (read[name] as? kp.facts.Observation.Value)?.value
        val caps = rules.ruleSet.engagement.caps.mapValues { (it.value as? kp.rules.Literal.Number)?.value?.toLong() }
        val total = (value("list.tasks_total") as? Number)?.toLong()
        val min = caps["stage_tasks_min"]
        val max = caps["stage_tasks_max"]
        if (total == null || (min != null && total < min) || (max != null && total > max)) missing += "list.tasks_total is ${total ?: "unknown"}: a stage has $min to $max tasks (assignment-proposal, engagement.yaml)"
        val listLines = (value("list.lines") as? Number)?.toLong()
        val linesCap = caps["stage_list_lines"]
        if (listLines == null || (linesCap != null && listLines > linesCap)) missing += "list.lines is ${listLines ?: "unknown"}: the cap is $linesCap (assignment-proposal, engagement.yaml)"
        listOf("plan.predecessors_exited", "list.fence_complete").forEach { name -> if (value(name) != true) missing += "$name is ${value(name) ?: "unknown"}, not true (T-01)" }
        listOf("stage.rolls", "stage.live").forEach { name -> if (value(name) != "none") missing += "$name is ${value(name) ?: "unknown"}: the walk is the way of a stage that rolls nothing and is live nowhere" }
        print("")
        print("the Apps (`just apps`):")
        if (Apps.run(rules, office, api) { print("  $it") } != 0) missing += "the Apps: not every answer is yes (above)"
        print("")
        print("the rules of the stage's repository, as GitHub tells them:")
        rulesOf(rules, office, project, stage, api, { print("  $it") }).forEach { missing += it }
        print("")
        if (missing.isEmpty()) {
            print("ready: nothing is missing — `just rehearse ${walk.name} --seats real --world office` may start")
            return 0
        }
        print("missing (${missing.size}):")
        missing.forEach { print("  - $it") }
        return 1
    }

    /** The facts of `just facts` the way's first step and its exit read: the list, the plan's row, the slots. */
    private val WAY = listOf("plan.stage_has_list", "plan.predecessors_exited", "list.fence_complete", "list.tasks_total", "list.tasks_open", "list.lines",
        "list.found_untriaged", "stage.rolls", "stage.live", "status.entries", "project.proof_files", "git.stray_changes", "budget.slot_open", "roster.floor_rank")

    /**
     * The rules GitHub holds the default branch of [stage]'s repository to — its list's title names it — as `kp-merger` and
     * the coder's App read them (`just apps merge` reads them so too): each ruleset and who may bypass it, printed. What is
     * missing: no rule holds the coder's App to `update` — it could push to the default branch — or none holds `kp-merger`
     * to green checks, the toy's second ruleset (D-123).
     */
    private fun rulesOf(rules: InForceRules, office: String, project: Path, stage: String, api: java.net.URI, print: (String) -> Unit): List<String> {
        val pool = rules.ruleSet.pool
        val block = pool.github ?: return listOf("pool/offices.yaml has no block github:")
        val entry = pool.offices[office] ?: return listOf("pool/offices.yaml names no office '$office'")
        val (effort, phase, number) = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\\.[0-9]+)$").matchEntire(stage)?.destructured ?: return listOf("$stage is no stage id")
        val folder = entry.project.efforts[effort]?.let(project::resolve) ?: return listOf("pool/offices.yaml gives the effort $effort no folder")
        val list = folder.takeIf { it.isDirectory() }?.let { dir -> Files.list(dir).use { all -> all.filter { it.fileName.toString().startsWith("tasks-p$phase-s$number-") && it.fileName.toString().endsWith(".md") }.toList() } }?.singleOrNull()
            ?: return listOf("$stage has no one list in $folder")
        val repo = Regex("^# ${Regex.escape(effort)}-(\\S+)-P$phase(?=\\s|$)").find(Files.readAllLines(list).firstOrNull().orEmpty())?.groupValues?.get(1)
            ?: return listOf("${list.fileName}:1 names no repository")
        val repository = "${block.organization}/$repo"
        val apps = kp.ops.GitHubApps(Roots.of(entry.roots).secrets.resolve("apps"), api)
        val rest = kp.ops.GitHubRest(api)
        val merger = block.apps[GitHubOperations.MERGER]?.let { kp.ops.App(GitHubOperations.MERGER, it.app, it.installation) } ?: return listOf("github.apps holds no App ${GitHubOperations.MERGER}")
        val coderSeat = block.seats[CODER] ?: return listOf("github.seats gives the coder no App")
        val coder = block.apps[coderSeat.app]?.let { kp.ops.App(coderSeat.app, it.app, it.installation) } ?: return listOf("github.apps holds no App ${coderSeat.app}")
        fun token(app: kp.ops.App, permissions: Map<String, String>): kp.ops.Token? = when (val minted = apps.mint(app, listOf(repo), permissions)) {
            is kp.ops.Minted.Ok -> minted.token
            is kp.ops.Minted.Failed -> null.also { print("${app.identity}: no token for $repository — ${minted.reason}") }
        }
        val reading = token(merger, kp.ops.GitHubApps.METADATA) ?: return listOf("no token of ${merger.identity} reads $repository")
        // Read only: whether an App may bypass a ruleset is GitHub's answer for the App, whatever its token may do.
        val pushing = token(coder, kp.ops.GitHubApps.METADATA) ?: return listOf("no token of ${coder.identity} reads $repository")
        fun ask(path: String, token: kp.ops.Token): kotlinx.serialization.json.JsonElement? = when (val sent = rest.send("GET", path, token)) {
            is kp.ops.GitHubRest.Sent.Answered -> sent.reply.let { reply -> if (reply.status == kp.ops.GitHubRest.OK) reply.json else null.also { print(rest.refused("GET", path, reply)) } }
            is kp.ops.GitHubRest.Sent.NotReached -> null.also { print("GET $path: ${sent.reason}") }
        }
        val default = ((ask("/repos/$repository", reading) as? kotlinx.serialization.json.JsonObject)?.get("default_branch") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return listOf("GitHub names no default branch of $repository")
        val held = ask("/repos/$repository/rules/branches/$default", reading) as? kotlinx.serialization.json.JsonArray ?: return listOf("GitHub's rules of $repository's $default are not readable")
        val byRuleset = held.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .groupBy({ (it["ruleset_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content }, { (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content })
        var coderHeld = false
        var mergerHeld = false
        print("$repository's default branch $default, as its rulesets hold it:")
        for ((id, types) in byRuleset) {
            fun bypass(token: kp.ops.Token) = ((ask("/repos/$repository/rulesets/$id", token) as? kotlinx.serialization.json.JsonObject)?.get(BYPASS) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val asMerger = ask("/repos/$repository/rulesets/$id", reading) as? kotlinx.serialization.json.JsonObject
            val name = (asMerger?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val mergerBypass = (asMerger?.get(BYPASS) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val coderBypass = bypass(pushing)
            print("ruleset $id '$name' holds $default to ${types.filterNotNull().joinToString(", ")} — ${merger.identity} may bypass it: $mergerBypass; ${coder.identity}: $coderBypass")
            if ("update" in types && coderBypass == NEVER) coderHeld = true
            if ("required_status_checks" in types && mergerBypass == NEVER) mergerHeld = true
        }
        return listOfNotNull(
            "no rule of $repository's $default holds ${coder.identity} to update: its push to $default would not be refused".takeUnless { coderHeld },
            "no rule of $repository's $default holds ${merger.identity} to green checks: its merge would not wait for them (the toy's second ruleset, D-123)".takeUnless { mergerHeld },
        )
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
    private const val CODER = "coder"
    private const val BYPASS = "current_user_can_bypass"
    private const val NEVER = "never"
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
