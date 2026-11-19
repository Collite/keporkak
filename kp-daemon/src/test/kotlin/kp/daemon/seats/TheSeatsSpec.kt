package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.daemon.checkout
import kp.daemon.seats.SeatsOffice.Companion.text
import kp.model.Delivered
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kp.model.Read
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S2.4 T1 — the daemon drives real seats: through T1's seam ([SeatsOffice]) — the daemon as a rehearsal starts it, the
 * real mailroom, seat API and driver, S2.1's fake agent as every seat's process, the world's facts and the other
 * operations from the walk's table, an injected clock. Each spec is named as its sentence in the list.
 */
/** The two facts of a seat's end the driver reports (contracts.md §4.4). */
private val ENDS = setOf("seat.process_gone", "seat.protocol_closed")

class TheSeatsSpec : StringSpec({
    "a message to an idle seat is its next prompt and is read; to a busy seat it waits in the inbox, and the seat is prompted with it when its turn has ended" {
        val hold = mapOf("coder-1" to """{"turns": [[{"do": "hold", "until": "%s"}]]}""")
        SeatsOffice(scripts = hold.mapValues { it.value.format(SeatsOffice.holdPath("first-turn")) }).use { office ->
            val before = office.lines().last().seq.toLong()
            val stop = office.send("coder-1", "stop", """{"sc": "SC-3"}""").id
            val triage = office.send("principal", "triage", """{"outcome": "resume", "found": "1", "since": "$stop"}""", re = stop).id
            office.until("coder-1 woken with the triage") { office.lines().any { it is Read && it.seat == "coder-1" && triage in it.ids } }

            // Idle: the triage is its next prompt — the message, its `delivered` with `woke`, `seat.wake` requested and `ok`, then a `read`.
            office.told(before).filter { it.startsWith("triage") || it.startsWith("delivered") || "seat.wake" in it || it.startsWith("read coder-1") }
                .dropWhile { !it.startsWith("triage") }.take(5) shouldBe
                listOf("triage principal→coder", "delivered woke [coder-1]", "op seat.wake requested coder-1", "op seat.wake ok coder-1", "read coder-1")
            office.prompts("coder-1").single() shouldContain triage

            // Busy — its first turn holds: the second triage waits in its inbox, and no wake is asked for it.
            val again = office.send("coder-1", "stop", """{"sc": "SC-3"}""").id
            val second = office.send("principal", "triage", """{"outcome": "resume", "found": "2", "since": "$again"}""", re = again).id
            office.lines().filterIsInstance<Delivered>().single { it.id == second }.woke shouldBe null
            office.ops("seat.wake").map { it.cause } shouldNotContain second
            office.office.state.unread("coder-1") shouldBe listOf(second)

            // Its turn ends: it is prompted with the waiting message, which is then read.
            office.release("first-turn")
            office.until("coder-1 woken with the second triage") { office.office.state.unread("coder-1").isEmpty() }
            office.ops("seat.wake").filter { it.cause == second }.map { it.phase } shouldBe listOf("requested", "ok")
            office.prompts("coder-1").last() shouldContain second
        }
    }

    "a seat is started with the home, the model and the effort its role and the roster give it, and its token is live before its process is" {
        SeatsOffice().use { office ->
            // The coder is seated at the level its request names — junior M: the cheapest model, medium.
            office.player.through("coder-level")
            office.received("coder-1").filter { it.text("event") == "session/set_config_option" }.map { "${it.text("configId")} ${it.text("value")}" } shouldContainInOrder
                listOf("model claude-haiku-5-5", "effort medium")

            // A reviewer for that coder's stage: at or above the coder's line, its model at least the review floor.
            office.player.through("reviewer-report")
            office.until("reviewer-1 started") { office.ops("seat.start").any { it.phase == "ok" } }

            val started = office.ops("seat.start").single { it.phase == "ok" }
            started.result!!.keys shouldBe setOf("seat", "session")
            started.result!!.text("seat") shouldBe "reviewer-1"
            val reviewer = office.received("reviewer-1")
            reviewer.filter { it.text("event") == "session/set_config_option" }.map { "${it.text("configId")} ${it.text("value")}" } shouldContainInOrder
                listOf("mode default", "model claude-opus-5-5", "effort medium")
            val home = office.project.resolve("office/seats/reviewer-1")
            reviewer.first { it.text("event") == "start" }.text("CLAUDE_CONFIG_DIR") shouldBe home.toString()
            home.resolve("CLAUDE.md").readText() shouldBe checkout.resolve("renders/reviewer.md").readText()
            reviewer.first { it.text("event") == "session/new" }.text("cwd") shouldBe office.project.resolve("office/worktrees/kp-p2-s2.4").toString()

            // The process called the seat API's stop hook with its own token before it answered anything: the token was live.
            reviewer.map { it.text("event") }.take(2) shouldBe listOf("start", "probe")
            reviewer.single { it.text("event") == "probe" }.text("status") shouldBe "200"
        }
    }

    "a seat whose process dies is reported gone and comes back with its list, STATUS and unread mail, as often as the rules allow and no more" {
        val turns = (1..8).joinToString(", ") { """[{"do": "hold", "until": "%s"}]""" }
        SeatsOffice(scripts = mapOf("coder-1" to """{"turns": [$turns]}""".format(*Array(8) { SeatsOffice.holdPath("never") }))).use { office ->
            office.project.resolve("tasks-p2-s2.4-seats.md").writeText("# the list\n")
            office.project.resolve("STATUS.md").writeText("# STATUS\n")
            // Woken once, it holds its turn: each message after that waits unread until the seat dies.
            val first = office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""").id
            office.until("coder-1 busy") { office.prompts("coder-1").any { first in it } }

            val replays = (1..4).map { death ->
                val waiting = office.send("principal", "triage", """{"outcome": "issue", "found": "$death", "since": "m-panda-000001"}""").id
                // What it has not read when it dies: this triage, and the copy of the last re-seated it is sent.
                val unread = office.office.state.unread("coder-1")
                unread.last() shouldBe waiting
                val before = office.lines().last().seq.toLong()
                office.agents.die("coder-1") shouldBe true
                // Its two reports — the process gone, the protocol closed — come in either order (S2.9 T3): the first is written.
                office.until("the death of coder-1 judged") { office.lines().any { it.seq.toLong() > before && it is Fact && it.fact in ENDS } }
                if (death <= 3) {
                    office.until("coder-1 re-seated") { office.lines().any { it.seq.toLong() > before && it is Message && it.kind == "re-seated" } }
                    // The re-seat's one prompt replayed every one of them and marked them read; what is unread now came after it.
                    office.office.state.unread("coder-1").filter { it in unread } shouldBe emptyList()
                    office.unread("coder-1").map { it.substringBefore(' ') } shouldBe listOf("re-seated")
                    office.prompts("coder-1").last().also { prompt -> unread.forEach { prompt shouldContain it } }
                } else {
                    office.until("the pause at the cap") { office.lines().any { it.seq.toLong() > before && it is Message && it.kind == "pause" } }
                    null
                }
            }

            // Three re-seats — the rules' cap — then the fourth death pauses the seat, and nothing re-seats it.
            office.ops("seat.reseat").map { it.phase } shouldBe listOf("requested", "ok", "requested", "ok", "requested", "ok")
            office.office.state.counter(office.stage(), "seat_gone_events") shouldBe 3
            // Past the cap a dead seat is paused, not marked gone: either of its two reports pauses it (D-81's worked edit).
            office.lines().filterIsInstance<Message>().filter { it.kind == "pause" }.map { it.by }.filterNot { it in setOf("robot:POOL-11", "robot:POOL-13") } shouldBe emptyList()
            replays.filterNotNull().forEach { prompt ->
                prompt shouldContain "project:tasks-p2-s2.4-seats.md"
                prompt shouldContain "project:STATUS.md"
            }
        }
    }

    "after the daemon is started again, every seat it had is reported gone and comes back the same way" {
        val project = SeatsOffice().use { office ->
            office.office.state.machine("seat", "coder-1")!!.state shouldBe "ready"
            office.project
        }
        try {
            SeatsOffice(prepared = project).use { office ->
                office.until("both seats re-seated") { office.lines().filterIsInstance<Message>().count { it.kind == "re-seated" } == 2 }

                office.lines().filterIsInstance<Fact>().filter { it.fact == "seat.process_gone" }.map { it.subject }.sorted() shouldBe listOf("coder-1", "coder-2")
                office.ops("seat.reseat").filter { it.phase == "ok" }.mapNotNull { it.result?.text("seat") }.sorted() shouldBe listOf("coder-1", "coder-2")
                office.prompts("coder-1").last() shouldContain "re-seated"
                office.seats.live() shouldBe setOf("coder-1", "coder-2")
            }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    "`pause`, `kill` and `retire` reach the seat's process, and each leaves its `op` record" {
        val holding = """{"turns": [[{"do": "hold", "until": "%s"}], [{"do": "hold", "until": "%s"}]]}"""
        SeatsOffice(scripts = mapOf("coder-1" to holding.format(SeatsOffice.holdPath("never"), SeatsOffice.holdPath("never")))).use { office ->
            val first = office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""").id
            office.until("coder-1 in a turn") { office.prompts("coder-1").any { first in it } }

            // pause, about a frozen stage: its turn in flight is cancelled — and the seat, idle, is then woken with the pause.
            val pause = office.send("pool", "pause", """{"seat": "coder-1", "cause": "frozen"}""", stage = office.stage("S2.5")).id
            office.until("the cancel done") { office.ops("seat.cancel").any { it.phase != "requested" } }
            office.ops("seat.cancel").map { it.phase } shouldBe listOf("requested", "ok")
            office.received("coder-1").map { it.text("event") } shouldContain "cancel"
            office.until("coder-1 in its next turn, on the pause") { office.prompts("coder-1").any { pause in it } }

            // kill, by the watchdog: silent past its limit, no tool call open — its process ends.
            val pid = office.agents.pid("coder-1")!!
            office.clock.now = office.clock.now.plus(Duration.ofMinutes(6))
            office.seats.watch()
            office.until("the kill done") { office.ops("seat.kill").any { it.phase != "requested" } }
            office.ops("seat.kill").map { it.phase } shouldBe listOf("requested", "ok")
            office.lines().filterIsInstance<Message>().single { it.kind == "kill" }.by shouldBe "robot:POOL-2"
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false) shouldBe false

            // retire, of the idle coder of another stage: its session ends, its pane closes, its home stays.
            val retiring = office.agents.pid("coder-2")!!
            office.send("pool", "retire", """{"seat": "coder-2"}""", stage = office.stage("S2.6"))
            office.until("the retire done") { office.ops("seat.retire").any { it.phase != "requested" } }
            office.ops("seat.retire").map { it.phase } shouldBe listOf("requested", "ok")
            office.office.state.machine("seat", "coder-2")!!.state shouldBe "retired"
            ProcessHandle.of(retiring).map { it.isAlive }.orElse(false) shouldBe false
            office.project.resolve("office/seats/coder-2/CLAUDE.md").exists() shouldBe true
            office.tmuxCalls() shouldContain "kill-window -t ${SeatsOffice.PANES}:coder-2"
            office.lines().filterIsInstance<Fact>().filter { it.subject == "coder-2" } shouldBe emptyList()
        }
    }
})
