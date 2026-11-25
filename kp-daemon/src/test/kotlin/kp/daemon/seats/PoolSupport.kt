package kp.daemon.seats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kp.daemon.InForceRules
import kp.daemon.Judgements
import kp.daemon.NoSeats
import kp.daemon.Office
import kp.daemon.Reports
import kp.daemon.ProjectWorld
import kp.daemon.RuleFiles
import kp.daemon.TestOffice
import kp.daemon.answered
import kp.daemon.checkout
import kp.daemon.git
import kp.daemon.json
import kp.daemon.repository
import kp.daemon.write
import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.fold
import kp.facts.Observation
import kp.facts.ProjectFacts
import kp.facts.VendorReport
import kp.log.LogWriter
import kp.mailroom.Answer
import kp.mailroom.Clock
import kp.mailroom.FactSource
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Envelope
import kp.model.OfficeId
import kp.model.parseLine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.13 — the seams of the pool's facts and the budgets' (PoolAndBudgetFactsSpec): the daemon's fact source over a
 * fixture pool and a folded fixture log, asked as the mailroom asks ([PoolAsked]); and the daemon on a fixture office
 * whose world is the service's — the project's facts and the pool's ([PoolOffice]).
 */

/**
 * A fixture roster: two vendors; the first line, a model on two efforts, a model of the second vendor, and last a
 * reserved model. Levels: junior → line 0, senior S → line 1, senior M and above → line 2; the review floor is beta.
 */
val ROSTER = """
    version: 1
    vendors:
      claude: {adapter: "@agentclientprotocol/claude-agent-acp", adapter_version: "0.106.0"}
      acme: {adapter: "@acme/agent"}
    order:
      - {vendor: claude, model: claude-alpha-1, effort: low}
      - {vendor: claude, model: claude-beta-1, effort: low}
      - {vendor: claude, model: claude-beta-1, effort: high}
      - {vendor: acme, model: acme-omega-1, effort: high}
      - {vendor: claude, model: claude-gamma-1, effort: high}
    levels:
      junior:
        S: {vendor: claude, model: claude-alpha-1, effort: low}
        M: {vendor: claude, model: claude-alpha-1, effort: low}
        L: {vendor: claude, model: claude-alpha-1, effort: low}
        XL: {vendor: claude, model: claude-alpha-1, effort: low}
      senior:
        S: {vendor: claude, model: claude-beta-1, effort: low}
        M: {vendor: claude, model: claude-beta-1, effort: high}
        L: {vendor: claude, model: claude-beta-1, effort: high}
        XL: {vendor: claude, model: claude-beta-1, effort: high}
    review:
      floor: claude-beta-1
    reserved:
      claude-gamma-1: [principal]
    role_models: {principal: {model: claude-gamma-1, effort: high}, qa: {model: acme-omega-1, effort: high}}
""".trimIndent() + "\n"

/**
 * Fixture budgets: two seats per office and for claude, one for acme, two stages in flight per effort; a turn's parts
 * weighted 1, 5, 1.25 and 0.1, the daily median over 14 days with at least 3.
 */
val BUDGETS = """
    version: 1
    slots: {total: 9, per_subscription: {claude: 2, acme: 1}, per_office: {panda: 2, dsk: 2}, stages_in_flight_per_effort: 2}
    warn: [0.7, 0.9]
    on_cap: pause_provisioning
    usage:
      weights: {input: 1, output: 5, cache_write: 1.25, cache_read: 0.1}
      median: {days: 14, least: 3}
""".trimIndent() + "\n"

/** A copy of master's rules whose `pool/roster.yaml` is [roster] and `pool/budgets.yaml` is [budgets]. */
fun fixtureRules(roster: String = ROSTER, budgets: String = BUDGETS): Path = TestOffice.copyOfTheRules().also { root ->
    root.resolve("pool/roster.yaml").writeText(roster)
    root.resolve("pool/budgets.yaml").writeText(budgets)
}

/** The rules under [root], as the daemon loads them: in force, or the spec fails with their problems. */
fun inForce(root: Path): InForceRules = InForceRules.of(RuleFiles(root).read()).let { (rules, loaded) -> rules ?: error("the fixture rules are not valid: ${loaded.problems}") }

/** A seat as a fixture log has it: started by a `seat.start` for [role] in [stage] — a request naming [level] — and now in [state]. */
data class SeatAt(val seat: String, val role: String, val stage: String?, val level: String?, val state: String)

/**
 * A fixture log, in judgements: each [SeatAt] as SEAT-01 starts one — the Pool Manager's report, its delivery, the seat
 * machine's move, `seat.start` requested and ok — then moved to its state; each stage of [stages] moved from `planned` to
 * its state, and each of [then] on from that state to its own in a judgement after (S3.4: a stage paused, which comes back
 * to the state it was in); each turn's end of [turns] as the daemon records it. Every line at [at], unless the turn's end
 * says when.
 */
