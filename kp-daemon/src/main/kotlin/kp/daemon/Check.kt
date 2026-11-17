package kp.daemon

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kp.engine.Datum
import kp.engine.Decision
import kp.engine.Engine
import kp.engine.FactRef
import kp.engine.Facts
import kp.engine.Move
import kp.engine.Outcome
import kp.engine.State
import kp.engine.Trigger
import kp.engine.UNKNOWN_FACT
import kp.engine.fold
import kp.log.LogLine
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Held
import kp.model.Message
import kp.model.Record
import kp.model.Refused
import kp.model.Rules
import kp.model.Transition
import kp.model.parseLine
import kp.model.plain
import kp.model.readEnvelope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.walk

/**
 * `kp check <log…>` (contracts.md §6, Amendment 5): re-judges log files under the rules in force and prints every
 * difference. A daemon's log — its files merged in `seq` order — has each message fed back through the engine with the
 * facts its record says were observed, on the state folded from the recorded lines before it; where the outcome differs,
 * the message's line is printed with both. A `rules` record naming another rule set is announced first. A hand log
 * (`by: hand:…`) has no observed fact: it is judged for what needs none — the sender's role, the `args`, the addressee
 * against the kind's declaration, the caps and windows, the state the stage is in — and what it could not check is
 * listed; the check says in which state it leaves each stage. A repeated id is reported in either. The check reads the
 * files and writes nothing.
 */
internal class Check(private val rules: InForceRules) {
    private val engine = Engine(rules.ruleSet)
    private val out = ArrayList<String>()
    private var findings = 0

    /** What to print, and the code: 0 when every outcome is reproduced and nothing is wrong, 1 otherwise. */
    fun run(paths: List<Path>): Pair<List<String>, Int> {
        out += "rules in force: ${rules.hash.take(HASH)}"
        val files = paths.flatMap { path ->
            when {
                path.isDirectory() -> path.walk().filter { it.isRegularFile() && it.name.endsWith(".jsonl") }.sorted().toList()
                path.isRegularFile() -> listOf(path)
                else -> emptyList<Path>().also { finding("$path: no such file or folder") }
            }
        }.distinct()
        val read = files.associateWith(::read)
        repeatedIds(read.values.flatten())
        val (hand, daemon) = read.entries.partition { (_, lines) -> lines.isNotEmpty() && lines.all { (it.entry as? Message)?.by?.startsWith(HAND) == true } }
        if (daemon.any { it.value.isNotEmpty() }) daemonLog(daemon.flatMap { it.value }.sortedBy { it.entry.seq })
        hand.forEach { (file, lines) -> handLog(file, lines.sortedBy { it.entry.seq }) }
        if (files.isEmpty()) finding("no log file to check")
        return out to (if (findings == 0) 0 else 1)
    }

    private fun finding(line: String) {
        findings++
        out += line
    }

    /**
     * The file's lines that read as messages or records. What does not is reported by its place and why — never by its
     * text: a file named by mistake (a key, a token) is not printed back.
     */
    private fun read(file: Path): List<LogLine> {
        val texts = try {
            file.readLines()
        } catch (error: java.io.IOException) {
            return emptyList<LogLine>().also { finding("$file: cannot be read as UTF-8 text (${error.javaClass.simpleName})") }
        }
        return texts.mapIndexedNotNull { index, text ->
            runCatching { LogLine(file, index + 1, parseLine(text)) }.getOrElse { error ->
                // The JSON reader's own message quotes its input; kp-model's names what is missing.
                val why = (error.message ?: error.javaClass.simpleName).takeUnless { "JSON input" in it || (text.isNotBlank() && text.trim() in it) } ?: "not JSON"
                finding("$file:${index + 1}: not a message or record of contracts.md §2 ($why)")
                null
            }
        }
    }

    /** A message id used by more than one line (contracts.md §6: "reports a repeated id"). */
    private fun repeatedIds(lines: List<LogLine>) {
        val first = HashMap<String, LogLine>()
        lines.forEach { line ->
            val id = (line.entry as? Message)?.id?.value ?: return@forEach
            val earlier = first.putIfAbsent(id, line) ?: return@forEach
            val where = if (earlier.file == line.file) "line ${earlier.number}" else "${earlier.file}:${earlier.number}"
            finding("${line.file}:${line.number} $id: the id is used again — first on $where")
        }
    }

    // ---- a daemon's log

