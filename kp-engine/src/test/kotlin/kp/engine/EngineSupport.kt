package kp.engine

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import kp.model.Message
import kp.model.parseLine
import kp.model.plain
import kp.rules.Loaded
import kp.rules.RuleSet
import kp.rules.Rules
import java.io.File

val root = File(System.getProperty("kp.root"))

/** The texts the daemon hands to `Rules.load`: the four rules files, the robots, the pool files and the personas' modes. */
fun filesOnMaster(): Map<String, String> {
    val rules = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary").map { File(root, "rules/$it.yaml") }
    val robots = File(root, "robots").listFiles { file -> file.name.endsWith(".yaml") }!!.sorted()
    val pool = listOf("roster", "offices", "budgets").map { File(root, "pool/$it.yaml") }
    val personas = File(root, "agents").listFiles()!!.map { File(it, "AGENT.md") }.filter { it.isFile }.sorted()
    return (rules + robots + pool + personas).associate { it.relativeTo(root).path to it.readText() }
}

/** An edit to one rule file: its first [from] becomes [to]. */
data class Edit(val file: String, val from: String, val to: String)

/** Master's rule set, or — with [edits] — a fixture rule set made from it. */
fun ruleSet(vararg edits: Edit): RuleSet {
    val files = filesOnMaster().toMutableMap()
    edits.forEach { (file, from, to) ->
        val text = files.getValue(file)
        check(from in text) { "'$from' is not in $file" }
        files[file] = text.replaceFirst(from, to)
    }
    return when (val loaded = Rules.load(files)) {
        is Loaded.Valid -> loaded.ruleSet
        is Loaded.Invalid -> error(loaded.problems.joinToString("\n"))
    }
}

val master: RuleSet by lazy { ruleSet() }

/** The engine on [rules] — under the profile they name: on master v1's, `supervised`. */
fun supervised(rules: RuleSet = master) = Engine(rules)

const val STAGE = "KP/P1/S1.2"
const val NOW = "2026-11-15T10:00:00Z"