class FixtureLog(
    seats: List<SeatAt> = emptyList(), stages: Map<String, String> = emptyMap(), turns: List<TurnEnd> = emptyList(), private val at: String = "2026-11-20T08:00:00Z",
    reports: List<Reported> = emptyList(),
    then: Map<String, String> = emptyMap(),
) {
    /** A turn's end as its record leaves it: a `fact` of `seat.usage`, window `turn`, its count and its evidence. */
    data class TurnEnd(val seat: String, val at: String, val count: Long, val evidence: String)

    /**
     * S3.14: the Team Lead's request for [seat] as [role] at [level] about [requested] — on the log, delivered — and the
     * Pool Manager's report on it about [stage], which is not: a report being judged is not on the log yet ([arriving]).
     */
    data class Reported(val seat: String, val role: String, val stage: String?, val level: String? = null, val requested: String? = null)

    private val requests = reports.mapIndexed { n, report -> report to "m-panda-8${"%05d".format(n + 1)}" }

    val judgements: List<List<JsonObject>> = seats.mapIndexed { n, seat -> started(seat, "m-panda-9${"%05d".format(n + 1)}") } +
        requests.map { (report, request) -> requested(report, request) } +
        listOfNotNull(stages.takeIf { it.isNotEmpty() }?.map { (stage, state) -> record("transition", "machine" to "stage", "subject" to stage, "from" to "planned", "to" to state, "cause" to "fixture") }) +
        listOfNotNull(then.takeIf { it.isNotEmpty() }?.map { (stage, state) -> record("transition", "machine" to "stage", "subject" to stage, "from" to stages.getValue(stage), "to" to state, "cause" to "fixture") }) +
        turns.map { turn ->
            listOf(record("fact", "fact" to "seat.usage", "subject" to "${turn.seat}, turn", "value" to turn.count, "evidence" to turn.evidence,
                "params" to mapOf("seat" to turn.seat, "window" to "turn"), when_ = turn.at))
        }

    /** The lines as they count, `seq` from 1, each judgement closed by its `judged` line. */
    val lines: List<Envelope> = buildList {
        judgements.forEach { judgement ->
            val first = size + 1
            judgement.forEach { add(parseLine(JsonObject(it + ("seq" to JsonPrimitive(size + 1))).toString())) }
            add(parseLine(JsonObject(record("judged", "first" to first) + ("seq" to JsonPrimitive(size + 1))).toString()))
        }
    }

    /** Writes the judgements to [project]'s log, as the office's writer would have. */
    fun writeTo(project: Path) {
        if (judgements.isEmpty()) return
        LogWriter.open(project, OfficeId("panda")).use { log ->
            judgements.forEach { lines ->
                val judgement = log.judgement()
                lines.forEach { judgement.add(it, null) }
                judgement.close(at)
            }
        }
    }

    private fun started(seat: SeatAt, id: String): List<JsonObject> {
        val args = mapOf("seat" to seat.seat, "role" to seat.role) + (seat.level?.let { mapOf("level" to it) } ?: emptyMap())
        val message = buildJsonObject {
            put("v", 1); put("at", at); put("id", id); put("thread", id); put("from", "pool"); put("to", "teamlead"); put("kind", "session-report")
            seat.stage?.let { put("stage", it) }
            put("subject", "session-report"); put("created", at); put("by", "robot:POOL-1"); put("args", element(args))
        }
        val with = mapOf("seat" to seat.seat, "role" to seat.role)
        return listOfNotNull(
            message,
            record("delivered", "id" to id, "to" to listOf("teamlead"), "class" to "A"),
            record("transition", "machine" to "seat", "subject" to seat.seat, "from" to "provisioned", "to" to "provisioned", "rule" to "SEAT-01", "cause" to id),
            record("op", "op" to "seat.start", "phase" to "requested", "cause" to id, "with" to with),
            record("op", "op" to "seat.start", "phase" to "ok", "cause" to id, "with" to with, "result" to mapOf("seat" to seat.seat, "session" to "session-of-${seat.seat}")),
            seat.state.takeIf { it != "provisioned" }?.let { state -> record("transition", "machine" to "seat", "subject" to seat.seat, "from" to "provisioned", "to" to state, "cause" to "fixture") },
        )
    }

    /** The Pool Manager's report of [seats]'s entry for [seat], answering its request, as the mailroom judges it: not on the log. */
    fun arriving(seat: String): kp.model.Message {
        val (report, request) = requests.single { it.first.seat == seat }
        return parseLine(JsonObject(message("m-panda-800999", request, "pool", "teamlead", "session-report", report.stage, request,
            mapOf("seat" to report.seat, "role" to report.role)) + ("seq" to JsonPrimitive(999))).toString()) as kp.model.Message
    }

    private fun requested(report: Reported, request: String): List<JsonObject> {
        val asked = mapOf("action" to "add", "seat" to report.seat, "role" to report.role) + (report.level?.let { mapOf("level" to it) } ?: emptyMap())
        return listOf(
            message(request, request, "teamlead", "pool", "session-request", report.requested, null, asked),
            record("delivered", "id" to request, "to" to listOf("pool"), "class" to "A"),
        )
    }

    private fun message(id: String, thread: String, from: String, to: String, kind: String, stage: String?, re: String?, args: Map<String, String>) = buildJsonObject {
        put("v", 1); put("at", at); put("id", id); put("thread", thread); put("from", from); put("to", to); put("kind", kind)
        stage?.let { put("stage", it) }
        re?.let { put("re", it) }
        put("subject", kind); put("created", at); put("by", "seat:$from"); put("args", element(args))
    }

    private fun record(event: String, vararg fields: Pair<String, Any>, when_: String = at): JsonObject =
        JsonObject(mapOf("v" to JsonPrimitive(1), "at" to JsonPrimitive(when_), "event" to JsonPrimitive(event)) + fields.associate { (key, value) -> key to element(value) })
}

