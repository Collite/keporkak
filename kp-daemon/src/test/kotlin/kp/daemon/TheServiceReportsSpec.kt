package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kp.engine.Datum
import kp.engine.FactRef
import kp.log.LogWriter
import kp.model.Envelope
import kp.model.Fact
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * KP/P3/S3.4 T1 (review-038 B2) — what starts the four reports of Amendment 45 in the daemon itself: the service's start,
 * its seats' watch every `watch.every`, a turn's end. The seams: the daemon as `kp-daemon run` starts it ([Launched]), on
 * a fixture office — its main checkouts and project throwaway repositories, its `watch.every` one second — whose log has
 * two stages building and Bora away, its log read as it writes it; and the seats driving the fake agent ([SeatsOffice]),
 * the reports the service's.
 */
class TheServiceReportsSpec : StringSpec({
    "the service's start reports each fact once as it reads; its watch reports a stray file for each active stage, and DEP-7 sends an anomaly-stop for each — taken away and put back, again" {
        val office = ReportingOffice()
        write(office.keporkak, "stray.txt", "a stray file\n")
        val daemon = Launched(office.project, Files.createTempDirectory("kp-").resolve("kp.sock"), office.root)
        try {
            daemon.awaitLine("started: office panda")
            // The start: the stray paths for each stage in an active state, the slots over for each dimension; the
            // consumption and the day against the median are unknown here — no vendor report, no day before — and left out.
            office.until("the start's reports and DEP-7's anomaly-stops") { office.anomalyStops().size == 2 }
            office.facts().filter { it.second != "budget.consumption" && it.second != "budget.vs_daily_median" }.map { it.first } shouldBe
                listOf("KP/P3/S3.1 git.stray_changes 1", "KP/P3/S3.2 git.stray_changes 1", "office budget.slots_over 0",
                    "subscription budget.slots_over 0", "effort budget.slots_over 0")
            office.anomalyStops() shouldBe listOf("KP/P3/S3.1", "KP/P3/S3.2")

            // The watch, every second: the file taken away is reported for each stage, put back again — and DEP-7 again.
            office.keporkak.resolve("stray.txt").deleteExisting()
            office.until("the stray file reported gone") { office.strays().count { it.endsWith(" 0") } == 2 }
            write(office.keporkak, "stray.txt", "a stray file, again\n")
            office.until("the stray file reported back") { office.anomalyStops().size == 4 }
            office.strays() shouldBe listOf("KP/P3/S3.1 1", "KP/P3/S3.2 1", "KP/P3/S3.1 0", "KP/P3/S3.2 0", "KP/P3/S3.1 1", "KP/P3/S3.2 1")
            office.anomalyStops() shouldBe listOf("KP/P3/S3.1", "KP/P3/S3.2", "KP/P3/S3.1", "KP/P3/S3.2")
        } finally {
            daemon.process.toHandle().destroy()
            daemon.ended()
        }
    }

    "a turn's end, through the seats and the fake agent, reports the day against the median when its whole part changes" {
        // Three days before with 1000 each, as the seats record a turn's end: the median is 1000. Today one turn of 3000.
        val turn = """{"turns": [[{"do": "end", "usage": {"input": 3000, "output": 0, "cache_write": 0, "cache_read": 0, "total": 3000}}]]}"""
        kp.daemon.seats.SeatsOffice(startsAt = "2026-11-15T09:00:00Z", scripts = mapOf("coder-1" to turn)).use { office ->
            office.office.reports = Reports(office.office, null, kp.daemon.seats.PoolFacts("panda").also { it.reading(office.office, office.seats) })
            listOf("2026-11-15", "2026-11-16", "2026-11-17").forEach { day ->
                office.clock.now = Instant.parse("${day}T10:00:00Z")
                office.office.recorded(FactRef("seat.usage", mapOf("seat" to Datum.Text("coder-1"), "window" to Datum.Text("turn"))), Datum.of(1000), office.stage(), ENDED)
            }
            office.clock.now = Instant.parse("2026-11-18T10:00:00Z")
            office.send("principal", "triage", """{"outcome": "issue", "found": "0", "since": "m-panda-000001"}""")
            office.until("the day against the median reported") { office.lines().filterIsInstance<Fact>().any { it.fact == "budget.vs_daily_median" } }

            office.lines().filterIsInstance<Fact>().filter { it.fact == "budget.vs_daily_median" }.map { "${(it.params?.get("subscription") as? JsonPrimitive)?.contentOrNull} ${it.value}" } shouldBe
                listOf("claude 3")
        }
    }
})

