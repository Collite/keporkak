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

/** How the writer opens the files and directories it writes and syncs; a spec watches the writes and syncs through it. */
internal fun interface Channels {
    fun open(path: Path, vararg options: OpenOption): FileChannel

    companion object {
        val files = Channels { path, options -> FileChannel.open(path, *options) }
    }
}

/**
 * The office's only writer of `mail/` (contracts.md §3). It holds a lock on `mail/.writer.lock` while open, so a
 * second writer on the same project — in this process or another — is refused. It assigns `seq`, continuing from
 * the highest on disk, and returns only once the line is synced to disk.
 */
class LogWriter private constructor(
    private val project: Path,
    private val office: OfficeId,
    private val lock: FileLock,
    last: Long,
    private val closed: MutableSet<Path>,
    private val channels: Channels,
) : AutoCloseable {
    /** The `seq` of the last line on disk, in any file of this office: the next line's is one more. */
    var last: Long = last
        private set

    /** Whether [stage]'s file ended with its own `exited` or `closed` and takes no more lines (§3, Rotation). */
    fun isClosed(stage: StageId): Boolean = Layout.stageFile(project, stage) in closed

    /**
     * Appends [draft] — a message or a record without `seq` — and returns the line as written. A message goes to its
     * own stage's file; a record to the file of [stage]; a line of no stage, or of a stage whose file is closed — a
     * late result, a late message (contracts.md §3) — to the office's file for the month of its `at`. A line the
     * contract refuses, or a hand-carried message, is refused, and nothing is written.
     */
    fun append(draft: JsonObject, stage: StageId? = null): Envelope {
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
        val file = if (owner != null) Layout.stageFile(project, owner) else Layout.officeFile(project, office, entry.at.value)

        write(file, (entry.toLine() + "\n").toByteArray(Charsets.UTF_8))
        last += 1
        if (owner != null && Layout.closes(entry, owner.value)) closed.add(file)
        return entry
    }

    override fun close() {
        if (lock.isValid) lock.release()
        lock.channel().close()
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

    companion object {
        /** Opens the writer for [office] on [project]; refused while another writer holds it or a file is torn. */
        fun open(project: Path, office: OfficeId): LogWriter = open(project, office, Channels.files)

        internal fun open(project: Path, office: OfficeId, channels: Channels): LogWriter {
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