private fun element(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
    null -> kotlinx.serialization.json.JsonNull
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::element))
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key as String to element(item) })
    else -> error("not a JSON value: $value")
}

/**
 * The daemon's fact source for the pool's facts over [rules] and [log] folded, at [now], asked as the mailroom asks —
 * `observe(fact, stage)`. [live] stands for the seats the daemon drives: each one's model and effort as it was started;
 * [reports] for what the vendor reported through them.
 */
class PoolAsked(
    rules: Path = fixtureRules(),
    log: FixtureLog = FixtureLog(),
    now: String = "2026-11-20T09:00:00Z",
    live: Map<String, Pair<String, String>> = emptyMap(),
    reports: List<VendorReport> = emptyList(),
    /** S3.14: the message being judged, not on the log yet — a `session-report` among them. */
    arriving: kp.model.Message? = null,
) {
    val journal = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val inForce = inForce(rules)
    private val state = fold(log.lines)
    val facts = PoolFacts("panda") { journal += it }.also { facts ->
        facts.reading(PoolFacts.Reading({ inForce }, { state }, { log.lines }, { Instant.parse(now) }, bound = { live[it] }, reports = { reports }, arriving = { arriving }))
    }

    /** [name] with [params], about [stage]: its value — a boolean, a whole number, a decimal or a text — or null when unknown. */
    fun ask(name: String, vararg params: Pair<String, String>, stage: String? = null): Any? = plain(facts.observe(ref(name, params), stage))

    /** What is missing when [name] is unknown; null when it has a value. */
    fun unknown(name: String, vararg params: Pair<String, String>, stage: String? = null): String? =
        (facts.observation(ref(name, params), stage)?.second as? Observation.Unknown)?.missing

    /** The evidence of [name]'s value. */
    fun evidence(name: String, vararg params: Pair<String, String>, stage: String? = null): String =
        (facts.observation(ref(name, params), stage)!!.second as Observation.Value).evidence
}

private fun ref(name: String, params: Array<out Pair<String, String>>) = FactRef(name, params.associate { (key, value) -> key to Datum.Text(value) })

/** A datum as a spec writes its expected value. */
fun plain(datum: Datum?): Any? = when (datum) {
    null -> null
    is Datum.Bool -> datum.value
    is Datum.Text -> datum.value
    is Datum.Number -> datum.value.stripTrailingZeros().let { if (it.scale() <= 0) it.intValueExact() else it }
    else -> datum.json()
}

/**
 * The daemon on a fixture office (S3.13's DONE): master's rules with the fixture pool, on a project checkout with KP's
 * plan, overview, STATUS and the list of `KP/P3/S3.1`, whose log [log] starts; its world is the service's — the project's
 * facts ([ProjectWorld]) and the pool's ([PoolFacts]) — and `roster.model_allowed`, which the seats answer in the
 * service, is true. Operations end `ok`. The pool can be changed while it runs ([budgets]).
 *
 * S3.4: with [reporting], the daemon's [Reports] (Amendment 45) are the office's, as the service makes them — told of a
 * judgement that moved a seat or a stage and of a reload by the office; of the watch, a turn's end and the vendor's
 * reports by the spec, as the seats tell them; the start is the spec's to call. [repos] are the office's main checkouts
 * in place of the pool's; [vendor] what the vendor last reported through the seats.
 */
