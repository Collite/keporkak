@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.hero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kp.daemon.NoSeats
import kp.daemon.Office
import kp.daemon.TableFacts
import kp.daemon.checkout
import kp.engine.Datum
import kp.engine.Decision
import kp.engine.Engine
import kp.engine.Facts
import kp.engine.fold
import kp.engine.settled
import kp.log.LogWriter
import kp.mailroom.Clock
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Delivered
import kp.model.Held
import kp.model.Message
import kp.model.OfficeId
import kp.model.Refused
import kp.model.StageId
import kp.model.Transition
import kp.model.parseLine
import kp.rules.Destination
import kp.rules.RuleSet
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

/** The stage every case of the sweep is about, the time it is judged at, and the time of what the log holds before it. */
const val CASE_STAGE = "SWEEP/P1/S1.1"
const val NOW = "2026-11-17T09:00:00Z"
const val EARLIER = "2026-11-17T08:00:00Z"
private const val OFFICE = "panda"

/** Something the log holds before a case's message is sent. */
sealed interface Seed {
    /** A machine's subject moved — with the counters the move carries. */
    data class Move(val machine: String, val subject: String, val from: String, val to: String, val counted: Map<String, Long> = emptyMap(), val cause: String? = null) : Seed

    /** A message delivered earlier, named [key] for a later seed or for the case's `re`. */
    data class Sent(
        val key: String, val kind: String, val from: String, val to: String, val args: Map<String, Datum> = emptyMap(), val messageClass: String = "A",
        val signed: Boolean = false, val stage: String? = CASE_STAGE, val read: Boolean = true, val deadline: String? = null,
    ) : Seed

    /** The flag machine's state of the thread of the message [key]. */
    data class Thread(val key: String, val state: String) : Seed

    /** A seat's refusals today, as its last refusal counted them. */
    data class Refusals(val seat: String, val count: Long) : Seed
}

/**
 * A world a case is judged in (S1.6 T5): what the log holds before the message ([seeds], the stage in [stageState]),
 * the table of facts, and the message — its kind, sender, args, the earlier message it answers and its addressee.
 */