    private fun daemonLog(lines: List<LogLine>) {
        lines.mapNotNull { it.entry as? Rules }.map { it.hash }.distinct().filter { it != rules.hash }.forEach { hash ->
            out += "the log was written under rules ${hash.take(HASH)}, not those in force: a difference may come from that"
        }
        var state = State()
        var judged = 0
        var differ = 0
        val notJudged = ArrayList<String>()
        lines.forEachIndexed { index, line ->
            val message = line.entry as? Message
            if (message != null && !message.by.startsWith(HAND)) {
                val record = lines.drop(index + 1).firstOrNull { (it.entry as? Record)?.let(::about) == message.id.value }?.entry
                when (record) {
                    null -> notJudged += "${line.file}:${line.number} ${message.id.value}: no record says what happened to it"
                    is Held -> notJudged += "${line.file}:${line.number} ${message.id.value}: held by ${record.by} — a robot's hold is not re-judged"
                    else -> {
                        judged++
                        val recorded = recorded(record, lines.drop(lines.indexOfFirst { it.entry === record } + 1))
                        val rejudged = rejudged(judge(state, message, (record.json[OBSERVED] as? JsonObject)))
                        if (recorded != rejudged) {
                            differ++
                            finding("${line.file}:${line.number} ${message.id.value} ${message.kind}: recorded $recorded; re-judged $rejudged")
                        }
                    }
                }
            } else if (message != null) {
                finding("${line.file}:${line.number} ${message.id.value}: a hand-recorded line among the daemon's")
            }
            state = fold(listOf(line.entry), state)
        }
        out += "$judged message${if (judged == 1) "" else "s"} re-judged: " + if (differ == 0) "every recorded outcome is reproduced" else "$differ differ${if (differ == 1) "s" else ""}"
        notJudged.forEach { out += "not re-judged: $it" }
        out += "not re-judged: the results of operations and the expiries — their records carry no observed facts"
    }

    /** The message fed back through the engine with the facts its record observed; a fact it did not observe is unknown. */
    private fun judge(state: State, message: Message, observed: JsonObject?): Decision {
        val trigger = Trigger.Arrival(message)
        val recorded = observed.orEmpty().mapValues { Datum.of(plain(it.value.toString())) }.filterValues { it != UNKNOWN_FACT }
        return engine.judge(state, trigger, Facts.collect(engine.needs(state, trigger)) { ref: FactRef -> recorded[ref.toString()] })
    }

    /** What the record and the transitions written right after it say happened to the message. */
    private fun recorded(record: Envelope, after: List<LogLine>): String = when (record) {
        is Refused -> refusal(record.rule, record.requirement, record.failed?.let { Datum.of(plain(it.toString())) })
        is Delivered -> {
            val moves = after.map { it.entry }.takeWhile { it is Transition && it.cause == record.id }.map { it as Transition }
                .map { move(it.machine, it.subject, it.from, it.to, it.rule, it.counted.orEmpty().mapValues { (_, value) -> value.toLong() }) }
            delivery(record.rule, moves, record.deliveryClass, record.ticket != null, record.deadline?.value, record.answers, record.counted.orEmpty().mapValues { it.value.toLong() })
        }
        else -> "${(record as Record).event}"
    }

    private fun rejudged(decision: Decision): String = when (decision.outcome) {
        Outcome.REFUSE -> refusal(decision.rule, decision.requirement, decision.failed)
        Outcome.DELIVER -> delivery(decision.rule, decision.moves.map(::move), decision.messageClass?.name, decision.waitsForBora, decision.deadline, decision.answers, decision.counted)
        else -> decision.outcome.name.lowercase()
    }

    private fun refusal(rule: String?, requirement: String?, failed: Datum?): String =
        "refused $rule: $requirement" + (failed?.let { " · failed ${it.json()}" } ?: "")

    private fun delivery(rule: String?, moves: List<String>, messageClass: String?, ticket: Boolean, deadline: String?, answers: String?, counted: Map<String, Long>): String =
        listOfNotNull("delivered" + (rule?.let { " $it" } ?: ""), moves.takeIf { it.isNotEmpty() }?.joinToString(", "), "class $messageClass",
            "ticket".takeIf { ticket }, deadline?.let { "deadline $it" }, answers?.let { "answers $it" },
            counted.takeIf { it.isNotEmpty() }?.toSortedMap()?.entries?.joinToString(" ", "counted ") { "${it.key}=${it.value}" }).joinToString(" · ")

    private fun move(move: Move) = move(move.machine, move.subject, move.from, move.to, move.rule, move.counted)

    private fun move(machine: String, subject: String, from: String, to: String, rule: String?, counted: Map<String, Long>) =
        "$machine $subject $from→$to" + (rule?.let { " by $it" } ?: "") + counted.toSortedMap().entries.joinToString("") { " ${it.key}=${it.value}" }

    /** The message a `delivered`, `refused` or `held` record is about. */
    private fun about(record: Record): String? = when (record) {
        is Delivered -> record.id
        is Refused -> record.id
        is Held -> record.id
        else -> null
    }

    // ---- a hand log

