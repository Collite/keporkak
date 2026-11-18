package kp.log

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kp.model.Envelope
import kp.model.Message
import kp.model.OfficeId
import kp.model.StageId
import kp.model.readEnvelope
import kp.model.toLine
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * How the writer reaches the disk: it opens the files and directories it writes and syncs through this. A spec watches
 * the writes and syncs through it, or stops the writer after a line, as a daemon killed there.
 */
fun interface Channels {
    fun open(path: Path, vararg options: OpenOption): FileChannel

    companion object {
        val files = Channels { path, options -> FileChannel.open(path, *options) }
    }
}

/**
 * The office's only writer of `mail/` (contracts.md §3). It holds a lock on `mail/.writer.lock` while open, so a
 * second writer on the same project — in this process or another — is refused. It assigns `seq`, continuing from
 * the highest on disk. A line appended alone returns only once it is synced to disk; the lines of a [Judgement] reach
 * the disk together when it is closed.
 */
class LogWriter private constructor(
    private val project: Path,
    private val office: OfficeId,
    private val lock: FileLock,
    last: Long,
    private val closed: MutableSet<Path>,
    private val channels: Channels,
) : AutoCloseable {
    /** The `seq` of the last line written or added to the open judgement, in any file of this office: the next line's is one more. */
    var last: Long = last
        private set

    private var open: Judgement? = null

    /** Whether [stage]'s file ended with its own `exited` or `closed` and takes no more lines (§3, Rotation). */
    fun isClosed(stage: StageId): Boolean = Layout.stageFile(project, stage) in closed

    /**
     * Appends [draft] — a message or a record without `seq` — and returns the line as written. A message goes to its
     * own stage's file; a record to the file of [stage]; a line of no stage, or of a stage whose file is closed — a
     * late result, a late message (contracts.md §3) — to the office's file for the month of its `at`. A line the
     * contract refuses, or a hand-carried message, is refused, and nothing is written; so is a line while a judgement
     * is open.
     */
    fun append(draft: JsonObject, stage: StageId? = null): Envelope {
        if (open != null) throw LogRefusal("a judgement is open: its lines are added to it")
        val line = place(draft, stage)
        write(line.file, line.bytes)
        take(line)
        return line.entry
    }

    /**
     * Opens a judgement (contracts.md §3, Amendment 23): what one arrival produces, appended together. One is open at a
     * time; until it is closed the writer appends nothing else.
     */
    fun judgement(): Judgement {
        if (!lock.isValid) throw LogRefusal("the writer is closed")
        if (open != null) throw LogRefusal("a judgement is open already")
        return Judgement().also { open = it }
    }

    /**
     * The lines of one judgement. Each takes its `seq` and its file when it is [add]ed, and none is on disk until
     * [close]: then they are written in `seq` order, closed by a `judged` line, and each file is synced once — the
     * `judged` line's file last, so that when it is on disk every line it closes is too.
     */
    inner class Judgement internal constructor() {
        private val lines = ArrayList<Placed>()
        /** The stage of the first line's file: the `judged` line goes there, or to the office's file once that is closed. */
        private var owner: StageId? = null

        /** Adds [draft] as [append] would place it, and returns the line with its `seq`; a line the contract refuses changes nothing. */
        fun add(draft: JsonObject, stage: StageId? = null): Envelope {
            check(open === this) { "the judgement is closed" }
            val line = place(draft, stage)
            if (lines.isEmpty()) owner = line.owner
            lines += line
            take(line)
            return line.entry
        }

        /** Writes the lines, closed by a `judged` record at [at] naming the first; returns the `judged` line once its file is synced. */
        fun close(at: String): Envelope {
            check(open === this) { "the judgement is closed" }
            check(lines.isNotEmpty()) { "a judgement closes the lines of an arrival; this one has none" }
            val judged = place(buildJsonObject {
                put("v", JsonPrimitive(1))
                put("at", JsonPrimitive(at))
                put("event", JsonPrimitive("judged"))
                put("first", JsonPrimitive(lines.first().entry.seq))
            }, owner)
            lines += judged
            take(judged)
            open = null
            writeAll(lines, last = judged.file)
            return judged.entry
        }
    }

    override fun close() {
        if (lock.isValid) lock.release()
        lock.channel().close()
    }

    /** A line placed: its entry with `seq`, the stage whose file it goes to (none: the office's), that file and its bytes. */
    private class Placed(val entry: Envelope, val owner: StageId?, val file: Path) {
        val bytes: ByteArray get() = (entry.toLine() + "\n").toByteArray(Charsets.UTF_8)
    }

    /** [draft] with the next `seq`, checked against the contract, and the file it goes to; nothing is changed. */
    private fun place(draft: JsonObject, stage: StageId?): Placed {
        if (!lock.isValid) throw LogRefusal("the writer is closed")
        if ("seq" in draft) throw LogRefusal("the log assigns seq; a draft must not carry one")
        val entry = try {
            readEnvelope(withSeq(draft, last + 1))
        } catch (error: IllegalArgumentException) {
            throw LogRefusal("not a line of contracts.md §2: ${error.message}")
        }
        if (entry is Message && entry.by.startsWith("hand:")) throw LogRefusal("a hand-carried message is recorded in _hand/, never by the office's writer")
        if (entry is Message && stage != null && stage != entry.stage) throw LogRefusal("a message is appended for its own stage (${entry.stage?.value})")
        val owner = (if (entry is Message) entry.stage else stage)?.takeUnless(::isClosed)
        return Placed(entry, owner, if (owner != null) Layout.stageFile(project, owner) else Layout.officeFile(project, office, entry.at.value))
    }

    /** The line is the last one: the next takes the `seq` after it, and a stage's own exit closes its file. */
    private fun take(line: Placed) {
        last += 1
        if (line.owner != null && Layout.closes(line.entry, line.owner.value)) closed.add(line.file)
    }

    private fun write(file: Path, bytes: ByteArray) {
        val created = !Files.exists(file)
        if (created) createDirectories(file.parent, channels)
        channels.open(file, CREATE, WRITE, APPEND).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (created) sync(file.parent, channels)
    }

    /** Writes [lines] in order, one write per line, then syncs each file once — [last] after the others — and each new file into its directory. */
    private fun writeAll(lines: List<Placed>, last: Path) {
        val opened = LinkedHashMap<Path, FileChannel>()
        val created = LinkedHashSet<Path>()
        try {
            lines.forEach { line ->
                val channel = opened.getOrPut(line.file) {
                    if (!Files.exists(line.file)) {
                        createDirectories(line.file.parent, channels)
                        created.add(line.file)
                    }
                    channels.open(line.file, CREATE, WRITE, APPEND)
                }
                val buffer = ByteBuffer.wrap(line.bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            (opened.keys.filter { it != last } + listOf(last)).forEach { opened.getValue(it).force(true) }
        } finally {
            opened.values.forEach(FileChannel::close)
        }
        created.map { it.parent }.distinct().forEach { sync(it, channels) }
    }

    companion object {
        /** Opens the writer for [office] on [project]; refused while another writer holds it or a file is torn. */
        fun open(project: Path, office: OfficeId): LogWriter = open(project, office, Channels.files)

        /** As [open], reaching the disk through [channels]. */
        fun open(project: Path, office: OfficeId, channels: Channels): LogWriter {
            val mail = Layout.mail(project)
            createDirectories(mail, channels)
            val channel = FileChannel.open(mail.resolve(".writer.lock"), CREATE, WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw LogRefusal("another writer holds $mail")
            }
            try {
                var last = 0L
                val closed = mutableSetOf<Path>()
                val stageFiles = Layout.stageFiles(project).toSet()
                for (file in stageFiles + Layout.officeFiles(project, office)) {
                    val parsed = ParsedFile.of(file)
                    parsed.problems.filterIsInstance<Torn>().firstOrNull()?.let { throw LogRefusal("$it; a person repairs it before the writer starts") }
                    if (parsed.count == 0) continue
                    val final = parsed.last ?: throw LogRefusal("${parsed.problems.last()}; the writer cannot continue its seq")
                    last = maxOf(last, final.seq)
                    if (file in stageFiles && Layout.closes(final.entry, Layout.stageOf(file))) closed.add(file)
                }
                return LogWriter(project, office, lock, last, closed, channels)
            } catch (error: Throwable) {
                lock.release()
                channel.close()
                throw error
            }
        }

        private fun withSeq(draft: JsonObject, seq: Long) = buildJsonObject {
            draft.forEach { (name, value) ->
                put(name, value)
                if (name == "v") put("seq", JsonPrimitive(seq))
            }
            if ("v" !in draft) put("seq", JsonPrimitive(seq))
        }

        /** Creates the missing directories and syncs each new entry into its parent. */
        private fun createDirectories(directory: Path, channels: Channels) {
            if (Files.isDirectory(directory)) return
            createDirectories(directory.parent, channels)
            Files.createDirectory(directory)
            sync(directory.parent, channels)
        }

        private fun sync(directory: Path, channels: Channels) = channels.open(directory, READ).use { it.force(true) }
    }
}
