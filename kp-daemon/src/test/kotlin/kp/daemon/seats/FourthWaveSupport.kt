@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.seats

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.daemon.GitHubWorld
import kp.daemon.Office
import kp.daemon.ProjectWorld
import kp.daemon.ServiceWorld
import kp.daemon.answered
import kp.daemon.checkout
import kp.daemon.git
import kp.daemon.hero.SetClock
import kp.daemon.json
import kp.daemon.repository
import kp.daemon.write
import kp.driver.claude.ClaudeAdapter
import kp.driver.home.CompiledAnswerer
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.Answer
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.mailroom.Sender
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Op
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.14 — the seam of AfterTheFourthWaveSpec: the daemon composed as the service composes it (`service` in
 * Main.kt) — its world the service's own fact sources, the project's and git's ([ProjectWorld]), GitHub's
 * ([GitHubWorld], on a GitHub no one answers) and the pool's and the seats' ([PoolFacts]), and no walk's table — with the
 * seats in front of the driver, S2.1's fake agent as every seat's process. The project is a throwaway one: KP's plan,
 * overview and STATUS, a list and a worktree for each stage of [stages], each moved to its state by a fixture log. A seat
 * is started as the rules start one ([started]): the Team Lead's `session-request`, the Pool Manager's `session-report`,
 * SEAT-01's `seat.start`. Every operation that is not a seat's ends `ok`. The pool is master's, but for what [roster] and
 * [budgets] change; its office is `panda`, the specs' office. A stage of [then] is moved on from its state to the one
 * named there, as a second fixture transition — S3.15: a stage paused from where it was; each of [seats] is on the log
 * as started, and in its state — S3.15: a seat retired.
 */
