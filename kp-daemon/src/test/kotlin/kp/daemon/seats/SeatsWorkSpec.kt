package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.seats.SeatsOffice.Companion.text
import kp.mailroom.Status
import kp.model.Fact
import kp.model.Message
import java.time.Duration
import kotlin.io.path.deleteRecursively

/**
 * S2.4 T2, T4, T6, T7 — what the seats do beside T1's sentences, through the same seam: a start that cannot be made, the
 * reserved model, the driver's facts as they are reported, the watch, compaction as the probe showed, and the panes.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SeatsWorkSpec : StringSpec({
    "a seat is not started when its stage's working directory does not exist: creating it is worktree.create's" {
        SeatsOffice().use { office ->
            office.project.resolve("office/worktrees/kp-p2-s2.4").deleteRecursively()

            office.player.through("reviewer-report")
            office.until("the start failed") { office.ops("seat.start").any { it.phase == "failed" } }

            office.ops("seat.start").single { it.phase == "failed" }.reason shouldBe
                "the working directory ${office.project.resolve("office/worktrees/kp-p2-s2.4")} does not exist: creating it is worktree.create's"
            office.received("reviewer-1") shouldBe emptyList()
        }
    }

    "a fixture roster that reserves a model refuses a coder on it with roster.model_allowed; under one that does not, the coder is started" {
        val reserving = fixtureRoot { roster ->
            roster.replace("  claude-fable-5-2: [principal]", "  claude-fable-5-2: [principal]\n  claude-opus-5-5: [principal]")
                .lines().joinToString("\n") { line -> if (Regex("^    (S|M|L|XL): ").containsMatchIn(line)) line.replace("claude-opus-5-5", "claude-sonnet-5-5") else line }
        }
        fun request(office: SeatsOffice) = office.send("teamlead", "session-request", """{"action": "add", "role": "coder", "level": "senior", "seat": "coder-3"}""").id
        fun report(office: SeatsOffice, request: String) = office.send("pool", "session-report", """{"seat": "coder-3", "role": "coder"}""", re = request)

        SeatsOffice("reserved-seat", root = reserving).use { office ->
            val refused = report(office, request(office))

            listOf(refused.status, refused.rule) shouldBe listOf(Status.REFUSED, "SEAT-01")
            refused.failed!!.json() shouldContain "roster.model_allowed"
            office.ops("seat.start") shouldBe emptyList()
        }
        SeatsOffice("reserved-seat").use { office ->
            val delivered = report(office, request(office))

            listOf(delivered.status, delivered.transition) shouldBe listOf(Status.DELIVERED, "SEAT-01")
            office.until("coder-3 started") { office.ops("seat.start").any { it.phase == "ok" } }
            office.received("coder-3").filter { it.text("event") == "session/set_config_option" }.map { "${it.text("configId")} ${it.text("value")}" } shouldContain
                "model claude-opus-5-5"
        }
    }

    "the watch reports the silence, the open tool call, the context and the usage of each seat with a turn in flight, every watch.every; the robots' rules compare, and a compaction waits for the turn's end" {
        val turn = """{"turns": [[{"do": "tool", "id": "t1", "title": "build", "name": "Bash", "input": {"command": "just build"}},
            {"do": "usage", "used": 800, "size": 1000}, {"do": "hold", "until": "${SeatsOffice.holdPath("watched")}"}]]}"""
        SeatsOffice(scripts = mapOf("coder-1" to turn)).use { office ->
            office.seated.watchEvery() shouldBe Duration.ofSeconds(30)
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("coder-1 holding its turn") { office.received("coder-1").any { it.text("event") == "hold" } }
            // The driver has taken the tool call and the context from the wire: what the watch will read.
            val observed = office.seats.facts { _, _ -> null }
            fun seen(fact: String) = observed.observe(kp.engine.FactRef(fact, mapOf("seat" to kp.engine.Datum.Text("coder-1"))), office.stage())
            office.until("the tool call and the context observed") { seen("seat.context_used") != null }
            // 61 minutes on the injected clock: the watch reports at the `watch.every` it has passed (S2.9 T5).
            office.clock.now = office.clock.now.plus(Duration.ofMinutes(61))
            office.until("the watch's report judged") { office.lines().filterIsInstance<Fact>().size == 2 }

            // What triggered something was written: the context past its health threshold (POOL-3), the tool call open past its watch (POOL-9).
            val facts = office.lines().filterIsInstance<Fact>()
            facts.map { "${it.fact} ${it.subject} ${it.value}" } shouldBe listOf("seat.tool_call_open_for coder-1 \"3660s\"", "seat.context_used coder-1 0.8")
            office.lines().filterIsInstance<Message>().single { it.kind == "compact" }.by shouldBe "robot:POOL-3"
            office.ops("seat.wake").single { it.with?.text("mode") == "report" }.with!!.text("rule") shouldBe "POOL-9"
            // Silent too, but with a tool call open: no kill. The idle coder-2 has no turn in flight, and is not watched.
            office.lines().filterIsInstance<Message>().none { it.kind == "kill" } shouldBe true
            facts.none { it.subject == "coder-2" } shouldBe true

            // The compaction waits for the turn in flight; when it ends, the adapter's `/compact` is the seat's next prompt.
            office.ops("seat.compact").map { it.phase } shouldBe listOf("requested")
            office.release("watched")
            office.until("the compaction done") { office.ops("seat.compact").any { it.phase != "requested" } }
            office.ops("seat.compact").map { it.phase } shouldBe listOf("requested", "ok")
            // The compaction is the seat's next prompt once its turn ended — before the `compact` message itself wakes it.
            office.prompts("coder-1")[1] shouldBe Seats.COMPACT
        }
    }

    "a protocol that closes while its process lives is reported closed; a process that ends is reported gone, once" {
        SeatsOffice(scripts = mapOf("coder-1" to """{"turns": [[{"do": "hangup"}]]}""")).use { office ->
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("coder-1's close reported") { office.lines().any { it is Fact && it.fact == "seat.protocol_closed" && it.subject == "coder-1" } }

            office.agents.die("coder-2") shouldBe true
            office.until("coder-2's end reported") { office.lines().any { it is Fact && it.subject == "coder-2" } }
            office.until("coder-2 re-seated") { office.ops("seat.reseat").any { it.phase == "ok" && it.result?.text("seat") == "coder-2" } }

            office.lines().filterIsInstance<Fact>().filter { it.subject == "coder-1" }.map { it.fact } shouldBe listOf("seat.protocol_closed")
            // A process that ends closes its protocol too: its two reports come in either order (S2.9 T3), and the first is the one event.
            office.lines().filterIsInstance<Fact>().filter { it.subject == "coder-2" }.map { it.fact }.single() shouldBeIn listOf("seat.process_gone", "seat.protocol_closed")
        }
    }

    "a tmux session has a window per live seat, named after it, tailing its transcript: made when the seat starts, closed when it is gone or retired" {
        SeatsOffice().use { office ->
            val transcripts = office.project.resolve("office/seats/_transcripts")
            office.tmuxCalls().filter { it.startsWith("new-") } shouldBe listOf(
                "new-session -d -s ${SeatsOffice.PANES} -n coder-1 tail -n +1 -F '$transcripts/coder-1.log'",
                "new-window -d -t ${SeatsOffice.PANES}: -n coder-2 tail -n +1 -F '$transcripts/coder-2.log'",
            )
            office.seats.panes.windows() shouldBe listOf("coder-1", "coder-2")

            office.agents.die("coder-1") shouldBe true
            office.until("coder-1 re-seated") { office.ops("seat.reseat").any { it.phase == "ok" } }

            office.tmuxCalls() shouldContain "kill-window -t ${SeatsOffice.PANES}:coder-1"
            office.tmuxCalls().last { it.startsWith("new-") } shouldStartWith "new-window -d -t ${SeatsOffice.PANES}: -n coder-1 "
            office.seats.panes.windows() shouldBe listOf("coder-2", "coder-1")
        }
    }

    "an office without tmux is said so once, and no operation fails for it" {
        SeatsOffice(tmux = "/nonexistent/tmux").use { office ->
            office.player.through("reviewer-report")
            office.until("reviewer-1 started") { office.ops("seat.start").any { it.phase != "requested" } }

            office.ops("seat.start").map { it.phase } shouldBe listOf("requested", "ok")
            office.said.filter { "tmux" in it } shouldBe listOf("panes: no tmux on this office (`/nonexistent/tmux`): seats run without panes")
            office.seats.live() shouldBe setOf("coder-1", "coder-2", "reviewer-1")
        }
    }
})