    /**
     * A hand log, on its own: each message judged with no fact but the time it was recorded at. What the engine cannot
     * decide without an observed fact — a requirement that reads one, or a signature, which a hand-carried line does not
     * have — is not checked, and moves nothing. What it decides is applied as the mailroom would record it, so the next
     * line is judged in the state the last one left.
     */
    private fun handLog(file: Path, lines: List<LogLine>) {
        var state = State()
        var seq = 0L
        val unread = sortedSetOf<String>()
        var unsigned = false
        val stages = LinkedHashSet<String>()
        fun take(entry: JsonObject) {
            state = fold(listOf(readEnvelope(JsonObject(entry + (SEQ to JsonPrimitive(++seq))))), state)
        }
        lines.forEach { line ->
            val message = line.entry as? Message ?: return@forEach
            message.stage?.value?.let(stages::add)
            val renumbered = readEnvelope(JsonObject(message.json + (SEQ to JsonPrimitive(seq + 1)))) as Message
            val trigger = Trigger.Arrival(renumbered)
            val decision = engine.judge(state, trigger, Facts.collect(engine.needs(state, trigger)) { ref -> if (ref.name == NOW) Datum.Text(message.at.value) else null })
            val where = "${line.file}:${line.number} ${message.id.value} ${message.kind}"
            take(renumbered.json)
            when (decision.outcome) {
                Outcome.DELIVER -> {
                    out += "$where: delivered" + (decision.rule?.let { " $it" } ?: "") + decision.moves.joinToString("") { " · ${it.machine} ${it.subject} ${it.from}→${it.to}" }
                    take(record(message, "delivered", TO to JsonPrimitive(message.to.value).let { kotlinx.serialization.json.JsonArray(listOf(it)) },
                        CLASS to JsonPrimitive(decision.messageClass?.name ?: "A"), RULE to decision.rule?.let(::JsonPrimitive),
                        ANSWERS to decision.answers?.let(::JsonPrimitive), DEADLINE to decision.deadline?.let(::JsonPrimitive)))
                    decision.moves.forEach { move ->
                        take(record(message, "transition", MACHINE to JsonPrimitive(move.machine), SUBJECT to JsonPrimitive(move.subject), FROM to JsonPrimitive(move.from),
                            TO to JsonPrimitive(move.to), RULE to JsonPrimitive(move.rule), CAUSE to JsonPrimitive(message.id.value),
                            COUNTED to move.counted.takeIf { it.isNotEmpty() }?.let { counted -> JsonObject(counted.mapValues { JsonPrimitive(it.value) }) }))
                    }
                }
                Outcome.REFUSE -> {
                    val failed = decision.failed as? Datum.Fields
                    val facts = failed?.let(::factsIn).orEmpty().ifEmpty { if (failed?.fields?.containsKey(HOLDS) == true) decision.unsupplied.map { it.name } else emptyList() }
                    when {
                        failed?.fields?.containsKey(SIGNED) == true -> {
                            unsigned = true
                            out += "$where: not checked — it needs a signature, which a hand-carried line does not have"
                        }
                        facts.any { name -> decision.unsupplied.any { it.name == name } } -> {
                            unread += facts
                            out += "$where: not checked — it reads ${facts.distinct().joinToString(", ")}, which a hand log does not record" +
                                (message.stage?.value?.let { "; $it is ${stageOf(state, it)}" } ?: "")
                        }
                        else -> {
                            val position = message.stage?.value?.takeIf { rules.ruleSet.kinds[message.kind]?.fires == decision.requirement }?.let { " — $it is ${stageOf(state, it)}" } ?: ""
                            finding("$where: refused — ${decision.requirement}$position")
                            take(record(message, "refused", RULE to JsonPrimitive(decision.rule ?: message.kind), REQUIREMENT to JsonPrimitive(decision.requirement ?: "")))
                        }
                    }
                }
                else -> out += "$where: ${decision.outcome.name.lowercase()}"
            }
        }
        stages.forEach { stage -> out += "$stage is left in ${stageOf(state, stage)}" }
        if (unread.isNotEmpty()) out += "not checked, a hand log records no observed fact: ${unread.joinToString(", ")}"
        if (unsigned) out += "not checked: signatures — a hand-carried line has none"
    }

    private fun stageOf(state: State, stage: String): String = state.stage(stage) ?: rules.ruleSet.lifecycle.stage.states.keys.first()

    /** The facts a requirement in its file form reads: every `fact:` in it, at any depth. */
    private fun factsIn(datum: Datum): List<String> = when (datum) {
        is Datum.Fields -> datum.fields.flatMap { (key, value) -> if (key == FACT && value is Datum.Text) listOf(value.value) else factsIn(value) }
        is Datum.Items -> datum.items.flatMap(::factsIn)
        else -> emptyList()
    }

    /** A record about [message], as the mailroom would write it, with its `seq` still to be given. */
    private fun record(message: Message, event: String, vararg fields: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
        put("v", JsonPrimitive(1))
        put("at", JsonPrimitive(message.at.value))
        put("event", JsonPrimitive(event))
        if (event != "transition") put("id", JsonPrimitive(message.id.value))
        fields.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    private companion object {
        const val HASH = 12
        const val HAND = "hand:"
        const val NOW = "now"
        const val OBSERVED = "observed"
        const val SEQ = "seq"
        const val FACT = "fact"
        const val HOLDS = "holds"
        const val SIGNED = "signed"
        const val TO = "to"
        const val CLASS = "class"
        const val RULE = "rule"
        const val ANSWERS = "answers"
        const val DEADLINE = "deadline"
        const val MACHINE = "machine"
        const val SUBJECT = "subject"
        const val FROM = "from"
        const val CAUSE = "cause"
        const val COUNTED = "counted"
        const val REQUIREMENT = "requirement"
    }
}