/** A message as the mailroom hands it to the engine: its id, thread and times already set (contracts.md §2.1). */
fun message(
    kind: String, from: String, args: String = "{}", id: String = "m-panda-000100", thread: String = id, re: String? = null,
    body: String? = null, stage: String? = STAGE, to: String = "teamlead", by: String = if (from == "bora") "bora-cli" else "seat:$from",
    signed: Boolean = false,
): Message {
    val optional = listOfNotNull(re?.let { """"re":"$it"""" }, body?.let { """"body":${Datum.Text(it).json()}""" }, stage?.let { """"stage":"$it"""" },
        """"signed":true""".takeIf { signed })
    val line = """{"v":1,"seq":1,"at":"$NOW","id":"$id","thread":"$thread","from":"$from","to":"$to","kind":"$kind","subject":"$kind",""" +
        """"created":"$NOW","by":"$by","args":$args${optional.joinToString("") { ",$it" }}}"""
    return parseLine(line) as Message
}

/** A message line and its `delivered` record: what the log holds for a message that went through. */
fun sent(id: String, kind: String, from: String, to: String = "teamlead", args: String = "{}", thread: String = id, re: String? = null, stage: String? = STAGE): Array<String> = arrayOf(
    """{"id":"$id","thread":"$thread",${re?.let { """"re":"$it",""" }.orEmpty()}"from":"$from","to":"$to","kind":"$kind",""" +
        """${stage?.let { """"stage":"$it",""" }.orEmpty()}"subject":"$kind","created":"$NOW","by":"seat:$from","args":$args}""",
    """{"event":"delivered","id":"$id","to":["$to"],"class":"A"}""",
)

/**
 * [lines] folded as they are written: a spec's log has no `judged` line (contracts.md §3), so each line counts as it is
 * read — as in the mailroom's own state while it writes a judgement ([settled]).
 */
fun folded(lines: Iterable<kp.model.Envelope>, from: State = State()): State = fold(lines, from).settled()

/** One JSON text as a datum, read by kp-model's plain view as the engine reads a line. */
fun parseDatum(json: String): Datum = Datum.of(plain(json))

/** A datum written as the rules files write a value in flow style: `{counter: resumes, lt: {cap: resumes_per_stage}}`. */
fun Datum.flow(): String = when (this) {
    is Datum.Fields -> fields.entries.joinToString(", ", "{", "}") { (key, value) -> "$key: ${value.flow()}" }
    is Datum.Items -> items.joinToString(", ", "[", "]") { it.flow() }
    is Datum.Text -> value
    is Datum.Number -> value.toPlainString()
    is Datum.Bool -> value.toString()
    Datum.Null -> "null"
}

/** A fact by its written form — `pr.ready`, `ci.state(of=abc)` — as [FactRef.toString] writes it; a parameter is text, or JSON when it is a list or a map. */
fun ref(written: String): FactRef {
    val name = written.substringBefore('(')
    val params = written.substringAfter('(', "").removeSuffix(")").split(", ").filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=').let { value -> if (value.startsWith("[") || value.startsWith("{")) parseDatum(value) else Datum.Text(value) } }
    return FactRef(name, params)
}

/** The table of facts the mailroom hands over, by written form. */
fun table(vararg entries: Pair<String, Any?>): Facts = Facts(entries.associate { (written, value) -> ref(written) to Datum.of(value) })

/** A refusal's rule and requirement. */
fun Decision.refusal(): Pair<String?, String?> = rule to requirement.takeIf { outcome == Outcome.REFUSE }

/**
 * A refusal, asserted by the rule that refused and by what failed — never by its outcome or its words alone (the retro
 * of P1, candidate 1; contracts.md §2.2). [failed] is the first requirement that failed, in its file form as the rules
 * write it in flow style (`{fact: git.branch_exists}`); where what failed is held for every kind with no requirement
 * written there is none, and [failed] is the rules' field its `requirement` names (`from: coder`). Another rule refusing
 * a requirement written the same way does not pass (S1.6's sweep, review-018 B1).
 */
fun refusedBy(rule: String, failed: String): Matcher<Decision> = Matcher { decision ->
    val what = decision.failed?.flow() ?: decision.requirement
    MatcherResult(
        decision.outcome == Outcome.REFUSE && decision.rule == rule && what == failed,
        { "expected a refusal by $rule, failed $failed — was ${decision.outcome} by ${decision.rule}, failed $what" },
        { "expected no refusal by $rule, failed $failed" },
    )
}

/** The `delivered` record a mailroom writes from a delivery's decision (contracts.md §2.2), less `v`, `seq` and `at`; [to] is its routing. */
fun deliveredRecord(decision: Decision, id: String, to: String): String {
    val fields = listOfNotNull(
        decision.rule?.let { """"rule":"$it"""" },
        decision.deadline?.let { """"deadline":"$it"""" },
        decision.answers?.let { """"answers":"$it"""" },
        """"counted":${Datum.of(decision.counted).json()}""",
        """"observed":${Datum.Fields(decision.observed).json()}""",
        """"attested":${Datum.of(decision.attested).json()}""",
    )
    return """{"event":"delivered","id":"$id","to":["$to"],"class":"${decision.messageClass}",${fields.joinToString(",")}}"""
}

/** The `refused` record a mailroom writes from a refusal's decision: the rule's guard as `requirement`, and the requirement that failed as `failed`. */
fun refusedRecord(decision: Decision, id: String): String =
    """{"event":"refused","id":"$id","rule":"${decision.rule}","requirement":${Datum.Text(decision.requirement!!).json()},""" +
        (decision.failed?.let { """"failed":${it.json()},""" } ?: "") + """"observed":${Datum.Fields(decision.observed).json()}}"""

/** The `expired` record a mailroom writes from an expiry's decision: its rule, and the new deadline — null when the wait is over. */
fun expiredRecord(decision: Decision, id: String): String =
    """{"event":"expired","id":"$id"${decision.rule?.let { ""","rule":"$it"""" }.orEmpty()},"deadline":${decision.deadline?.let { "\"$it\"" } ?: "null"}}"""

/** A line as the log holds it: [line], given `v`, `seq` and `at`. */
fun stamped(line: String, seq: Int = 7): String = """{"v":1,"seq":$seq,"at":"$NOW",""" + line.removePrefix("{")

/** [message] as a line of the log, less `v`, `seq` and `at`. */
fun unstamped(message: Message): String = Datum.of(message.plain() - setOf("v", "seq", "at")).json()

/** What a mailroom writes for [message] and its [decision] — the message, its record, its transitions — read back through kp-model and folded onto this state. */
fun State.recorded(message: Message, decision: Decision): State {
    val record = if (decision.outcome == Outcome.REFUSE) refusedRecord(decision, message.id.value) else deliveredRecord(decision, message.id.value, message.to.value)
    return folded(after(this, unstamped(message), record, *transitionRecords(decision, message.id.value).toTypedArray()), this)
}

/** What a mailroom writes when the deadline of [id] passed and the engine decided [decision]: the `expired` record and its transitions, folded. */
fun State.expired(id: String, decision: Decision): State =
    folded(after(this, expiredRecord(decision, id), *transitionRecords(decision, id).toTypedArray()), this)

/** One `transition` record per machine the decision moves, caused by [cause]. */
fun transitionRecords(decision: Decision, cause: String): List<String> = decision.moves.map { move ->
    """{"event":"transition","machine":"${move.machine}","subject":"${move.subject}","from":"${move.from}","to":"${move.to}",""" +
        """"rule":"${move.rule}","cause":"$cause","counted":${Datum.of(move.counted).json()}}"""
}

/** [lines] as the log holds them after [state]: `seq` continues from the state's, each `at` a minute after the last. */
fun after(state: State, vararg lines: String): List<kp.model.Envelope> = lines.mapIndexed { index, line ->
    val seq = state.seq + index + 1
    parseLine("""{"v":1,"seq":$seq,"at":"2026-11-15T09:%02d:00Z",""".format(seq % 60) + line.removePrefix("{"))
}
