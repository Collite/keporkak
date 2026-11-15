package kp.engine

import kp.model.Message
import kp.model.parseLine
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

/** The engine under v1's autonomy profile. */
fun supervised(rules: RuleSet = master) = Engine(rules, "supervised")

const val STAGE = "KP/P1/S1.2"
const val NOW = "2026-11-15T10:00:00Z"

/** A message as the mailroom hands it to the engine: its id, thread and times already set (contracts.md §2.1). */
fun message(
    kind: String, from: String, args: String = "{}", id: String = "m-panda-000100", thread: String = id, re: String? = null,
    body: String? = null, stage: String? = STAGE, to: String = "teamlead", by: String = if (from == "bora") "bora-cli" else "seat:$from",
): Message {
    val optional = listOfNotNull(re?.let { """"re":"$it"""" }, body?.let { """"body":${Datum.Text(it).json()}""" }, stage?.let { """"stage":"$it"""" })
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

/** A fact by its written form — `pr.ready`, `ci.state(of=abc)` — as [FactRef.toString] writes it; parameters are text. */
fun ref(written: String): FactRef {
    val name = written.substringBefore('(')
    val params = written.substringAfter('(', "").removeSuffix(")").split(", ").filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to Datum.Text(it.substringAfter('=')) as Datum }
    return FactRef(name, params)
}

/** The table of facts the mailroom hands over, by written form. */
fun table(vararg entries: Pair<String, Any?>): Facts = Facts(entries.associate { (written, value) -> ref(written) to Datum.of(value) })

/** A refusal's rule and requirement. */
fun Decision.refusal(): Pair<String?, String?> = rule to requirement.takeIf { verdict == Verdict.REFUSE }
