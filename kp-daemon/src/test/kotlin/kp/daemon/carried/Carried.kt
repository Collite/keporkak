package kp.daemon.carried

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kp.daemon.FakeSeating
import kp.daemon.MovingClock
import kp.daemon.Office
import kp.daemon.Ran
import kp.daemon.Rehearsal
import kp.daemon.SimulatedPeers
import kp.daemon.checkout
import kp.model.Envelope
import kp.model.Message
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The walk S3.5 writes, in the checkout's `rules/walks/`. */
val FIRST_CARRIED_STAGE: Path = checkout.resolve("rules/walks/first-carried-stage.yaml")

/**
 * `just rehearse first-carried-stage --seats fake --world office` on S3.5's fixture office ([FixtureOffice]), as T1's specs
 * play it: the real mailroom, seat API, driver, facts and operations; S2.1's fake agent as both seats, following
 * [coder]'s and [reviewer]'s scripts — by default [FakeScripts]' — Bora's approval the real `kp approve` on the daemon's
 * socket, as root. [clock] is the wall clock, moved on as a spec lets time pass. A rehearsal is started with [start] and
 * may be started again on the same office: each start is one run of the command.
 */
class Carried(
    val office: FixtureOffice = FixtureOffice(),
    coder: String? = null,
    reviewer: String? = null,
    val walk: Path = FIRST_CARRIED_STAGE,
) : AutoCloseable {
    val clock = MovingClock()
    val peers = SimulatedPeers()
    val said = CopyOnWriteArrayList<String>()
    val markers: Path = office.folder.resolve("markers").createDirectories()
    val evidence: Path = office.folder.resolve("evidence")
    private val scripts = mapOf(
        "coder-1" to (coder ?: FakeScripts.coder(office, markers)),
        "reviewer-1" to (reviewer ?: FakeScripts.reviewer(office, markers)),
    )
    private val started = CopyOnWriteArrayList<Rehearsal>()
    val rehearsal: Rehearsal get() = started.last()

    /** One run of the command on the office, cut after [cutAfter] when the spec says; Bora answers each step of his. */
    fun start(cutAfter: Int? = null): Rehearsal {
        val seating = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = office.tmux.toString(), panes = "kp-carried", scripts = scripts, evidence = evidence)
        return Rehearsal.onTheOffice(office.root, walk, FIXTURE_OFFICE, office.project, office.socket, peers, seating, api = office.apps.api, clock = clock, cutAfter = cutAfter) { said += it }
            .also { started += it; bora(it) }
    }

    /** Plays the walk, cut after [cutAfter] when the spec says, to its end; the daemon is then closed. Whether it passed. */
    fun play(cutAfter: Int? = null, seconds: Long = 120): Boolean? {
        val rehearsal = start(cutAfter)
        check(rehearsal.awaitPlayed(seconds * 1000)) { "the walk was not played in ${seconds}s:\n" + said.joinToString("\n") }
        val passed = rehearsal.passed
        rehearsal.close()
        return passed
    }

    /** Bora at the command line, on a thread of his own: each time the walk waits for him, `sudo kp approve <ticket>`. */
    private fun bora(rehearsal: Rehearsal) {
        Thread.ofPlatform().daemon(true).name("kp-carried-bora").start {
            runCatching {
                while (true) {
                    val kind = rehearsal.awaitBora(600_000) ?: break
                    check(kind == "approval") { "the walk waits for Bora's $kind" }
                    val ticket = rehearsal.office.state.openTickets().keys.max()
                    kp("approve", ticket.toString(), root = true)
                }
            }
        }
    }

    /** `scripts/kp` with [args] on the daemon's socket; with [root], as `sudo kp …`, simulated. */
    fun kp(vararg args: String, root: Boolean = false): Ran {
        synchronized(peers) {
            peers.root = root
            try {
                val process = ProcessBuilder(listOf(checkout.resolve("scripts/kp").toString()) + args).redirectErrorStream(true)
                    .apply { environment()["KP_SOCKET"] = office.socket.toString() }.start()
                val text = process.inputStream.bufferedReader().readText()
                check(process.waitFor(60, TimeUnit.SECONDS)) { "kp ${args.joinToString(" ")} did not end" }
                return Ran(process.exitValue(), text)
            } finally {
                peers.root = false
            }
        }
    }

    /** The office's log as it counts, read from disk. */
    fun lines(): List<Envelope> = kp.daemon.OnTheOffice.lines(office.project, FIXTURE_OFFICE)

    val daemonOffice: Office get() = rehearsal.office

    /**
     * Every fact the judgements of the stage's messages and of its operations' results observed — `<fact>(<params>) = <value>`
     * as the record writes it — with the rules each judgement fired (its transitions', or the refusal's rule).
     */
    fun observedOnTheWay(): Map<String, List<String>> {
        val lines = lines()
        val ours = lines.filterIsInstance<Message>().filter { it.stage?.value == CARRIED }.map { it.id.value }.toSet()
        val read = java.util.TreeMap<String, MutableList<String>>()
        lines.filterIsInstance<kp.model.Record>().forEach { record ->
            val id = (record.json["id"] as? JsonPrimitive)?.content ?: (record.json["cause"] as? JsonPrimitive)?.content ?: return@forEach
            if (id !in ours) return@forEach
            val observed = record.json["observed"] as? JsonObject ?: return@forEach
            val fired = lines.filterIsInstance<kp.model.Transition>().filter { it.cause == id }.mapNotNull { it.rule }.ifEmpty { listOfNotNull((record.json["rule"] as? JsonPrimitive)?.content) }
            observed.forEach { (fact, value) -> read.getOrPut("$fact = $value") { ArrayList() }.addAll(fired.ifEmpty { listOf(record.json["event"].toString()) }) }
        }
        return read.mapValues { it.value.distinct() }
    }

    override fun close() {
        started.forEach { runCatching { it.close() } }
        office.close()
    }
}

