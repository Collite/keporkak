package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.daemon.Office
import kp.daemon.Reports
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.VendorReport
import kp.mailroom.Status
import kp.model.Fact
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KP/P3/S3.15 T1, T4 — the reports of one key are written in the order they were decided (contracts.md §4.4, Amendment
 * 45; §7, Amendment 36). Bora reads these. The seam: S3.13's fixture office ([PoolOffice]) with the daemon's [Reports],
 * told by two callers that the spec holds apart — the first has decided its report and not yet written it ([HeldWrite])
 * when the second decides; the first is then let go. Each is the report's `fact` record on the office's log.
 */
class TheReportsInOrderSpec : StringSpec({
    "two seats' vendor reports for one subscription: the second decides before the first has written, and the first is written first" {
        PoolOffice().use { office ->
            val write = HeldWrite(office.office)
            val reports = Reports(office.office, office.world, office.pool, write).also { office.office.reports = it }
            reports.started()
            val at = office.at()

            // coder-1's seat says 0.71 of the window: decided, and held before it is written.
            write.hold()
            office.vendor = listOf(vendor("0.71", "coder-1"))
            val first = CompletableFuture.runAsync { reports.vendorReported() }
            write.entered()
            // coder-2's seat says 0.91 of the same subscription's window: another tenth, decided after the first.
            office.vendor = listOf(vendor("0.91", "coder-2"))
            reports.vendorReported()
            write.go()
            first.get(30, TimeUnit.SECONDS)

            office.reported("budget.consumption", at) shouldBe listOf("claude 0.71", "claude 0.91")
        }
    }

    "a reload's report and a judgement's `moved`: the judgement decides before the reload has written, and the reload's is written first" {
        val log = FixtureLog(seats = listOf(SeatAt("coder-1", "coder", S31, "junior", "idle"), SeatAt("reviewer-1", "reviewer", S31, null, "idle")))
        PoolOffice(log).use { office ->
            val write = HeldWrite(office.office)
            val reports = Reports(office.office, office.world, office.pool, write).also { office.office.reports = it }
            reports.started()
            office.reported("budget.slots_over") shouldBe listOf("office 0", "subscription 0", "effort 0")
            val at = office.at()

            // The office's limit lowered under the two seats in use: the reload decides one over, and is held before it writes.
            write.hold()
            val reload = CompletableFuture.runAsync { office.budgets(BUDGETS.replace("per_office: {panda: 2, dsk: 2}", "per_office: {panda: 1, dsk: 2}")) }
            write.entered()
            // The pool retires a seat (SEAT-11): the judgement moved a seat, and decides — the office is no longer over.
            office.send("pool", "retire", """{"seat": "reviewer-1"}""", to = "reviewer-1").status shouldBe Status.DELIVERED
            write.go()
            reload.get(30, TimeUnit.SECONDS)

            office.reported("budget.slots_over", at) shouldBe listOf("office 1", "office 0")
        }
    }
})

private const val S31 = "KP/P3/S3.1"

/** What the vendor says of the subscription `claude`'s five-hour window through [seat]: [fraction] used. */
private fun vendor(fraction: String, seat: String) =
    VendorReport("claude", "five_hour", BigDecimal(fraction), Instant.parse("2026-11-20T12:00:00Z"), Instant.parse("2026-11-20T09:00:00Z"), seat)

/**
 * Where [Reports] writes a report: the office's [Office.recorded] — but, once [hold] is called, the first report written
 * waits there until [go]. Its caller has decided it, and has not written it.
 */
private class HeldWrite(private val office: Office) : (FactRef, Datum, String?, String?) -> Unit {
    @Volatile private var holding = false
    private val held = AtomicBoolean(false)
    private val arrived = CountDownLatch(1)
    private val gone = CountDownLatch(1)

    fun hold() {
        holding = true
    }

    /** Waits until the first report held has arrived here. */
    fun entered() = check(arrived.await(30, TimeUnit.SECONDS)) { "no report arrived to be held" }

    fun go() = gone.countDown()

    override fun invoke(fact: FactRef, value: Datum, stage: String?, evidence: String?) {
        if (holding && held.compareAndSet(false, true)) {
            arrived.countDown()
            check(gone.await(30, TimeUnit.SECONDS)) { "the held report was never let go" }
        }
        office.recorded(fact, value, stage, evidence)
    }
}

/** Where the log stands: what comes after is what a step did. */
private fun PoolOffice.at(): Long = office.counted().maxOfOrNull { it.seq.toLong() } ?: 0L

/** The `fact` records of [name] after [after], in the log's order: `<the parameter it is for> <value>`. */
private fun PoolOffice.reported(name: String, after: Long = 0): List<String> =
    office.counted().filter { it.seq.toLong() > after }.filterIsInstance<Fact>().filter { it.fact == name }.map { fact ->
        val about = fact.params?.let { (it["dimension"] ?: it["subscription"]) as? JsonPrimitive }?.contentOrNull ?: fact.stage
        "$about ${fact.value}"
    }
