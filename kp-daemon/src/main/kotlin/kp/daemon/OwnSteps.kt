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

    /** [seat] has a live session and a turn in flight. */
    fun busy(seat: String): Boolean

    /** A turn of [seat] that the message [cause] woke it for has ended. */
    fun turnEnded(seat: String, cause: String): Boolean

    /** Why the play must end now — the seats have used more tokens than the walk allows — or null. */
    fun failure(): String?

    /** The play of [player] has ended — failed, with [failure], or not: the evidence and the play's record are written ([Written]). */
    fun ended(player: Player, failure: String?): Written
}

/**
 * What [OwnSteps.ended] wrote: the [evidence] file, and each path of the throwaway project the record keeps a copy of,
 * with its copy (S3.6 T5).
 */
class Written(val evidence: Path, private val kept: List<Pair<Path, Path>>) {
    /** [text] with each kept path its copy's: what pointed into the throwaway project points at the record (Amendment 38, S3.10). */
    fun pointed(text: String): String = kept.fold(text) { pointed, (from, to) -> pointed.replace(from.toString(), to.toString()) }
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
    /**
     * A rehearsal on the office (contracts.md §12, Amendment 46; S3.5 T5): the log is the office's and is not copied — the
     * stage's records are named by their `seq` — and the record names each operation with its result, the pull request
     * and the merge commit.
     */
    private val onTheOffice: Boolean = false,
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

    override fun busy(seat: String): Boolean = driven.busy(seat)

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