class PoolOffice(log: FixtureLog = FixtureLog(), budgets: String = BUDGETS, repos: Map<String, Path> = emptyMap(), reporting: Boolean = false) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "pool-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, "plan.md", "## 2 · Stages\n\n| stage | after | goal |\n|---|---|---|\n| **S3.1 Facts** | — | the facts |\n\n## 3 · Chart\n```mermaid\nflowchart LR\n  S3.1[S3.1 Facts]\n```\n")
        write(project, "tasks-overview.md", "- [ ] **P3**\n  - [ ] **S3.1** Facts\n")
        write(project, "tasks-p3-s3.1-facts.md", LIST)
        write(project, "STATUS.md", "# KP — status\n")
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "cut S3.1")
        log.writeTo(project)
    }
    val root: Path = fixtureRules(budgets = budgets).also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText().replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\","))
        if (repos.isNotEmpty()) offices.writeText(offices.readText().replaceFirst(Regex("(?m)^    repos: \\{.*}$"),
            Regex.escapeReplacement("    repos: {" + repos.entries.joinToString(", ") { (repo, dir) -> "$repo: \"$dir\"" } + "}")))
    }
    val journal = java.util.concurrent.CopyOnWriteArrayList<String>()
    val world: ProjectWorld = ProjectWorld.of(inForce(root), "panda", project) { journal += it }
    val pool = PoolFacts("panda") { journal += it }
    private val facts = FactSource { fact, stage ->
        when {
            fact.name in ProjectFacts.ANSWERED -> world.observe(fact, stage)
            fact.name in PoolFacts.ANSWERED -> pool.observe(fact, stage)
            fact.name == "roster.model_allowed" -> Datum.Bool(true)
            else -> null
        }
    }
    /** What the vendor last reported through the seats — what the seats hand the pool's facts. */
    @Volatile var vendor: List<VendorReport> = emptyList()
    val office: Office = Office.start("panda", project, root, facts, Operations { _, done -> done(OperationResult.Ok()) }, NoSeats,
        Clock { Instant.parse("2026-11-20T09:00:00Z") }, folded = { office ->
            world.reading(office)
            pool.reading(PoolFacts.Reading({ office.rules }, { office.state }, { office.counted() }, { office.clock.now() }, reports = { vendor }, arriving = office::arriving))
        })
    val reports: Reports? = if (reporting) Reports(office, world, pool).also { office.reports = it } else null

    /** A message from [from] — a role's seat, or a robot's role — of [kind] about `KP/P3/S3.1`, with [args] (JSON). */
    fun send(from: String, kind: String, args: String, to: String? = null, re: String? = null): Answer = office.submit(Sender.Seat(from), json(
        """{"kind": "$kind", "stage": "KP/P3/S3.1", "subject": "$kind", "args": $args""" + (to?.let { """, "to": "$it"""" } ?: "") + (re?.let { """, "re": "$it"""" } ?: "") + "}",
    )).answered()

    /** `pool/budgets.yaml` rewritten as [budgets], and the office's rules reloaded: no restart. */
    fun budgets(budgets: String) {
        root.resolve("pool/budgets.yaml").writeText(budgets)
        checkNotNull(office.reloadIfChanged()) { "the office did not reload its rules" }
    }

    /** [name] with [params] as the pool's facts answer it now about `KP/P3/S3.1`. */
    fun ask(name: String, vararg params: Pair<String, String>): Any? = plain(pool.observe(ref(name, params), "KP/P3/S3.1"))

    /** The facts the judgement of the last message of [kind] observed, each with its value as JSON. */
    fun observedOf(kind: String): Map<String, String> {
        val lines = office.lines().map { it.entry }
        val id = lines.filterIsInstance<kp.model.Message>().last { it.kind == kind }.id.value
        val judged = lines.filterIsInstance<kp.model.Record>().first { record ->
            (record.json["id"] as? JsonPrimitive)?.content == id && (record.json["event"] as? JsonPrimitive)?.content in setOf("delivered", "refused")
        }
        return (judged.json["observed"]?.jsonObject ?: JsonObject(emptyMap())).mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    override fun close() {
        office.close()
        Judgements.check(project)
    }

    private companion object {
        /** `KP/P3/S3.1`'s list: its fence complete, six tasks. */
        val LIST = """
            # KP-keporkak-P3 · S3.1 — Facts            (PR: feat/kp-keporkak-p3-s3.1-facts)
            rolls: none  ·  live: none  ·  touches: kp-facts/

            ## Scope fence
            - Stop conditions: SC-1 another repo · SC-2 contracts.md
            ## Tasks
            - [ ] T1 — Specs
            - [ ] T2 — The shape
            - [ ] T3 — The plan
            - [ ] T4 — The list
            - [ ] T5 — STATUS
            - [ ] T6 — The checkouts
            ## DONE (observable, all required)
            - the facts are read
            ## Found while doing
            -
        """.trimIndent() + "\n"
    }
}
