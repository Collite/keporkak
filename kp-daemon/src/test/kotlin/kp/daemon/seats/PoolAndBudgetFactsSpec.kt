package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kp.daemon.FakeSeating
import kp.daemon.TestOffice
import kp.daemon.checkRules
import kp.daemon.daemonLoad
import kp.daemon.hero.Hero
import kp.daemon.refusedBy
import kp.daemon.rewrite
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.Status
import kp.model.Fact
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * KP/P3/S3.13 T1 — the pool's facts and the budgets' (contracts.md §4.4, Amendment 44; Bora reads these). The seams: the
 * daemon's fact source over a fixture pool and a folded fixture log, asked as the mailroom asks ([PoolAsked]); the driver
 * in front of S2.1's fake agent, whose `usage_update` and turn's end carry what the pinned adapter's do ([SeatsOffice],
 * the thin thread as `just rehearse thin-thread --seats fake` plays it); `check-rules.py` and the daemon's load of a pool;
 * and the daemon on a fixture office ([PoolOffice]). The fixture roster and budgets are in PoolSupport.kt: lines 0
 * alpha low, 1 beta low, 2 beta high, 3 acme omega high, 4 gamma high (reserved for the principal); two seats per office
 * and for claude, one for acme, two stages in flight per effort.
 */