class ServiceOffice(
    stages: Map<String, String>,
    roster: (String) -> String = { it },
    budgets: (String) -> String = { it },
    then: Map<String, String> = emptyMap(),
    seats: List<SeatAt> = emptyList(),
) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "service-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, "plan.md", "## 2 · Stages\n\n| stage | after | goal |\n|---|---|---|\n" + stages.keys.joinToString("") { "| **${short(it)} A stage** | — | its goal |\n" })
        write(project, "tasks-overview.md", "- [ ] **P3**\n" + stages.keys.joinToString("") { "  - [ ] **${short(it)}** A stage\n" })
        stages.keys.forEach { stage -> write(project, "tasks-p3-s${short(stage).removePrefix("S")}-a-stage.md", LIST.replace("STAGE", short(stage))) }
        write(project, "STATUS.md", "# KP — status\n")
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "the stages, cut")
        FixtureLog(seats = seats, stages = stages, then = then).writeTo(project)
    }
    /** The office's four roots, beside the project: a worktree made for each stage, a Claude token file the fake agents need none of. */
    val roots = Roots(folder.resolve("worktrees"), folder.resolve("scratch"), folder.resolve("seats"), FakeAgents.secrets(folder.resolve("secrets"))).also { roots ->
        stages.keys.forEach { stage -> roots.worktrees.resolve(Seats.worktree(stage)!!).createDirectories() }
    }
    val root: Path = fixtureRoot(roster).also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\",")
            .replace(Regex("(?m)^      worktrees: .*$"), "      worktrees: \"${roots.worktrees}\"")
            .replace(Regex("(?m)^      scratch: .*$"), "      scratch: \"${roots.scratch}\"")
            .replace(Regex("(?m)^      seats: .*$"), "      seats: \"${roots.seats}\"")
            .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"${roots.secrets}\""))
        val pool = root.resolve("pool/budgets.yaml")
        pool.writeText(budgets(pool.readText()))
    }
    val clock = SetClock(Instant.parse("2026-11-25T09:00:00Z"))
    /** The daemon's journal: what each of its parts said. */
    val journal = CopyOnWriteArrayList<String>()
    val world = ServiceWorld(
        ProjectWorld.of(inForce(root), OFFICE, project) { journal += it },
        GitHubWorld.of(OFFICE, project, URI("http://127.0.0.1:9")) { journal += it },
        PoolFacts(OFFICE) { journal += it },
    )
    val agents = FakeAgents(System.getProperty("kp.driver.fake.classpath"), folder.resolve("fake"))
    private var driving: Seats? = null
    val seated: Seated = Seated.start(OFFICE, project, root, world, Operations { _, done -> done(OperationResult.Ok()) }, clock, "127.0.0.1:0",
        folded = { office -> world.reading(office); world.pool!!.reading(office, driving) }) { starting ->
        Seats(root, OFFICE, project, roots, agents, clock, Panes("kp-no-tmux-here", "kp-s3.14") { journal += it }, { journal += it }, starting) { listener, time ->
            ClaudeAdapter.driver(roots.secrets.resolve("claude-token"), roots.seats, time, CompiledAnswerer(roots.seats), listener)
        }.also { driving = it }
    }.also { it.start(null) }

    val office: Office get() = seated.office
    val seats: Seats get() = seated.seats

    /** What the mailroom asks the world: the office's facts — the seats' first, then the service's — about [stage]. */
    fun ask(fact: String, vararg params: Pair<String, String>, stage: String?): Any? =
        plain(seats.facts(world).observe(FactRef(fact, params.associate { (key, value) -> key to Datum.Text(value) }), stage))

    /** A message from [from] — a role's seat, or a robot's role — of [kind] about [stage] (none: about no stage), with [args] (JSON). */
    fun send(from: String, kind: String, args: String, stage: String?, re: String? = null): Answer = office.submit(Sender.Seat(from), json(
        """{"kind": "$kind", "subject": "$kind", "args": $args""" + (stage?.let { """, "stage": "$it"""" } ?: "") + (re?.let { """, "re": "$it"""" } ?: "") + "}",
    )).answered()

    /** The Team Lead asks for [seat] as [role] at [level], about [stage] — or about none, as a day's request: the request's id, once it is delivered. */
    fun request(seat: String, role: String, stage: String?, level: String? = null): String =
        send("teamlead", "session-request", """{"action": "add", "role": "$role", "seat": "$seat"""" + (level?.let { """, "level": "$it"""" } ?: "") + "}", stage)
            .also { check(it.status == kp.mailroom.Status.DELIVERED) { "the request for $seat was not delivered: $it" } }.id

    /** The Pool Manager's report on [seat] as [role], answering the request [re]: its answer. */
    fun report(seat: String, role: String, stage: String, re: String): Answer = send("pool", "session-report", """{"seat": "$seat", "role": "$role"}""", stage, re = re)

    /** [seat] started as the rules start one, and seated: its `seat.start` ended `ok`. */
    fun started(seat: String, role: String, stage: String, level: String? = null) {
        val report = report(seat, role, stage, request(seat, role, stage, level))
        check(report.status == kp.mailroom.Status.DELIVERED) { "the report on $seat was not delivered: $report" }
        until("$seat started") { ops("seat.start").any { it.phase == "ok" && it.result?.text("seat") == seat } }
    }

    /** The log as it counts, in `seq` order. */
    fun lines(): List<Envelope> = office.counted()

    /** The operations of [op] on the log. */
    fun ops(op: String): List<Op> = lines().filterIsInstance<Op>().filter { it.op == op }

    /** The ids of the messages of [kind] on the log, in order. */
    fun messages(kind: String): List<String> = lines().filterIsInstance<kp.model.Message>().filter { it.kind == kind }.map { it.id.value }

    /** The `delivered` record of message [id]. */
    fun delivered(id: String): Delivered = lines().filterIsInstance<Delivered>().single { it.id == id }

    /** What the judgement of message [id] observed: each fact with its value as JSON. */
    fun observed(id: String): Map<String, String> {
        val judged = lines().filterIsInstance<kp.model.Record>().first { record ->
            (record.json["id"] as? JsonPrimitive)?.content == id && (record.json["event"] as? JsonPrimitive)?.content in setOf("delivered", "refused")
        }
        return (judged.json["observed"] as? JsonObject).orEmpty().mapValues { (_, value) -> if (value is JsonPrimitive && !value.isString) value.content else value.toString() }
    }

    /** The texts [seat]'s fake agents were prompted with, in order, each with the process that received it. */
    fun prompts(seat: String): List<Pair<Long, String>> {
        var pid = 0L
        return agents.received(seat).mapNotNull { record ->
            when (record.text("event")) {
                "start" -> null.also { pid = (record["pid"] as JsonPrimitive).content.toLong() }
                "session/prompt" -> pid to record.text("text")!!
                else -> null
            }
        }
    }

    /** Waits until [done] holds, at most [seconds]: the daemon works on threads of its own. */
    fun until(what: String, seconds: Long = 30, done: () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000
        var version = office.changes.version()
        while (!done()) {
            if (System.nanoTime() > end) throw AssertionError("waited ${seconds}s for $what; the log:\n" + lines().joinToString("\n") { SeatsOffice.told(it) })
            version = office.changes.await(version, 200)
        }
    }

    /** The daemon stops — its seats die with it — and its folder goes; what a seat's process still writes as it ends may stay, under build/. */
    override fun close() {
        seated.close()
        office.close()
        runCatching { folder.deleteRecursively() }
    }

    companion object {
        const val OFFICE = "panda"

        private fun short(stage: String): String = stage.substringAfterLast('/')

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

        /** A stage's list, its fence complete. */
        private val LIST = """
            # KP-keporkak-P3 · STAGE — A stage            (PR: feat/kp-keporkak-p3-a-stage)
            rolls: none  ·  live: none  ·  touches: kp-daemon/

            ## Scope fence
            - Stop conditions: SC-1 another repo · SC-2 contracts.md
            ## Tasks
            - [ ] T1 — Specs
            - [ ] T2 — The work
            - [ ] T3 — More work
            - [ ] T4 — The rest
            ## DONE (observable, all required)
            - it is done
            ## Found while doing
            -
        """.trimIndent() + "\n"
    }
}