data class World(
    val kind: String,
    val sender: String,
    val signed: Boolean = false,
    val args: Map<String, Datum> = emptyMap(),
    val re: String? = null,
    val to: String? = null,
    val stageState: String = "planned",
    val seeds: List<Seed> = emptyList(),
    val facts: Map<String, Datum> = emptyMap(),
) {
    fun seeded(seed: Seed): World = copy(seeds = seeds + seed)

    /** The lines the log holds before the message, in order, `seq` from 1; and the id each [Seed.Sent] got. */
    fun render(): Pair<List<JsonObject>, Map<String, String>> {
        val lines = ArrayList<JsonObject>()
        val ids = HashMap<String, String>()
        val threads = HashMap<String, String>()
        fun next(): Long = lines.size + 1L
        fun line(vararg fields: Pair<String, JsonElement?>) = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("seq", JsonPrimitive(next()))
            put("at", JsonPrimitive(EARLIER))
            fields.forEach { (key, value) -> if (value != null && value != JsonNull) put(key, value) }
        }.also(lines::add)
        fun move(machine: String, subject: String, from: String, to: String, counted: Map<String, Long>, cause: String = "sweep") = line("event" to t("transition"), "machine" to t(machine),
            "subject" to t(subject), "from" to t(from), "to" to t(to), "cause" to t(cause),
            "counted" to counted.takeIf { it.isNotEmpty() }?.let { JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) }) })
        if (stageState != "planned") move("stage", CASE_STAGE, "planned", stageState, emptyMap())
        seeds.forEach { seed ->
            when (seed) {
                is Seed.Move -> move(seed.machine, seed.subject, seed.from, seed.to, seed.counted, seed.cause?.let(ids::getValue) ?: "sweep")
                is Seed.Sent -> {
                    val id = "m-$OFFICE-%06d".format(next())
                    ids[seed.key] = id
                    threads[seed.key] = id
                    line("id" to t(id), "thread" to t(id), "from" to t(seed.from), "to" to t(seed.to), "kind" to t(seed.kind), "stage" to seed.stage?.let(::t),
                        "subject" to t(seed.kind), "args" to seed.args.takeIf { it.isNotEmpty() }?.let { json(Datum.Fields(it)) }, "created" to t(EARLIER),
                        "signed" to JsonPrimitive(true).takeIf { seed.signed }, "by" to t(by(seed.from)), "deadline" to seed.deadline?.let(::t))
                    line("event" to t("delivered"), "id" to t(id), "to" to Json.parseToJsonElement("[\"${seed.to}\"]"), "class" to t(seed.messageClass),
                        "deadline" to seed.deadline?.let(::t))
                    if (seed.read && seed.to !in PARTIES) line("event" to t("read"), "seat" to t(seed.to), "ids" to Json.parseToJsonElement("[\"$id\"]"))
                }
                is Seed.Thread -> move("flag", threads.getValue(seed.key), "asked", seed.state, emptyMap())
                is Seed.Refusals -> {
                    val id = "m-$OFFICE-%06d".format(next())
                    line("id" to t(id), "thread" to t(id), "from" to t(seed.seat), "to" to t("teamlead"), "kind" to t("started"), "stage" to t(CASE_STAGE),
                        "subject" to t("started"), "created" to t(EARLIER), "by" to t("seat:${seed.seat}"))
                    line("event" to t("refused"), "id" to t(id), "rule" to t("started"), "requirement" to t("sweep"), "refusals_today" to JsonPrimitive(seed.count))
                }
            }
        }
        return lines to ids
    }

    /** What the sender supplies (contracts.md §2.1): the kind, the stage, a subject, the args, the message it answers, its addressee. */
    fun input(ids: Map<String, String>): JsonObject = JsonObject(listOfNotNull(
        "kind" to t(kind), "stage" to t(CASE_STAGE), "subject" to t("$kind (sweep)"), "args" to json(Datum.Fields(args)),
        re?.let { "re" to t(ids.getValue(it)) }, to?.let { "to" to t(it) },
    ).toMap())

    /** The message as the mailroom would write it — for a judgement in memory. */
    fun message(rules: RuleSet, seq: Long, ids: Map<String, String>): Message {
        val id = "m-$OFFICE-%06d".format(seq)
        val reSent = re?.let { key -> seeds.filterIsInstance<Seed.Sent>().first { it.key == key } }
        val declared = rules.kinds[kind]
        val addressee = to ?: when (val destination = declared?.to) {
            is Destination.Parties -> destination.names.first()
            is Destination.Reply -> reSent?.from
            Destination.AnySeat -> (args["seat"] as? Datum.Text)?.value
            null -> null
        } ?: "mailroom"
        val json = buildJsonObject {
            put("v", JsonPrimitive(1)); put("seq", JsonPrimitive(seq)); put("at", t(NOW)); put("id", t(id))
            put("thread", t(re?.let(ids::getValue) ?: id)); re?.let { put("re", t(ids.getValue(it))) }
            put("from", t(sender)); put("to", t(addressee))
            declared?.cc?.takeIf { it.isNotEmpty() }?.let { cc -> put("cc", Json.parseToJsonElement(cc.joinToString(",", "[", "]") { "\"$it\"" })) }
            put("kind", t(kind)); put("stage", t(CASE_STAGE)); put("subject", t("$kind (sweep)")); put("args", json(Datum.Fields(args)))
            put("created", t(NOW)); if (signed && sender == BORA) put("signed", JsonPrimitive(true)); put("by", t(by(sender)))
        }
        return parseLine(json.toString()) as Message
    }

    /** The engine's judgement of the message in this world, in memory: the search's oracle. */
    fun judge(rules: RuleSet): Decision {
        val (lines, ids) = render()
        val state = fold(lines.map { parseLine(it.toString()) }).settled()
        val trigger = kp.engine.Trigger.Arrival(message(rules, lines.size + 1L, ids))
        val engine = Engine(rules)
        return engine.judge(state, trigger, Facts.collect(engine.needs(state, trigger), ::observe))
    }

    fun observe(ref: kp.engine.FactRef): Datum? = if (ref.name == "now") Datum.Text(NOW) else TableFacts.lookup(facts[ref.name], ref)

    /**
     * The case through a real office (S1.6 T1's seam): the world's lines written to a throwaway project, the office
     * started on it — the loader, the log, the engine, the mailroom and its robots — the message submitted as its
     * sender would, and what became of it read back from the log.
     */
    fun inOffice(root: Path = checkout, trigger: Trigger = Trigger.Sent, results: Map<String, Boolean> = emptyMap()): Outcome {
        val project = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "sweep-")
        try {
            val (lines, ids) = render()
            LogWriter.open(project, OfficeId(OFFICE)).use { log ->
                val judgement = log.judgement() // the world as one judgement, closed: a start has nothing to void (Amendment 23)
                lines.forEach { line ->
                    val draft = JsonObject(line - "seq")
                    val stage = when {
                        "kind" in line -> null
                        line["machine"] == t("stage") -> StageId(CASE_STAGE)
                        line["event"] == t("delivered") || line["event"] == t("refused") -> (lines.firstOrNull { it["id"] == line["id"] && "kind" in it }?.get("stage") as? JsonPrimitive)?.content?.let(::StageId)
                        else -> null
                    }
                    judgement.add(draft, stage)
                }
                if (lines.isNotEmpty()) judgement.close(NOW)
            }
            val operations = Operations { request, done -> done(if (results[request.operation] == false) OperationResult.Failed("failed in the sweep") else OperationResult.Ok()) }
            val office = Office.start(OFFICE, project, root, TableFacts(facts), operations, NoSeats, Clock { Instant.parse(NOW) })
            return office.use {
                val before = office.state.seq
                val id = when (trigger) {
                    Trigger.Sent -> office.submit(if (sender == BORA) Sender.Bora(signed) else Sender.Seat(sender), input(ids)).id
                    is Trigger.Expiry -> ids.getValue(trigger.key).also { office.tick() }
                    is Trigger.Changed -> null.also { office.changed(kp.engine.FactRef(trigger.fact), trigger.value) }
                    Trigger.Acted -> null.also { office.acted() }
                }
                val written = office.lines().map { it.entry }
                val record = id?.let { written.lastOrNull { line -> (line is Delivered && line.id == id) || (line is Refused && line.id == id) || (line is Held && line.id == id) } }
                Outcome(id ?: "", record, written.filterIsInstance<Transition>().filter { if (id != null) it.cause == id else it.seq.toLong() > before }.mapNotNull { it.rule })
            }
        } finally {
            project.deleteRecursively()
        }
    }

    /** What sets a case going: its message sent; a deadline of a message the world holds passing; a fact reported; Bora acting. */
    sealed interface Trigger {
        data object Sent : Trigger
        data class Expiry(val key: String) : Trigger
        data class Changed(val fact: String, val value: Datum) : Trigger
        data object Acted : Trigger
    }

    /** What became of a case's message: its last record and the transitions it fired. */
    class Outcome(val id: String, val record: kp.model.Envelope?, val fired: List<String>) {
        val refused: Refused? get() = record as? Refused
        val held: Held? get() = record as? Held
        val delivered: Boolean get() = record is Delivered
        override fun toString(): String = when (val r = record) {
            is Refused -> "refused by ${r.rule}: ${r.failed ?: r.requirement}"
            is Held -> "held by ${r.by}"
            is Delivered -> "delivered" + (if (fired.isEmpty()) "" else ", fired ${fired.joinToString()}")
            else -> "no record"
        }
    }

    companion object {
        const val BORA = "bora"
        val PARTIES = setOf("bora", "daemon", "mailroom", "ledger", "board")
        private val ROBOTS = setOf("deputy", "pool", "qa", "retro")

        private fun t(text: String) = JsonPrimitive(text)
        fun json(datum: Datum): JsonElement = Json.parseToJsonElement(datum.json())
        private fun by(from: String): String = when (from) {
            BORA -> "bora-cli"
            "mailroom" -> "mailroom"
            in ROBOTS -> "seat:$from"
            else -> "seat:$from"
        }
    }
}