/**
 * The fake seats of S3.5's fixture (S2.1's agent): what a coder and a reviewer do on the toy's S0.2 — their work done by a
 * shell script of their own in the fixture, each part of it once however often it runs, and each message sent once, so a
 * seat re-seated, or started again with the daemon, goes on where it was: each turn does what is left, once the seat was
 * told to work — the coder by its `assign`, the reviewer by its `review-bind`, in a prompt or replayed. The coder sends
 * `started`, works for [pause] seconds — the time its seat may be ended in — then commits and pushes on the stage's
 * branch, ticks its list with the commit, opens the pull request as its App and sends `review-request`. The reviewer
 * approves the pull request's head on GitHub as its App and sends `verdict` PASS on that head, through the seat API's
 * `send`. Every turn uses [usage].
 */
object FakeScripts {
    private const val TURNS = 8

    fun coder(office: FixtureOffice, markers: Path, pause: Int = 3, usage: Long = 1100): String {
        val work = office.folder.resolve("coder-work.sh").apply { writeText(coderWork(office)) }
        val assigned = markers.resolve("assigned")
        val turn = buildJsonArray {
            add(told("assign from teamlead", assigned))
            add(send(markers.resolve("started"), "started", buildJsonObject {}, assigned))
            add(buildJsonObject { put("do", "run"); put("seconds", pause); put("once", markers.resolve("pause").toString()); put("if_file", assigned.toString()) })
            add(buildJsonObject { put("do", "shell"); put("command", "sh '$work'"); put("if_file", assigned.toString()) })
            add(send(markers.resolve("review-request"), "review-request", buildJsonObject { put("attest", buildJsonArray { add(JsonPrimitive("done_lines_hold")) }) }, assigned))
            add(end(usage))
        }
        return script(turn, usage)
    }

    fun reviewer(office: FixtureOffice, markers: Path, usage: Long = 1100): String {
        val work = office.folder.resolve("reviewer-work.sh").apply { writeText(reviewerWork(office, markers.resolve("verdict"))) }
        val bound = markers.resolve("bound")
        val turn = buildJsonArray {
            add(told("review-bind from teamlead", bound))
            add(buildJsonObject { put("do", "shell"); put("command", "sh '$work'"); put("if_file", bound.toString()) })
            add(end(usage))
        }
        return script(turn, usage)
    }

    /** A script whose every turn — of a process started, and of one whose session was loaded — is [turn]: what is left of the work. */
    private fun script(turn: JsonArray, @Suppress("UNUSED_PARAMETER") usage: Long): String {
        val turns = JsonArray(List(TURNS) { turn })
        return buildJsonObject { put("turns", turns); put("loaded_turns", turns) }.toString()
    }

    /** What a seat keeps of being told [mail] — the kind and sender a prompt names it by — in [marker]: once told, it works. */
    private fun told(mail: String, marker: Path) = buildJsonObject {
        put("do", "shell"); put("command", "touch '$marker'"); put("if_prompt", mail)
    }

    private fun send(marker: Path, kind: String, args: JsonObject, when_: Path) = buildJsonObject {
        put("do", "mcp"); put("tool", "send"); put("once", marker.toString()); put("if_file", when_.toString())
        put("arguments", buildJsonObject { put("kind", kind); put("subject", kind); put("args", args) })
    }

    private fun end(usage: Long) = buildJsonObject {
        put("do", "end"); put("usage", buildJsonObject { put("input", usage - usage / 11); put("output", usage / 11); put("total", usage) })
    }

