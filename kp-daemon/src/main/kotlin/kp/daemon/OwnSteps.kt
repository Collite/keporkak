package kp.daemon

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kp.daemon.seats.Seats
import kp.log.LogLine
import kp.log.counted
import kp.model.Delivered
import kp.model.Fact
import kp.model.Message
import kp.model.Op
import kp.model.Read
import kp.model.Refused
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * The walk's Claude seats as they play their own steps (S2.5; contracts.md §12, Amendment 27): what a [Player] needs of
 * them — whose steps are theirs and how long one is waited for, each seat's transcript, whether a seat is idle, whether
 * the turn a message woke it for has ended, and whether the seats have used more tokens than the walk allows — and, when
 * the play ends, its evidence.
 */
interface OwnSteps {
    val seats: Set<String>
    val wait: Duration

    fun transcript(seat: String): Path

    /** [seat] has a live session and no turn in flight. */
    fun idle(seat: String): Boolean

    /** A turn of [seat] that the message [cause] woke it for has ended. */
    fun turnEnded(seat: String, cause: String): Boolean

    /** Why the play must end now — the seats have used more tokens than the walk allows — or null. */
    fun failure(): String?

    /** The play of [player] has ended — failed, with [failure], or not: the evidence is written, and named. */
    fun ended(player: Player, failure: String?): Path
}

/**
 * [OwnSteps] of the seats the daemon drives ([Seats]): it hears each turn's end ([Seats.Turns]) and counts what it used
 * — the turn's `seat.usage`, as the driver observes it when the turn ends: a turn that never ended reported none. The
 * evidence goes to [folder] ([Evidence]) as `<walk>-<time>.md`, and the play's record beside it, in `<walk>-<time>/`: the
 * log (`mail/`) and each Claude seat's transcript (`transcripts/<seat>.log`), copied when the play ends, however it ends
 * (S3.6 T5). The evidence points there, not into the throwaway project. [tokenFile] is the seats' Claude token, read only
 * to keep it out of the evidence.
 */