/** A turn's end as the seats' record of it begins, of a seat of the subscription `claude`. */
private const val ENDED = "turn ended: end_turn · input 1000, output 0, cache write 0, cache read 0 · weighted 1000 by input 1, output 5, cache write 1.25, cache read 0.1 · subscription claude"

/**
 * A fixture office for the service: its project checkout and two main checkouts, throwaway repositories with one commit;
 * master's rules with the office `panda` reading them and watching every second; a log whose one judgement moved
 * `KP/P3/S3.1` and `KP/P3/S3.2` to `building` and Bora to absent.
 */
private class ReportingOffice {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "reports-")
    val project: Path = committed(folder.resolve("project"))
    val keporkak: Path = committed(folder.resolve("keporkak"))
    private val toy: Path = committed(folder.resolve("kp-toy"))
    val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        val text = offices.readText()
        val panda = text.indexOf("\n  panda:\n")
        val after = text.indexOf("\n  dsk:\n", panda)
        val entry = text.substring(panda, after)
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), Regex.escapeReplacement("    project: {checkout: \"$project\","))
            .replace(Regex("(?m)^    repos: .*$"), Regex.escapeReplacement("    repos: {keporkak: \"$keporkak\", kp-toy: \"$toy\"}"))
            .replace(Regex("(?m)^    watch: \\{every: [^}]+}$"), "    watch: {every: 1s}")
        offices.writeText(text.substring(0, panda) + entry + text.substring(after))
    }

    init {
        LogWriter.open(project, OfficeId("panda")).use { log ->
            val judgement = log.judgement()
            listOf("KP/P3/S3.1", "KP/P3/S3.2").forEach { stage ->
                judgement.add(json("""{"v":1,"at":"$AT","event":"transition","machine":"stage","subject":"$stage","from":"planned","to":"building","cause":"fixture"}"""), StageId(stage))
            }
            judgement.add(json("""{"v":1,"at":"$AT","event":"transition","machine":"presence","subject":"bora","from":"present","to":"absent","rule":"PRESENCE-01","cause":"fixture"}"""))
            judgement.close(AT)
        }
    }

    private fun committed(dir: Path): Path = repository(dir).also {
        write(it, "README.md", "${it.fileName}\n")
        git(it, "add", "-A")
        git(it, "commit", "--quiet", "-m", "one")
    }

    /** The log as the daemon has written it so far: a line it is writing is read once it is whole. */
    private fun lines(): List<Envelope> = runCatching { Log(project.resolve("mail")).lines }.getOrElse { Thread.sleep(50); Log(project.resolve("mail")).lines }

    /** Each `fact` record of the four: `<what it is about> <fact> <value>`, and its fact. */
    fun facts(): List<Pair<String, String>> = lines().filterIsInstance<Fact>().filter { it.fact in FOUR }.map { fact ->
        val about = (fact.params?.get("dimension") as? JsonPrimitive)?.contentOrNull ?: (fact.params?.get("subscription") as? JsonPrimitive)?.contentOrNull ?: fact.stage
        "$about ${fact.fact} ${fact.value}" to fact.fact
    }

    fun strays(): List<String> = facts().filter { it.second == "git.stray_changes" }.map { it.first.replace(" git.stray_changes", "") }

    /** The stage of each anomaly-stop DEP-7 sent. */
    fun anomalyStops(): List<String?> = lines().filterIsInstance<Message>().filter { it.kind == "anomaly-stop" && it.by == "robot:DEP-7" }.map { it.stage?.value }

    fun until(what: String, seconds: Long = 60, done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!done()) {
            check(System.nanoTime() < end) { "$what: not within ${seconds}s — the four: ${facts().map { it.first }}" }
            Thread.sleep(200)
        }
    }

    private companion object {
        const val AT = "2026-11-24T08:00:00Z"
        val FOUR = setOf("git.stray_changes", "budget.slots_over", "budget.consumption", "budget.vs_daily_median")
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    }
}