class PoolAndBudgetFactsSpec : StringSpec({
    "the roster's ranks for every line of a fixture roster — the first, the last, a model on two efforts, a reserved model — named by seat and by role in a stage; a role with no seat and a model in no line are unknown, saying which" {
        val asked = PoolAsked(log = FixtureLog(listOf(
            SeatAt("coder-1", "coder", S31, "junior", "busy"), // junior → line 0, the first
            SeatAt("reviewer-1", "reviewer", S31, null, "idle"), // ≥ the coder's, at least the floor → line 1, beta low
            SeatAt("teamlead-1", "teamlead", S31, null, "idle"), // senior M → line 2, beta high
            SeatAt("principal-1", "principal", null, null, "idle"), // role_models → line 4, the last, reserved
        )), live = mapOf("coder-2" to ("claude-delta-1" to "low")))

        asked.ask("roster.rank", "of" to "coder-1") shouldBe 0
        asked.ask("roster.model_rank", "of" to "coder-1") shouldBe 0
        asked.ask("roster.rank", "of" to "reviewer", stage = S31) shouldBe 1
        asked.ask("roster.rank", "of" to "teamlead-1") shouldBe 2
        asked.ask("roster.model_rank", "of" to "teamlead", stage = S31) shouldBe 1
        asked.ask("roster.rank", "seat" to "principal-1") shouldBe 4
        asked.ask("roster.model_rank", "of" to "principal-1") shouldBe 4
        asked.ask("roster.floor_rank") shouldBe 1
        asked.evidence("roster.rank", "of" to "principal-1") shouldBe "principal-1 runs claude-gamma-1 at high: pool/roster.yaml line 10 (order.4)"
        asked.unknown("roster.rank", "of" to "qa", stage = S31) shouldBe "no seat is bound to the role qa in KP/P3/S3.1"
        asked.unknown("roster.model_rank", "of" to "coder-2") shouldBe "coder-2 runs claude-delta-1 at low, which no line of pool/roster.yaml's order holds"
    }

    "the slots, for each of office, subscription and effort: one under the limit, at it and over it give slot_open true, false, false and slots_over 0, 0 and the number over; a gone seat holds its slot and a retired one does not; of two subscriptions and of two efforts one full closes the slot only where no parameter and no stage names the other" {
        val retired = SeatAt("reviewer-9", "reviewer", S31, null, "retired")
        val under = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "junior", "busy"), retired), stages = mapOf(S31 to "building")))
        val at = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "junior", "busy"), SeatAt("coder-2", "coder", S32, "junior", "gone"), retired),
            stages = mapOf(S31 to "building", S32 to "in_review")))
        val over = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "junior", "busy"), SeatAt("coder-2", "coder", S32, "junior", "gone"),
            SeatAt("coder-3", "coder", S33, "junior", "provisioned"), retired), stages = mapOf(S31 to "building", S32 to "in_review", S33 to "assigned")))
        val all = listOf(under, at, over)

        all.map { it.ask("budget.slot_open", "dimension" to "office") } shouldBe listOf(true, false, false)
        all.map { it.ask("budget.slots_over", "dimension" to "office") } shouldBe listOf(0, 0, 1)
        all.map { it.ask("budget.slot_open", "dimension" to "subscription") } shouldBe listOf(true, false, false)
        all.map { it.ask("budget.slots_over", "dimension" to "subscription") } shouldBe listOf(0, 0, 1)
        all.map { it.ask("budget.slot_open", "dimension" to "effort", stage = S31) } shouldBe listOf(true, false, false)
        all.map { it.ask("budget.slots_over", "dimension" to "effort", stage = S31) } shouldBe listOf(0, 0, 1)
        over.evidence("budget.slots_over", "dimension" to "office") shouldBe
            "office panda: in use 3 — coder-1 busy, coder-2 gone, coder-3 provisioned — of pool/budgets.yaml line 2 (slots.per_office.panda: 2)"

        // claude is full and acme is not; KP is full and TOY is not.
        over.ask("budget.slot_open", "dimension" to "subscription", "subscription" to "acme") shouldBe true
        over.ask("budget.slot_open", "dimension" to "effort", stage = "TOY/P0/S0.2") shouldBe true
        over.ask("budget.slot_open", "dimension" to "effort", "effort" to "TOY", stage = S31) shouldBe true
        over.ask("budget.slot_open", "dimension" to "effort") shouldBe false
        over.ask("budget.slots_over", "dimension" to "effort") shouldBe 1
    }

    "a turn's count is the weighted sum of its four parts, a missing part zero, and its end leaves one `fact` record whether it ended by the agent's answer, a cancel, a kill or its process gone — the last three marked partial; the watch's reports of the same turn are not summed" {
        // The thin thread, its coder's first turn reporting 100 input, 10 output, 1 000 cache write, 10 000 cache read; every other turn none.
        val scratch = Files.createTempDirectory("kp-s3.13-scratch-")
        val coder = Hero.walkFile("thin-thread").resolveSibling("thin-thread/fake/coder-1.json").readText()
            .replaceFirst(""""usage": {"input": 1000, "output": 100, "total": 1100}""", """"usage": {"input": 100, "output": 10, "cache_write": 1000, "cache_read": 10000, "total": 11110}""")
            .replace(""", "usage": {"input": 1000, "output": 100, "total": 1100}""", "")
        val hero = Hero("thin-thread", fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = "kp-no-tmux-here", panes = "kp-s3.13", evidence = scratch,
            scripts = mapOf("coder-1" to coder, "reviewer-1" to """{"turns": [[{"do": "end"}]]}""")))
        try { hero.rest() } finally { hero.close() }
        val turns = hero.office.counted().filterIsInstance<Fact>().filter { it.fact == "seat.usage" }

        turns.groupingBy { it.subject }.eachCount() shouldBe mapOf("coder-1, turn" to 4, "reviewer-1, turn" to 1)
        turns.first().value.toString() shouldBe "2400"
        turns.first().evidence shouldBe "turn ended: end_turn · input 100, output 10, cache write 1000, cache read 10000 · weighted 2400 by input 1, output 5, cache write 1.25, cache read 0.1 · subscription claude"
        turns.drop(1).map { it.value.toString() } shouldBe listOf("0", "0", "0", "0")
        scratch.listDirectoryEntries("thin-thread-*.md").single().readText() shouldContain "the seats used 2400 of the walk's 1500000"

        // In the seats' walk: a turn answered, one cancelled, one killed by the watchdog, one whose process ends.
        val holding = """[{"do": "hold", "until": "${SeatsOffice.holdPath("never")}"}]"""
        // A re-seated agent's replay holds: what it would use is no part of this sentence.
        SeatsOffice(scripts = mapOf("coder-1" to """{"turns": [[{"do": "end", "usage": {"input": 100, "output": 10, "cache_write": 1000, "cache_read": 10000, "total": 11110}}], $holding, $holding], "loaded_turns": [$holding]}""",
            "coder-2" to """{"turns": [$holding], "loaded_turns": [$holding]}""")).use { office ->
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("coder-1's first turn recorded") { office.usages("coder-1").size == 1 }
            office.seats.facts { _, _ -> null }.observe(FactRef("seat.usage", mapOf("seat" to Datum.Text("coder-1"), "window" to Datum.Text("turn"))), null) shouldBe Datum.of(2400)
            office.seats.facts { _, _ -> null }.observe(FactRef("seat.usage", mapOf("seat" to Datum.Text("coder-1"), "window" to Datum.Text("session"))), null) shouldBe Datum.of(2400)
            office.send("principal", "triage", """{"outcome": "issue", "found": "1", "since": "m-panda-000001"}""")
            office.until("coder-1 in its second turn") { office.prompts("coder-1").size == 2 }
            office.send("pool", "pause", """{"seat": "coder-1", "cause": "frozen"}""", stage = office.stage("S2.5"))
            office.until("the cancelled turn recorded") { office.usages("coder-1").size == 2 }
            office.until("coder-1 in its third turn, on the pause") { office.prompts("coder-1").size == 3 }
            office.clock.now = office.clock.now.plus(Duration.ofMinutes(6))
            office.until("the killed turn recorded") { office.usages("coder-1").size == 3 }
            office.send("principal", "triage", """{"outcome": "issue", "found": "2", "since": "m-panda-000001"}""", stage = office.stage("S2.6"))
            office.until("coder-2 in a turn") { office.prompts("coder-2").isNotEmpty() }
            office.seats.end("coder-2")
            office.until("coder-2's turn recorded") { office.usages("coder-2").size == 1 }

            office.usages("coder-1").map { it.value.toString() } shouldBe listOf("2400", "0", "0")
            office.usages("coder-1")[1].evidence!! shouldStartWith "turn ended: cancelled, partial · "
            office.usages("coder-1")[2].evidence!! shouldStartWith "turn ended: no answer, partial — "
            office.usages("coder-2").single().evidence!! shouldStartWith "turn ended: no answer, partial — "
        }
    }

    "budget.consumption is the fullest window the vendor reported, a named window when one is asked, and unknown before any report and past a window's reset" {
        // The vendor's words, as the pinned adapter passes them beside `usage_update` (`_meta["_claude/rateLimit"]`): resets at 12:00 and on the 21st.
        val five = """{"status": "allowed", "rateLimitType": "five_hour", "utilization": 0.42, "resetsAt": 1795003200}"""
        val week = """{"status": "allowed_warning", "rateLimitType": "seven_day", "utilization": 0.81, "resetsAt": 1795219200}"""
        SeatsOffice(scripts = mapOf("coder-1" to """{"turns": [[{"do": "usage", "used": 1000, "size": 200000, "rate_limit": $five},
            {"do": "usage", "used": 2000, "size": 200000, "rate_limit": $week}, {"do": "end"}]]}""")).use { office ->
            val pool = PoolFacts("panda").also { it.reading(office.office, office.seats) }
            fun consumption(window: String): Any? = plain(pool.observe(FactRef("budget.consumption", mapOf("subscription" to Datum.Text("claude"), "window" to Datum.Text(window))), null))

            consumption("subscription") shouldBe null
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("coder-1's turn recorded") { office.usages("coder-1").size == 1 }
            consumption("subscription") shouldBe BigDecimal("0.81")
            consumption("five_hour") shouldBe BigDecimal("0.42")
            consumption("seven_day") shouldBe BigDecimal("0.81")
            office.clock.now = Instant.parse("2026-11-18T12:00:01Z")
            consumption("five_hour") shouldBe null
            consumption("subscription") shouldBe BigDecimal("0.81")
            office.clock.now = Instant.parse("2026-11-21T00:00:01Z")
            consumption("subscription") shouldBe null
        }
    }

    "budget.vs_daily_median over days with turns only: one day fewer than median.least is unknown; a turn ending at 23:59:59 and one at 00:00:00 UTC count for different days" {
        val turns = listOf(
            FixtureLog.TurnEnd("coder-1", "2026-11-12T10:00:00Z", 3000, ended("claude")),
            FixtureLog.TurnEnd("coder-1", "2026-11-18T23:59:59Z", 1000, ended("claude")),
            FixtureLog.TurnEnd("coder-1", "2026-11-19T00:00:00Z", 5000, ended("claude")),
            FixtureLog.TurnEnd("qa-1", "2026-11-19T12:00:00Z", 90000, ended("acme")),
            FixtureLog.TurnEnd("coder-1", "2026-11-20T08:00:00Z", 12000, ended("claude")),
            // The watch's report of a turn in flight, not its end: not summed.
            FixtureLog.TurnEnd("coder-1", "2026-11-20T08:30:00Z", 12000, "the last turn ended at 2026-11-20T08:00:00Z: input 12000, output 0, total 12000"),
        )
        // Days with a turn before the 20th: the 12th (3 000), the 18th (1 000), the 19th (5 000) — the median 3 000; today 12 000.
        PoolAsked(log = FixtureLog(turns = turns)).ask("budget.vs_daily_median", "subscription" to "claude") shouldBe 4
        PoolAsked(log = FixtureLog(turns = turns)).ask("budget.vs_daily_median", "subscription" to "acme") shouldBe null
        PoolAsked(log = FixtureLog(turns = turns.drop(1))).unknown("budget.vs_daily_median", "subscription" to "claude") shouldBe
            "claude has turns on 2 days before 2026-11-20 (2026-11-18, 2026-11-19); the median needs 3 (pool/budgets.yaml line 7, usage.median.least)"
    }

    "a daemon on a fixture office with its limit of seats in use refuses a session-report, naming SEAT-01 and budget.slot_open, and with one seat retired delivers it; an assignment-proposal whose slots are open and whose seat can be had is delivered with each of T-01's facts in its observed — none unknown; a limit lowered under the seats in use is read at the next ask, with no restart" {
        PoolOffice(FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "junior", "idle")))).use { office ->
            office.send("teamlead", "assignment-proposal", """{"role": "coder", "level": "junior", "seat": "coder-2"}""").status shouldBe Status.DELIVERED
            office.observedOf("assignment-proposal").filterValues { it == """{"unknown":"not supplied"}""" } shouldBe emptyMap()
            office.observedOf("assignment-proposal").keys shouldContain "budget.slot_open(dimension=effort)"
            val first = office.send("teamlead", "session-request", """{"action": "add", "role": "reviewer", "seat": "reviewer-1"}""").id
            val second = office.send("teamlead", "session-request", """{"action": "add", "role": "coder", "seat": "coder-3"}""").id
            office.send("pool", "session-report", """{"seat": "reviewer-1", "role": "reviewer"}""", re = first).status shouldBe Status.DELIVERED

            // Two seats in use, two the limit: full.
            office.send("pool", "session-report", """{"seat": "coder-3", "role": "coder"}""", re = second) should refusedBy("SEAT-01", """{"fact":"budget.slot_open","dimension":"office"}""")
            office.budgets(BUDGETS.replace("per_office: {panda: 2, dsk: 2}", "per_office: {panda: 1, dsk: 2}"))
            office.ask("budget.slot_open", "dimension" to "office") shouldBe false
            office.ask("budget.slots_over", "dimension" to "office") shouldBe 1
            office.budgets(BUDGETS)

            // The Team Lead asks for coder-1's removal: POOL-8 retires it, and the report finds its slot.
            office.send("teamlead", "session-request", """{"action": "remove", "seat": "coder-1"}""")
            office.office.state.machine("seat", "coder-1")!!.state shouldBe "retired"
            office.send("pool", "session-report", """{"seat": "coder-3", "role": "coder"}""", re = second).status shouldBe Status.DELIVERED
        }
    }

    "each of the nine, asked with a parameter the pool does not know — an office, a subscription, a dimension, a seat — is unknown with what was asked; none throws out of the fact source" {
        val asked = PoolAsked(log = FixtureLog(listOf(SeatAt("coder-1", "coder", S31, "junior", "busy"))))

        asked.unknown("roster.rank", "of" to "nobody-9") shouldBe "no seat nobody-9 is known to the log or to the seats"
        asked.unknown("roster.model_rank", "seat" to "nobody-9") shouldBe "no seat nobody-9 is known to the log or to the seats"
        asked.unknown("pool.seat_available", "role" to "nobody", "level" to "junior") shouldBe "no persona declares the role nobody"
        asked.unknown("pool.seat_available", "role" to "coder", "level" to "expert") shouldBe "pool/roster.yaml has no level expert"
        asked.unknown("office.computer_use", "office" to "mac") shouldBe "pool/offices.yaml names no office mac"
        asked.unknown("budget.slot_open", "dimension" to "planet") shouldBe "no dimension planet: office, subscription or effort"
        asked.unknown("budget.slots_over", "dimension" to "subscription", "subscription" to "other") shouldBe "pool/budgets.yaml gives the subscription other no limit"
        asked.unknown("budget.consumption", "subscription" to "other", "window" to "five_hour") shouldBe "pool/roster.yaml names no subscription other"
        asked.unknown("budget.vs_daily_median", "subscription" to "other") shouldBe "pool/roster.yaml names no subscription other"
        // With no parameter at all: the floor's rank, and this office's computer use; every other is unknown.
        PoolFacts.ANSWERED.map { name -> asked.facts.observe(FactRef(name, emptyMap()), null) }.filterNot { it == null } shouldBe listOf(Datum.of(1), Datum.Text("off"))
    }

    "check-rules.py and the daemon's load each refuse a budgets.yaml without usage:, naming it; master's pool passes both with the approved block" {
        val without = TestOffice.copyOfTheRules().also { rules -> rules.resolve("pool/budgets.yaml").rewrite { it.substringBefore("# how a turn's tokens are counted") } }
        checkRules(without) shouldContain "pool/budgets.yaml: usage must say how a turn's tokens are counted: its weights and its median"
        daemonLoad(without) shouldContain "pool/budgets.yaml: usage must say how a turn's tokens are counted: its weights and its median"
        checkRules(TestOffice.copyOfTheRules()) shouldBe emptyList()
        daemonLoad(TestOffice.copyOfTheRules()) shouldBe emptyList()
    }
})

private const val S31 = "KP/P3/S3.1"
private const val S32 = "KP/P3/S3.2"
private const val S33 = "KP/P3/S3.3"

/** A turn's end as the daemon's record says it, of a seat of [subscription]. */
private fun ended(subscription: String) =
    "turn ended: end_turn · input 1, output 0, cache write 0, cache read 0 · weighted 1 by input 1, output 5, cache write 1.25, cache read 0.1 · subscription $subscription"

/** The turn-end records of [seat] on the office's log, in order. */
private fun SeatsOffice.usages(seat: String): List<Fact> = lines().filterIsInstance<Fact>().filter { it.fact == "seat.usage" && it.subject == "$seat, turn" }