    override fun ended(player: Player, failure: String?): Written {
        seats.forEach(::sessionOf)
        val name = "${player.walkName}-${STAMP.format(clock.now())}"
        val record = folder.createDirectories().resolve(name)
        // The play's record (S3.6 T5): the log and the transcripts, copied beside the evidence, which names the copies. On the
        // office the log is the office's: it is named, by `seq`, and not copied (Amendment 46).
        val log = player.office.project.resolve(MAIL)
        val kept = (if (onTheOffice) emptyList() else listOf(log to record.resolve(MAIL))) + seats.map { seat -> transcript(seat) to record.resolve(TRANSCRIPTS).resolve("$seat.log") }
        kept.forEach { (from, to) -> copied(from, to) }
        // No token of any kind is in the record (S3.5 T5): a transcript's copy is hidden as the evidence is.
        seats.map { seat -> record.resolve(TRANSCRIPTS).resolve("$seat.log") }.filter { it.exists() }.forEach { copy -> copy.writeText(hidden(Files.readString(copy))) }
        val file = folder.resolve("$name.md")
        val written = Written(file, kept)
        file.writeText(hidden(written.pointed(Evidence(player, this, failure).text())))
        return written
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

    /**
     * [text] with the seats' Claude token, every session token minted for them, every GitHub token by its prefix and every
     * private key replaced: none is ever written.
     */
    private fun hidden(text: String): String {
        val token = runCatching { Files.readString(tokenFile).trim() }.getOrNull()?.takeIf { it.length >= MIN_SECRET }
        val minted = driven.mintedDigests()
        val plain = KEY.replace(GITHUB_TOKEN.replace(if (token == null) text else text.replace(token, HIDDEN), HIDDEN), HIDDEN)
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
            appendLine("- tokens: the seats used ${used.values.sum()} of the walk's ${at.claude.tokens} (`seat.usage` of each turn as it ended: its parts weighted by `usage.weights` of pool/budgets.yaml)")
            appendLine()
            appendLine("## The seats")
            appendLine()
            appendLine("| seat | role | model | effort | sessions | transcript | used |")
            appendLine("|---|---|---|---|---|---|---|")
            at.seats.sorted().forEach { seat ->
                val bound = at.driven.binding(seat)
                val turns = synchronized(at.turns) { at.turns[seat].orEmpty().toList() }
                val silent = turns.count { it.second == null }
                appendLine("| $seat | ${bound?.role ?: "-"} | ${bound?.model ?: "-"} | ${bound?.effort ?: "-"} | ${at.sessions[seat].orEmpty().joinToString(" · ").ifEmpty { "-" }} | " +
                    "${at.transcript(seat)} | ${used[seat] ?: 0} in ${turns.size} turn${if (turns.size == 1) "" else "s"}${if (silent > 0) ", $silent with no usage reported" else ""} |")
            }
            appendLine()
            appendLine("## What each turn used, by its parts")
            appendLine()
            appendLine("Each turn as its end in the seat's transcript shows it, and each seat's sums; `seat.usage` counts a turn's four parts — input, " +
                "output, cache write, cache read — weighted by `usage.weights` of pool/budgets.yaml, a part not reported as zero: the column weighted. " +
                "\"not reported\": the agent left that part out. The pinned adapter reports no count of a turn's model calls.")
            appendLine()
            appendLine("| seat | turn | input | output | cache read | cache write | thought | total | weighted |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            at.seats.sorted().forEach { seat ->
                val counted = synchronized(at.turns) { at.turns[seat].orEmpty().toList() }
                val turns = counted.map { it.second }
                counted.forEachIndexed { at, (weighted, usage) ->
                    appendLine("| $seat | ${at + 1} | ${PARTS.joinToString(" | ") { part -> usage?.let(part)?.toString() ?: NOT_REPORTED }} | ${weighted ?: NOT_REPORTED} |")
                }
                if (turns.isNotEmpty()) appendLine("| $seat | ${turns.size} turn${if (turns.size == 1) "" else "s"} | " + PARTS.joinToString(" | ") { part ->
                    val reported = turns.mapNotNull { usage -> usage?.let(part) }
                    "${reported.sum()}" + if (reported.size < turns.size) " ($NOT_REPORTED in ${turns.size - reported.size})" else ""
                } + " | ${counted.sumOf { it.first ?: 0L }} |")
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
            if (at.onTheOffice) office()
        }

        /**
         * On the office (S3.5 T5): each operation the play's stage asked for, with its result; the pull request and the merge
         * commit; and every record the play's window of the office's log holds, named by its `seq` — the log is not copied.
         */
        private fun StringBuilder.office() {
            val stage = player.stageId
            val causes = entries.filterIsInstance<Message>().filter { it.stage?.value == stage }.map { it.id.value }.toSet()
            // The stage's records: its messages and what became of them, what they set going, and the facts reported about it.
            val window = entries.filter { record ->
                (record is Message && record.id.value in causes) || record.json["id"]?.let { (it as? JsonPrimitive)?.contentOrNull } in causes ||
                    record.json["cause"]?.let { (it as? JsonPrimitive)?.contentOrNull } in causes || (record is Fact && record.stage == stage)
            }
            val operations = entries.filterIsInstance<Op>().filter { it.cause in causes }
            appendLine()
            appendLine("## Each operation, with its result")
            appendLine()
            operations.filter { it.phase == REQUESTED }.forEach { asked ->
                val ended = operations.lastOrNull { it.op == asked.op && it.cause == asked.cause && it.phase != REQUESTED }
                appendLine("- ${asked.op} for ${asked.cause} (seq ${asked.seq}): " + (ended?.let { "${it.phase} (seq ${it.seq})" + listOfNotNull(it.reason, it.result?.toString()).joinToString(" — ", prefix = if (it.reason != null || it.result != null) " — " else "") } ?: "no result on the log"))
            }
            appendLine()
            appendLine("## The pull request and the merge commit")
            appendLine()
            val made = operations.lastOrNull { it.op == WORKTREE_CREATE && it.phase == OK }?.result
            // The merge: the git.merge that ended ok, and the message that asked for it — whose judgement observed the head.
            val merging = operations.lastOrNull { it.op == GIT_MERGE && it.phase == OK }
            val head = merging?.let { op -> entries.filterIsInstance<Delivered>().lastOrNull { it.id == op.cause }?.observed?.get(PR_HEAD) }
            val merged = merging?.result?.text(SHA)
            appendLine("- the pull request: the stage's, in ${made?.text(REPOSITORY) ?: "-"} from ${made?.text(BRANCH) ?: "-"}" + (head?.let { " — its head at the merge's judgement: $it" } ?: ""))
            appendLine("- the merge commit: ${merged ?: "none on the log"}")
            appendLine()
            appendLine("## The stage's records on the office's log, by seq")
            appendLine()
            appendLine("The log is the office's, ${office.project.resolve(MAIL)}: the stage's lines are named here, not copied (Amendment 46).")
            appendLine()
            window.forEach { appendLine("- ${named(it)}") }
        }

        /** A record of the log named by its `seq`, and what it is. */
        private fun named(entry: kp.model.Envelope): String = "seq ${entry.seq} · " + when (entry) {
            is Message -> "${entry.kind} ${entry.id.value} from ${entry.from.value} (${entry.by}) to ${entry.to.value}"
            is Delivered -> "delivered ${entry.id}"
            is Refused -> "refused ${entry.id} by ${entry.rule}: ${entry.requirement}"
            is kp.model.Held -> "held ${entry.id}"
            is Op -> "${entry.op} ${entry.phase} for ${entry.cause}" + (entry.reason?.let { " — $it" } ?: "")
            is kp.model.Transition -> "transition ${entry.machine} ${entry.subject} ${entry.from} → ${entry.to}" + (entry.rule?.let { " ($it)" } ?: "")
            is Fact -> "fact ${entry.fact} ${entry.subject}"
            is Read -> "read by ${entry.seat}"
            else -> entry.json["event"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "record"
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

        /** A log line as the file holds it, with where it is — on the office, named by its `seq` and not copied (Amendment 46). */
        private fun shown(entry: kp.model.Envelope): String {
            if (at.onTheOffice) return named(entry)
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
        /** A GitHub token by its prefix — an installation token `ghs_`, and every other kind GitHub mints. */
        val GITHUB_TOKEN = Regex("\\bgh[pousr]_[A-Za-z0-9_.-]{20,}")
        /** A private key, PEM. */
        val KEY = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")
        const val MIN_SECRET = 8
        const val WAKE = "seat.wake"
        const val REQUESTED = "requested"
        const val RESEAT = "seat.reseat"
        const val SEAT_KEY = "seat"
        const val MAIL = "mail"
        const val WORKTREE_CREATE = "worktree.create"
        const val GIT_MERGE = "git.merge"
        const val OK = "ok"
        const val PR_HEAD = "pr.head"
        const val SHA = "sha"
        const val REPOSITORY = "repository"
        const val BRANCH = "branch"
        const val NOT_REPORTED = "not reported"
        /** A turn's usage by its parts, in the order its end shows them (S3.6 T6). */
        val PARTS: List<(kp.driver.Usage) -> Long?> = listOf({ it.input }, { it.output }, { it.cachedRead }, { it.cachedWrite }, { it.thought }, { it.total })
        const val TRANSCRIPTS = "transcripts"
        /** A word that may be a session token (base64url, as the driver mints one): what is hashed and compared. */
        val WORD = Regex("[A-Za-z0-9_-]{20,}")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