class SeatsAtPlay(
    private val claude: Walk.ClaudeSeats,
    private val transcripts: Path,
    private val folder: Path,
    private val tokenFile: Path,
    private val walkFile: Path,
    private val clock: kp.mailroom.Clock,
) : OwnSteps, Seats.Turns {
    /** The daemon's seats; set once they are made, before any of them starts. */
    @Volatile lateinit var driven: Seats
    @Volatile private var office: Office? = null
    private val began: Instant = clock.now()
    /** Each seat's turns, in the order they ended: the turn's `seat.usage`, and its usage by its parts (S3.6 T6). */
    private val turns = ConcurrentHashMap<String, MutableList<Pair<Long?, kp.driver.Usage?>>>()
    private val woken: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()
    private val sessions = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var over: String? = null

    override val seats: Set<String> get() = claude.seats
    override val wait: Duration get() = claude.wait

    /** The office whose changes a turn's end signals: the player waits on them. */
    fun attach(office: Office) {
        this.office = office
        seats.forEach(::sessionOf)
    }

    override fun transcript(seat: String): Path = transcripts.resolve("$seat.log")

    override fun idle(seat: String): Boolean = driven.idle(seat)

    override fun turnEnded(seat: String, cause: String): Boolean = (seat to cause) in woken

    override fun failure(): String? = over

    override fun ended(seat: String, cause: String?, usage: Long?, parts: kp.driver.Usage?) {
        sessionOf(seat)
        synchronized(turns) { turns.getOrPut(seat) { ArrayList() } += usage to parts }
        val total = used().values.sum()
        if (total > claude.tokens && over == null) {
            over = "the seats have used $total tokens, more than the walk's ${claude.tokens} (" +
                used().entries.joinToString(", ") { (each, tokens) -> "$each $tokens" } + "); the last turn was $seat's — its transcript: ${transcript(seat)}"
        }
        // The turn is said to have ended only once what it used is counted: whoever waits for it then reads [failure] whole (review-024, B1).
        cause?.let { woken += seat to it }
        office?.changes?.signal()
    }

    /** What each seat has used, by its turns' `seat.usage`. */
    fun used(): Map<String, Long> = synchronized(turns) { turns.toSortedMap().mapValues { (_, each) -> each.sumOf { it.first ?: 0L } } }

    private fun sessionOf(seat: String) {
        driven.session(seat)?.let { session -> sessions.getOrPut(seat) { java.util.Collections.synchronizedSet(LinkedHashSet()) } += session }
    }

    override fun ended(player: Player, failure: String?): Path {
        seats.forEach(::sessionOf)
        val name = "${player.walkName}-${STAMP.format(clock.now())}"
        val record = folder.createDirectories().resolve(name)
        // The play's record (S3.6 T5): the log and the transcripts, copied beside the evidence, which names the copies.
        val log = player.office.project.resolve(MAIL)
        val kept = listOf(log to record.resolve(MAIL)) + seats.sorted().map { seat -> transcript(seat) to record.resolve(TRANSCRIPTS).resolve("$seat.log") }
        kept.forEach { (from, to) -> copied(from, to) }
        val written = Evidence(player, this, failure).text()
        val file = folder.resolve("$name.md")
        file.writeText(hidden(kept.fold(written) { text, (from, to) -> text.replace(from.toString(), to.toString()) }))
        return file
    }

    /** [from] — a file, or a folder and all it holds — as it is now, at [to]; nothing when it does not exist. */
    private fun copied(from: Path, to: Path) {
        if (!from.exists()) return
        Files.walk(from).use { paths ->
            paths.forEach { path ->
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) target.createDirectories() else Files.copy(path, target.also { it.parent.createDirectories() }, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** [text] with the seats' Claude token and every session token minted for them replaced: neither is ever written. */
    private fun hidden(text: String): String {
        val token = runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf { it.length >= MIN_SECRET }
        val minted = driven.mintedDigests()
        val plain = if (token == null) text else text.replace(token, HIDDEN)
        return WORD.replace(plain) { word -> if (Seats.sha256(word.value) in minted) HIDDEN else word.value }
    }

    /** The evidence of one play (contracts.md §12): the log, the seats, the steps, P2's DONE line by the log, what was sent beyond. */
    private class Evidence(private val player: Player, private val at: SeatsAtPlay, private val failure: String?) {
        private val office = player.office
        private val log: List<LogLine> = office.lines().counted()
        private val entries = log.map { it.entry }
        private val raw = HashMap<Path, List<String>>()

        fun text(): String = buildString {
            val used = at.used()
            appendLine("# Rehearsal ${player.walkName} with its seats playing their own steps — ${if (failure == null) "passed" else "failed"}")
            appendLine()
            if (failure != null) appendLine("**Failed:** $failure\n")
            appendLine("- the walk: ${at.walkFile} · the stage ${player.stageId} · office ${office.name} · from ${at.began} to ${at.clock.now()}")
            appendLine("- the log: ${office.project.resolve("mail")} — " + log.map { it.file }.distinct().joinToString(", ") { it.relativeTo(office.project).toString() })
            appendLine("- tokens: the seats used ${used.values.sum()} of the walk's ${at.claude.tokens} (`seat.usage` of each turn, as it ended)")
            appendLine()
            appendLine("## The seats")
            appendLine()
            appendLine("| seat | role | model | effort | sessions | transcript | used |")
            appendLine("|---|---|---|---|---|---|---|")
            at.seats.sorted().forEach { seat ->
                val bound = at.driven.binding(seat)
                val turns = synchronized(at.turns) { at.turns[seat].orEmpty().toList() }
                val silent = turns.count { it.first == null }
                appendLine("| $seat | ${bound?.role ?: "-"} | ${bound?.model ?: "-"} | ${bound?.effort ?: "-"} | ${at.sessions[seat].orEmpty().joinToString(" · ").ifEmpty { "-" }} | " +
                    "${at.transcript(seat)} | ${used[seat] ?: 0} in ${turns.size} turn${if (turns.size == 1) "" else "s"}${if (silent > 0) ", $silent with no usage reported" else ""} |")
            }
            appendLine()
            appendLine("## What each turn used, by its parts")
            appendLine()
            appendLine("Each turn as its end in the seat's transcript shows it, and each seat's sums; `seat.usage` counts a turn's total. " +
                "\"not reported\": the agent left that part out. The pinned adapter reports no count of a turn's model calls.")
            appendLine()
            appendLine("| seat | turn | input | output | cache read | cache write | thought | total |")
            appendLine("|---|---|---|---|---|---|---|---|")
            at.seats.sorted().forEach { seat ->
                val turns = synchronized(at.turns) { at.turns[seat].orEmpty().map { it.second } }
                turns.forEachIndexed { at, usage -> appendLine("| $seat | ${at + 1} | ${PARTS.joinToString(" | ") { part -> usage?.let(part)?.toString() ?: NOT_REPORTED }} |") }
                if (turns.isNotEmpty()) appendLine("| $seat | ${turns.size} turn${if (turns.size == 1) "" else "s"} | " + PARTS.joinToString(" | ") { part ->
                    val reported = turns.mapNotNull { usage -> usage?.let(part) }
                    "${reported.sum()}" + if (reported.size < turns.size) " ($NOT_REPORTED in ${turns.size - reported.size})" else ""
                } + " |")
            }
            appendLine()
            appendLine("## The steps")
            appendLine()
            player.played.forEach { played ->
                val step = played.step
                val what = listOfNotNull(step.id, step.who ?: step.text("by"), step.kind ?: step.operation ?: (step.fields("event")["fact"] as? kp.engine.Datum.Text)?.value).joinToString(" · ")
                val own = step.who in at.seats && step.kind != null
                val lines = if (played.last < played.first) "its lines were on the log before the step" else "log lines ${played.first}–${played.last}"
                appendLine("- ${played.number} · $what${if (own) " (the seat's own)" else ""}: ${played.outcome ?: "done"}${played.message?.let { " $it" } ?: ""} — $lines")
            }
            appendLine()
            appendLine("## P2's DONE line, by the log")
            (woken() + own() + deaths()).sortedBy { it.first }.forEach { append(it.second) }
            appendLine()
            appendLine("## The pointers")
            appendLine()
            appendLine("- the log: ${office.project.resolve("mail")}")
            at.seats.sorted().forEach { seat -> appendLine("- $seat: transcript ${at.transcript(seat)} · sessions ${at.sessions[seat].orEmpty().joinToString(" · ").ifEmpty { "-" }}") }
            appendLine()
            appendLine("## What the seats sent beyond the walk's steps")
            appendLine()
            val stepped = player.played.mapNotNull { it.message }.toSet()
            val beyond = entries.filterIsInstance<Message>().filter { it.from.value in at.seats && it.by == "seat:${it.from.value}" && it.id.value !in stepped }
            if (beyond.isEmpty()) appendLine("none") else block(beyond.flatMap { message -> listOf(message) + entries.filter { record -> outcomeOf(record, message.id.value) } })
        }

        /**
         * Each Claude seat woken with a message — when it was delivered, or when a turn ended and it was the oldest unread:
         * the message, its delivery, the wake requested and ended, and the seat's `read`. By the line the wake was asked on.
         */
        private fun woken(): List<Pair<Long, String>> = entries.filterIsInstance<Op>().filter { it.op == WAKE && it.phase == REQUESTED && it.with?.text(SEAT_KEY) in at.seats }.mapNotNull { asked ->
            val seat = asked.with!!.text(SEAT_KEY)!!
            val message = message(asked.cause) ?: return@mapNotNull null
            val wakes = entries.filterIsInstance<Op>().filter { it.op == WAKE && it.cause == asked.cause && it.with?.text(SEAT_KEY) == seat }
            val read = entries.filterIsInstance<Read>().filter { it.seat == seat && asked.cause in it.ids }
            asked.seq.toLong() to section("$seat is woken by ${message.kind} ${message.id.value}", listOf(message) + entries.filter { outcomeOf(it, asked.cause) } + wakes + read)
        }

        /** Each step a seat played itself: its message and what became of it — a refusal also as the tool result its transcript shows. */
        private fun own(): List<Pair<Long, String>> = player.played.filter { it.step.who in at.seats && it.step.kind != null && it.message != null }.map { played ->
            val seat = played.step.who!!
            val id = played.message!!
            val lines = listOfNotNull(message(id)) + entries.filter { outcomeOf(it, id) }
            val refused = entries.any { it is Refused && it.id == id }
            val shown = if (!refused) "" else "\nthe tool result in $seat's transcript:\n```\n" +
                transcriptLines(seat).filter { id in it && " → " in it && " replay " !in it }.joinToString("\n") + "\n```\n"
            (lines.minOfOrNull { it.seq.toLong() } ?: 0L) to section("$seat sends ${played.step.kind}: ${played.outcome}", lines) + shown
        }

        /** Each seat whose end a step reported: the driver's report, what the robots sent and asked, the re-seat and its prompt. */
        private fun deaths(): List<Pair<Long, String>> = player.played.filter { it.step.fields("event").isNotEmpty() && (it.step.fields("trigger")["seat"] as? kp.engine.Datum.Text)?.value in at.seats }.map { played ->
            val seat = (played.step.fields("trigger")["seat"] as kp.engine.Datum.Text).value
            val after = log.filter { it.entry.seq.toLong() >= played.first }.map { it.entry }
            val ends = after.filterIsInstance<Fact>().filter { it.fact in Script.DEATHS_OF_A_SEAT && it.subject == seat }
            val pool = after.filterIsInstance<Message>().filter { it.by.startsWith("robot:") && it.plainArgs["seat"] == seat }.flatMap { message ->
                listOf(message) + after.filter { outcomeOf(it, message.id.value) }
            }
            val reseats = after.filterIsInstance<Op>().filter { it.op == RESEAT && (it.with?.text(SEAT_KEY) == seat || it.result?.text(SEAT_KEY) == seat) }
            val prompt = transcriptLines(seat).filter { " prompt: You are $seat, re-seated" in it }
            played.first to section("$seat's process ends and the seat is re-seated", ends + pool + reseats) +
                "\nthe re-seat's prompt in $seat's transcript:\n```\n" + prompt.joinToString("\n") + "\n```\n"
        }

        private fun section(title: String, lines: List<kp.model.Envelope>): String = "\n### $title\n\n```\n" + lines.distinctBy { it.seq }.sortedBy { it.seq }.joinToString("\n", transform = ::shown) + "\n```\n"

        private fun StringBuilder.block(lines: List<kp.model.Envelope>) = append("```\n" + lines.distinctBy { it.seq }.sortedBy { it.seq }.joinToString("\n", transform = ::shown) + "\n```\n")

        /** A log line as the file holds it, with where it is. */
        private fun shown(entry: kp.model.Envelope): String {
            val line = log.first { it.entry.seq == entry.seq }
            val text = raw.getOrPut(line.file) { line.file.readLines() }.getOrNull(line.number - 1) ?: entry.json.toString()
            return "${line.file.fileName}:${line.number} $text"
        }

        private fun message(id: String): Message? = entries.filterIsInstance<Message>().firstOrNull { it.id.value == id }

        private fun outcomeOf(record: kp.model.Envelope, id: String): Boolean =
            (record is Delivered && record.id == id) || (record is Refused && record.id == id) || (record is kp.model.Held && record.id == id)

        private fun transcriptLines(seat: String): List<String> = at.transcript(seat).takeIf { it.exists() }?.readLines().orEmpty()

        private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private companion object {
        const val HIDDEN = "‹hidden›"
        const val MIN_SECRET = 8
        const val WAKE = "seat.wake"
        const val REQUESTED = "requested"
        const val RESEAT = "seat.reseat"
        const val SEAT_KEY = "seat"
        const val MAIL = "mail"
        const val NOT_REPORTED = "not reported"
        /** A turn's usage by its parts, in the order its end shows them (S3.6 T6). */
        val PARTS: List<(kp.driver.Usage) -> Long?> = listOf({ it.input }, { it.output }, { it.cachedRead }, { it.cachedWrite }, { it.thought }, { it.total })
        const val TRANSCRIPTS = "transcripts"
        /** A word that may be a session token (base64url, as the driver mints one): what is hashed and compared. */
        val WORD = Regex("[A-Za-z0-9_-]{20,}")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
