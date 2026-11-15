package kp.engine

import kp.model.Envelope
import kp.model.Message
import kp.model.parseLine
import kp.rules.Destination
import java.io.File

/** One step of a walk as either side saw it: refused or not, the transitions it fired, and every stage's state. */
data class TraceStep(val step: Int, val refused: Boolean, val fired: List<String>, val stages: Map<String, String>)

private fun pythonWalk(name: String): List<String> {
    val process = ProcessBuilder(File(root, "build/python/bin/python").path, "scripts/check-rules.py", "--trace", name)
        .directory(root).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readLines()
    process.waitFor()
    val failures = output.filter { it.startsWith("FAIL walk $name") }
    check(failures.isEmpty()) { failures.joinToString("\n") }
    return output
}

/** The walk as the Python walker read it from `rules/walks/` (its `WALK` line). */
fun walkDefinition(name: String): Datum.Fields =
    Datum.parse(pythonWalk(name).single { it.startsWith("WALK ") }.removePrefix("WALK ")) as Datum.Fields

/** The Python walker's own steps (`check-rules.py --trace`): the second opinion. */
fun walkerTrace(name: String): List<TraceStep> = pythonWalk(name).filter { it.startsWith("TRACE ") }.map { line ->
    val step = (Datum.parse(line.removePrefix("TRACE ")) as Datum.Fields).fields
    TraceStep(
        (step.getValue("step") as Datum.Number).value.toInt(),
        (step.getValue("refused") as Datum.Bool).value,
        (step.getValue("fired") as Datum.Items).items.map { (it as Datum.Text).value },
        (step.getValue("stages") as Datum.Fields).fields.mapValues { (it.value as Datum.Text).value },
    )
}

/** The same walk through the engine, with a fake mailroom that writes what each decision says and folds it. */
fun engineTrace(name: String): List<TraceStep> = WalkThrough(walkDefinition(name)).run()

private fun Datum?.text(): String? = (this as? Datum.Text)?.value
private fun Datum?.fields(): Map<String, Datum> = (this as? Datum.Fields)?.fields.orEmpty()

/**
 * A fake mailroom for one walk. It seeds the walk's initial states as records, hands each step to the engine with the
 * walk's facts as the table, and writes the message, the `delivered` or `refused` record, the `transition` records and
 * the requested operations — every line through kp-model's reader, so each is a valid line of contracts.md §2 — and
 * folds them before the next step. Facts are looked up the walker's way: a plain value, or a map keyed by the
 * parameter the fact is asked with.
 */
private class WalkThrough(private val walk: Datum.Fields) {
    private val engine = supervised()
    private val definition = walk.fields
    private val stageIds = LinkedHashMap<String, String>()
    private val facts = HashMap<String, MutableMap<String, Datum>>()
    private val ids = HashMap<String, String>()
    private val messages = HashMap<String, Message>()
    private var lines = ArrayList<Envelope>()
    private var state = State()
    private var seq = 0
    private var minute = 0
    private var current = definition["stage"].text() ?: "fixture-stage"

    private fun now() = "2026-11-15T10:%02d:00Z".format(minute)

    private fun write(json: String): Envelope {
        seq++
        val line = parseLine("""{"v":1,"seq":$seq,"at":"${now()}",""" + json.removePrefix("{"))
        lines += line
        state = fold(listOf(line), state)
        return line
    }

    private fun stage(name: String): String = stageIds.getOrPut(name) {
        val id = "WALK/P1/S1.${stageIds.size + 1}"
        val setting = definition["stages"].fields()[name].fields()
        check(setting["machines"] == null) { "a walk that sets companion machines is not one this harness seeds" }
        val initial = setting["initial"].text() ?: definition["initial"].text() ?: "planned"
        if (initial != "planned") write("""{"event":"transition","machine":"stage","subject":"$id","from":"planned","to":"$initial","cause":"m-walk-000000"}""")
        facts[name] = (definition["facts"].fields() + setting["facts"].fields()).toMutableMap()
        id
    }