    /** The pull request's description: the six headings of the hand-off and a line under each (EV-1). */
    private const val BODY = "## Stage\\nTOY/P0/S0.2 Smoke\\n## What landed\\nthe version, said once\\n## DONE line\\n- the version is the build's\\n" +
        "## Verified\\nthe fixture's check run: green on the head\\n## Not in this stage\\nparsing and formatting\\n## Found while doing\\n-"

    private fun coderWork(office: FixtureOffice) = """
        #!/bin/sh
        # The fake coder's work on TOY/P0/S0.2 (S3.5's fixture): one commit on the stage's branch, pushed; the list ticked with it;
        # the pull request opened as the coder's App. Each part is done once, however often this runs.
        set -eu
        branch=${'$'}(git rev-parse --abbrev-ref HEAD)
        if [ -z "${'$'}(git log --format=%H origin/master..HEAD)" ]; then
          printf 'version=0.1.0\n' >> gradle.properties
          git add gradle.properties
          git commit -q -m 'S0.2: the version, said once, in gradle.properties'
        fi
        git push -q origin "HEAD:refs/heads/${'$'}branch"
        sha=${'$'}(git rev-parse HEAD)
        sed -i -e 's/^- \[ \] \(T[0-9]\)/- [x] \1/' -e "s/^      done${'$'}/      done ${'$'}sha/" '${office.list}'
        token=${'$'}(curl -sf -X POST -H "Authorization: Bearer ${'$'}KP_SESSION_TOKEN" "${'$'}KP_HOOK_URL/hook/github-token" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
        if ! curl -sf -H "Authorization: Bearer ${'$'}token" "${office.apps.api}repos/Collite/kp-toy/pulls?head=Collite:${'$'}branch&state=all" | grep -q '"number"'; then
          curl -sf -X POST -H "Authorization: Bearer ${'$'}token" -H 'Content-Type: application/json' '${office.apps.api}repos/Collite/kp-toy/pulls' \
            -d "{\"title\":\"S0.2 Smoke\",\"head\":\"${'$'}branch\",\"base\":\"master\",\"body\":\"$BODY\"}" > /dev/null
        fi
        echo "the work of S0.2 is pushed at ${'$'}sha and its pull request is open"
    """.trimIndent() + "\n"

    private fun reviewerWork(office: FixtureOffice, sent: Path) = """
        #!/bin/sh
        # The fake reviewer's work on TOY/P0/S0.2 (S3.5's fixture): the pull request's head approved on GitHub as the reviewer's
        # App, then `verdict` PASS on that head through the seat API's `send` — each once, however often this runs.
        set -eu
        api='${office.apps.api}repos/Collite/kp-toy'
        token=${'$'}(curl -sf -X POST -H "Authorization: Bearer ${'$'}KP_SESSION_TOKEN" "${'$'}KP_HOOK_URL/hook/github-token" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
        pulls=${'$'}(curl -sf -H "Authorization: Bearer ${'$'}token" "${'$'}api/pulls?head=Collite:$CARRIED_BRANCH&state=open")
        number=${'$'}(printf '%s' "${'$'}pulls" | sed -n 's/^\[{"number":\([0-9]*\).*/\1/p')
        head=${'$'}(printf '%s' "${'$'}pulls" | sed -n 's/.*"head":{"ref":"[^"]*","sha":"\([0-9a-f]*\)".*/\1/p')
        [ -n "${'$'}number" ] && [ -n "${'$'}head" ] || { echo "no open pull request of $CARRIED_BRANCH"; exit 1; }
        case "${'$'}(curl -sf -H "Authorization: Bearer ${'$'}token" "${'$'}api/pulls/${'$'}number/reviews")" in
          *"\"commit_id\":\"${'$'}head\",\"state\":\"APPROVED\""*) ;;
          *) curl -sf -X POST -H "Authorization: Bearer ${'$'}token" -H 'Content-Type: application/json' "${'$'}api/pulls/${'$'}number/reviews" \
               -d "{\"commit_id\":\"${'$'}head\",\"event\":\"APPROVE\",\"body\":\"PASS\"}" > /dev/null ;;
        esac
        if [ ! -e '$sent' ]; then
          curl -sf -X POST -H "Authorization: Bearer ${'$'}KP_SESSION_TOKEN" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' "${'$'}KP_HOOK_URL/mcp" \
            -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"send\",\"arguments\":{\"kind\":\"verdict\",\"subject\":\"verdict\",\"args\":{\"verdict\":\"PASS\",\"sha\":\"${'$'}head\",\"blockers\":[],\"follow_ups\":[]}}}}" > /dev/null
          touch '$sent'
        fi
        echo "PASS on ${'$'}head of #${'$'}number"
    """.trimIndent() + "\n"
}
