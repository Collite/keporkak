package kp.daemon.seats

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kp.daemon.json
import kp.daemon.repository
import kp.daemon.write
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.VendorReport
import kp.mailroom.Sender
import kp.model.Envelope
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.deleteExisting

/**
 * KP/P3/S3.4 T1 — the four facts the daemon reports when they change (contracts.md §4.4, Amendment 45). The seam: the
 * daemon on a fixture office whose world is the service's — fixture main checkouts, a fixture pool and log ([PoolOffice])
 * — with its reports told what the seats tell them: the watch, a turn's end, the vendor's report; and the fake agent's
 * vendor report through a seat. Each report is a `fact` record; each sentence names the rule that waits for it.
 */
class TheFactsReportedSpec : FreeSpec({
    val s31 = "KP/P3/S3.1"
    val s32 = "KP/P3/S3.2"

    "git.stray_changes, read every watch.every" - {
        "once per active stage; none while it stays; taken away and put back, again; DEP-7 sends one anomaly-stop per stage while Bora is away" {
            val keporkak = repository(Files.createTempDirectory("kp-s3.4-main-").resolve("keporkak"))
            val log = FixtureLog(stages = mapOf(s31 to "building", s32 to "building"),
                seats = listOf(SeatAt("coder-1", "coder", s31, "junior", "idle"), SeatAt("coder-2", "coder", s32, "junior", "idle")))
            PoolOffice(log, repos = mapOf("keporkak" to keporkak), reporting = true).use { office ->
                office.office.submit(Sender.Bora(signed = true), json("""{"kind": "away"}"""))
                val reports = office.reports!!
                reports.started()
                office.strays() shouldBe listOf("$s31 0", "$s32 0")

                write(keporkak, "stray.txt", "a stray file\n")
                var at = office.at()
                reports.watched()
                office.strays(at) shouldBe listOf("$s31 1", "$s32 1")
                office.anomalyStops(at) shouldBe listOf(s31, s32)
                office.records(at).filterIsInstance<Fact>().first().evidence shouldBe "keporkak: 1 changed path — stray.txt"

                at = office.at()
                reports.watched()
                office.strays(at) shouldBe emptyList()

                keporkak.resolve("stray.txt").deleteExisting()
                reports.watched()
                write(keporkak, "stray.txt", "a stray file, again\n")
                reports.watched()
                office.strays(at) shouldBe listOf("$s31 0", "$s32 0", "$s31 1", "$s32 1")
                office.anomalyStops(at) shouldBe listOf(s31, s32)
            }
        }
        "DEP-7's anomaly-stop for each stage names that stage's coder (the service answers `seat.name`: S3.14)" {
            val keporkak = repository(Files.createTempDirectory("kp-s3.4-main-").resolve("keporkak"))
            val log = FixtureLog(stages = mapOf(s31 to "building", s32 to "building"),
                seats = listOf(SeatAt("coder-1", "coder", s31, "junior", "idle"), SeatAt("coder-2", "coder", s32, "junior", "idle")))
            PoolOffice(log, repos = mapOf("keporkak" to keporkak), reporting = true).use { office ->
                office.office.submit(Sender.Bora(signed = true), json("""{"kind": "away"}"""))
                office.reports!!.started()
                write(keporkak, "stray.txt", "a stray file\n")
                office.reports.watched()
                office.records().filterIsInstance<Message>().filter { it.by == "robot:DEP-7" }
                    .map { "${it.stage?.value} ${(it.args?.get("seat") as? JsonPrimitive)?.contentOrNull}" } shouldBe listOf("$s31 coder-1", "$s32 coder-2")
            }
        }
        "none with no stage active" {
            val keporkak = repository(Files.createTempDirectory("kp-s3.4-main-").resolve("keporkak"))
            write(keporkak, "stray.txt", "a stray file\n")
            PoolOffice(FixtureLog(stages = mapOf(s31 to "paused")), repos = mapOf("keporkak" to keporkak), reporting = true).use { office ->
                office.reports!!.started()
                office.reports.watched()
                office.strays() shouldBe emptyList()
            }
        }
        "once for a stage that becomes active while it is above zero" {
            val keporkak = repository(Files.createTempDirectory("kp-s3.4-main-").resolve("keporkak"))
            write(keporkak, "stray.txt", "a stray file\n")
            // S3.1 was building and is paused — its seat died — and S3.2 is building.
            val log = FixtureLog(stages = mapOf(s31 to "building", s32 to "building"), then = mapOf(s31 to "paused"))
            PoolOffice(log, repos = mapOf("keporkak" to keporkak), reporting = true).use { office ->
                office.reports!!.started()
                office.strays() shouldBe listOf("$s32 1")
                val at = office.at()
                office.reports.watched()
                office.strays(at) shouldBe emptyList()
                // Its seat re-seated, S3.1 is building again (T-60): at the next watch it is reported, and S3.2 is not again.
                office.send("pool", "re-seated", """{"seat": "coder-1"}""")
                office.reports.watched()
                office.strays(at) shouldBe listOf("$s31 1")
            }
        }
    }

    "budget.slots_over, after a judgement that moved a seat or a stage and after a reload, for each dimension that changed; POOL-16 wakes once for it" {
        val log = FixtureLog(seats = listOf(SeatAt("coder-1", "coder", s31, "junior", "idle"), SeatAt("reviewer-1", "reviewer", s31, null, "idle")))
        PoolOffice(log, reporting = true).use { office ->
            office.reports!!.started()
            office.reported("budget.slots_over") shouldBe listOf("office 0", "subscription 0", "effort 0")

            // The office's limit lowered under the two seats in use: one over, on the office's dimension only.
            var at = office.at()
            office.budgets(BUDGETS.replace("per_office: {panda: 2, dsk: 2}", "per_office: {panda: 1, dsk: 2}"))
            office.reported("budget.slots_over", at) shouldBe listOf("office 1")
            office.wakes("POOL-16", at) shouldBe 1

            // The pool retires a seat (SEAT-11): the seat moved, and the office is no longer over.
            at = office.at()
            office.send("pool", "retire", """{"seat": "reviewer-1"}""", to = "reviewer-1")
            office.reported("budget.slots_over", at) shouldBe listOf("office 0")
            office.wakes("POOL-16", at) shouldBe 0
        }
    }

    "budget.consumption, when the vendor's fraction moves into another tenth — 0.69 → 0.71 and at a reset, not 0.71 → 0.74; POOL-7 wakes its seat at 0.71 and at 0.91: twice" {
        PoolOffice(reporting = true).use { office ->
            office.reports!!.started()
            office.reported("budget.consumption") shouldBe emptyList() // unknown before any report: left out at the start
            val resets = Instant.parse("2026-11-20T12:00:00Z")
            listOf("0.69", "0.71", "0.74", "0.91").forEach { fraction ->
                office.vendor = listOf(VendorReport("claude", "five_hour", BigDecimal(fraction), resets, Instant.parse("2026-11-20T09:00:00Z"), "coder-1"))
                office.reports.vendorReported()
            }
            office.reported("budget.consumption") shouldBe listOf("claude 0.69", "claude 0.71", "claude 0.91")
            office.wakes("POOL-7") shouldBe 2

            // After a reset the vendor says 0.05 of a new window: another tenth.
            val at = office.at()
            office.vendor = listOf(VendorReport("claude", "five_hour", BigDecimal("0.05"), resets.plusSeconds(18_000), Instant.parse("2026-11-20T09:00:00Z"), "coder-1"))
            office.reports.vendorReported()
            office.reported("budget.consumption", at) shouldBe listOf("claude 0.05")
            office.wakes("POOL-7", at) shouldBe 0
        }
    }

    "budget.vs_daily_median, when a turn's end is recorded and the whole part of its value changes; DEP-6 sends its anomaly-stop while Bora is away" {
        // Three days before today with 1000 each: the median is 1000.
        val days = listOf("2026-11-17", "2026-11-18", "2026-11-19").map { FixtureLog.TurnEnd("coder-1", "${it}T10:00:00Z", 1000, ended("claude")) }
        PoolOffice(FixtureLog(seats = listOf(SeatAt("coder-1", "coder", s31, "junior", "idle")), turns = days), reporting = true).use { office ->
            office.office.submit(Sender.Bora(signed = true), json("""{"kind": "away"}"""))
            office.reports!!.started()
            office.reported("budget.vs_daily_median") shouldBe listOf("claude 0")
            listOf(500L, 1000L, 400L, 1200L).forEach { count -> office.turnEnded(count) } // today: 0.5, 1.5, 1.9, 3.1
            office.reported("budget.vs_daily_median") shouldBe listOf("claude 0", "claude 1.5", "claude 3.1")
            office.anomalyStops(by = "DEP-6") shouldBe listOf(null)
        }
    }

    "at a start each of the four is reported once, as it then reads; one that is unknown is left out" {
        val keporkak = repository(Files.createTempDirectory("kp-s3.4-main-").resolve("keporkak"))
        PoolOffice(FixtureLog(stages = mapOf(s31 to "building")), repos = mapOf("keporkak" to keporkak), reporting = true).use { office ->
            office.reports!!.started()
            office.records().filterIsInstance<Fact>().map { it.fact } shouldBe listOf("git.stray_changes", "budget.slots_over", "budget.slots_over", "budget.slots_over")
        }
    }

    "a vendor's report through a fake seat reaches the reports: its consumption is reported" {
        val said = """{"status": "allowed", "resetsAt": 1795003200, "rateLimitType": "five_hour", "unifiedWindows": {
            "five_hour": {"utilization": 0.42, "resetsAt": 1795003200, "status": "allowed"}}}"""
        SeatsOffice(scripts = mapOf("coder-1" to """{"turns": [[{"do": "usage", "used": 1000, "size": 200000, "rate_limit": $said}, {"do": "end"}]]}""")).use { office ->
            val pool = PoolFacts("panda").also { it.reading(office.office, office.seats) }
            office.office.reports = kp.daemon.Reports(office.office, null, pool)
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("the consumption reported") { office.lines().filterIsInstance<Fact>().any { it.fact == "budget.consumption" } }
            office.lines().filterIsInstance<Fact>().single { it.fact == "budget.consumption" }.let { "${it.params?.get("subscription")} ${it.value}" } shouldBe "\"claude\" 0.42"
        }
    }
})