    private fun observe(ref: FactRef): Datum? {
        if (ref.name == "now") return Datum.Text(now())
        val value = facts.getValue(current)[ref.name] ?: return null
        if (value !is Datum.Fields) return value
        val key = listOf("of", "seat", "id", "dimension", "subscription", "pointer").firstNotNullOfOrNull { ref.params[it] } ?: return null
        return value.fields[key.text() ?: key.json()]
    }

    fun run(): List<TraceStep> {
        stage(current)
        definition["seats"].fields().keys.forEach { seat ->
            write("""{"event":"transition","machine":"seat","subject":"$seat","from":"provisioned","to":"ready","cause":"m-walk-000000"}""")
        }
        val steps = (definition["steps"] as Datum.Items).items.map { it.fields() }
        return steps.mapIndexed { index, step ->
            minute = index + 1
            current = step["stage"].text() ?: current
            val stageId = stage(current)
            facts.getValue(current).putAll(step["facts"].fields())
            val decision = if (step["operation"] != null) result(step) else arrival(step, index + 1, stageId)
            TraceStep(index + 1, decision.outcome == Outcome.REFUSE, decision.moves.map { it.rule },
                stageIds.mapValues { (_, id) -> state.stage(id) ?: "planned" })
        }
    }

    private fun result(step: Map<String, Datum>): Decision {
        val request = ids.getValue(step["request"].text()!!)
        val operation = step["operation"].text()!!
        val ok = (step["result"].text() ?: "ok") == "ok"
        write("""{"event":"op","op":"$operation","phase":"${if (ok) "ok" else "failed"}","cause":"$request"}""")
        val trigger = Trigger.Result(request, operation, ok)
        val decision = engine.judge(state, trigger, Facts.collect(engine.needs(state, trigger), ::observe))
        record(decision, request)
        return decision
    }

    private fun arrival(step: Map<String, Datum>, index: Int, stageId: String): Decision {
        val id = "m-walk-%06d".format(index)
        step["id"].text()?.let { ids[it] = id }
        val kind = step.getValue("kind").text()!!
        val who = step["who"].text() ?: master.kinds.getValue(kind).from.first()
        val replyTo = step["re"].text()?.let { messages.getValue(ids.getValue(it)) }
        val args = step["args"] ?: Datum.Fields(emptyMap())
        val to = when (val destination = master.kinds.getValue(kind).to) {
            is Destination.Parties -> destination.names.first()
            is Destination.Reply -> replyTo!!.from.value
            Destination.AnySeat -> args.fields()["seat"].text() ?: who
        }
        val by = step["by"].text() ?: if (who == "bora") "bora-cli" else "seat:$who"
        val optional = listOfNotNull(replyTo?.let { """"re":"${it.id.value}"""" }, step["body"]?.let { """"body":${it.json()}""" })
        val message = write("""{"id":"$id","thread":"${replyTo?.thread?.value ?: id}","from":"$who","to":"$to","kind":"$kind","stage":"$stageId",""" +
            """"subject":"$kind","created":"${now()}","by":"$by","args":${args.json()}${optional.joinToString("") { ",$it" }}}""") as Message
        messages[id] = message
        val trigger = Trigger.Arrival(message, signed = step["signed"].text() == "bora")
        val decision = engine.judge(state, trigger, Facts.collect(engine.needs(state, trigger), ::observe))
        if (decision.outcome == Outcome.REFUSE) write(refusedRecord(decision, id))
        else {
            write(deliveredRecord(decision, id, to))
            record(decision, id)
        }
        return decision
    }

    /** The transitions a decision moves and the operations it requests, caused by [cause]. */
    private fun record(decision: Decision, cause: String) {
        transitionRecords(decision, cause).forEach(::write)
        decision.effects.filterIsInstance<Requested.Do>().forEach { effect ->
            write("""{"event":"op","op":"${effect.operation}","phase":"requested","cause":"$cause","with":${Datum.Fields(effect.with).json()}}""")
        }
    }
}