private fun ended(subscription: String) =
    "turn ended: end_turn · input 1, output 0, cache write 0, cache read 0 · weighted 1 by input 1, output 5, cache write 1.25, cache read 0.1 · subscription $subscription"

/** Where the log stands: what comes after is what a step did. */
private fun PoolOffice.at(): Long = office.counted().maxOfOrNull { it.seq.toLong() } ?: 0L

private fun PoolOffice.records(after: Long = 0): List<Envelope> = office.counted().filter { it.seq.toLong() > after }

/** The `fact` records of [name] after [after]: `<stage or the parameter it is for> <value>`. */
private fun PoolOffice.reported(name: String, after: Long = 0): List<String> = records(after).filterIsInstance<Fact>().filter { it.fact == name }.map { fact ->
    val about = fact.params?.let { (it["dimension"] ?: it["subscription"]) as? JsonPrimitive }?.contentOrNull ?: fact.stage
    "$about ${fact.value}"
}

private fun PoolOffice.strays(after: Long = 0): List<String> = reported("git.stray_changes", after)

/** The stage of each anomaly-stop [by] a robot's rule sent after [after]. */
private fun PoolOffice.anomalyStops(after: Long = 0, by: String = "DEP-7"): List<String?> =
    records(after).filterIsInstance<Message>().filter { it.kind == "anomaly-stop" && it.by == "robot:$by" }.map { it.stage?.value }

/** How many wakes of its own seat [rule] requested after [after]. */
private fun PoolOffice.wakes(rule: String, after: Long = 0): Int =
    records(after).filterIsInstance<Op>().count { it.op == "seat.wake" && it.phase == "requested" && (it.with?.get("rule") as? JsonPrimitive)?.contentOrNull == rule }

/** A turn of coder-1's ends with [count]: its record as the seats write it, and the reports told, as the seats tell them. */
private fun PoolOffice.turnEnded(count: Long) {
    office.recorded(FactRef("seat.usage", mapOf("seat" to Datum.Text("coder-1"), "window" to Datum.Text("turn"))), Datum.of(count), "KP/P3/S3.1", ended("claude"))
    reports!!.turnEnded()
}

