package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import kotlin.io.path.exists

/** One command as `kp` sends it (contracts.md §6): `{v, cmd, args, tty}`. */
data class Request(val cmd: String, val args: List<String>, val tty: Boolean)

/** Where a command writes its answer: each line is one `{text}`; the last, written by the socket, is `{done, code}`. */
fun interface Out {
    /** Writes one line of text to `kp`; throws [IOException] once `kp` has gone. */
    fun line(text: String)
}

/** What the daemon does with one command from one peer: writes its lines to [out] and returns its exit code. */
fun interface Commands {
    fun run(request: Request, peer: Peer, out: Out): Int
}

/** The control socket would not open: another daemon answers on it, or its folder cannot be made private. */
class SocketRefusal(message: String) : RuntimeException(message)

/**
 * The control socket (contracts.md §6, architecture.md §4): a Unix socket in the office user's runtime directory. A
 * connection sends one JSON line `{v, cmd, args, tty}` and gets lines `{text}` until `{done, code}`. Who sent it is read
 * from the socket itself by [peers] — never taken from what the client sends. Each connection has a thread of its own,
 * so a `kp inbox --follow` that stays open blocks nobody.
 */
class ControlSocket private constructor(private val path: Path, private val server: ServerSocketChannel, private val peers: PeerReader, private val commands: Commands) : AutoCloseable {
    private val open: MutableSet<SocketChannel> = Collections.synchronizedSet(HashSet())
    private val accepting = Thread.ofPlatform().name("kp-control").daemon(true).unstarted(::accept)
    @Volatile private var closed = false

    private fun accept() {
        while (!closed) {
            val channel = try {
                server.accept()
            } catch (_: ClosedChannelException) {
                return
            } catch (error: IOException) {
                if (closed) return
                Journal.say("control socket: accept failed: ${error.message}")
                continue
            }
            open += channel
            Thread.ofVirtual().name("kp-command").start { serve(channel) }
        }
    }

    private fun serve(channel: SocketChannel) = channel.use {
        try {
            val out = Out { text -> write(channel, buildJsonObject { put(TEXT, JsonPrimitive(text)) }) }
            val code = try {
                val peer = peers.peer(channel)
                when (val read = read(channel)) {
                    is Read.Command -> commands.run(read.request, peer, out)
                    is Read.Bad -> out.line("kp: ${read.why}").let { USAGE }
                }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                Journal.say("control socket: a command failed: $error")
                out.line("kp: the daemon could not carry this command: ${error.message ?: error.javaClass.simpleName}")
                FAILED
            }
            write(channel, buildJsonObject { put(DONE, JsonPrimitive(true)); put(CODE, JsonPrimitive(code)) })
        } catch (_: IOException) {
            // `kp` went away — a `--follow` interrupted, or a closed daemon. Nothing to answer.
        } finally {
            open -= channel
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        server.close()
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        accepting.join(JOIN_MILLIS)
        Files.deleteIfExists(path)
    }

    private sealed interface Read {
        data class Command(val request: Request) : Read
        data class Bad(val why: String) : Read
    }

    companion object {
        /** `kp`'s answer to a malformed request; a command that throws ends with [FAILED]. */
        const val USAGE = 2
        const val FAILED = 3
        private const val LIMIT = 1 shl 20
        private const val JOIN_MILLIS = 5_000L
        private const val TEXT = "text"
        private const val DONE = "done"
        private const val CODE = "code"

        /**
         * Binds [path]: its folder is made private to the office user (0700); a socket file no daemon answers on is
         * a stale one and is replaced; one a daemon answers on refuses the start.
         */
        fun open(path: Path, peers: PeerReader, commands: Commands): ControlSocket {
            val folder = path.toAbsolutePath().parent
            if (!folder.exists()) Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            if (path.exists()) {
                val answers = runCatching { SocketChannel.open(UnixDomainSocketAddress.of(path)).close() }.isSuccess
                if (answers) throw SocketRefusal("a daemon already answers on $path: stop it first (just stop)")
                Files.delete(path)
            }
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
            return ControlSocket(path, server, peers, commands).also { it.accepting.start() }
        }

        private fun write(channel: SocketChannel, line: JsonObject) {
            val bytes = ByteBuffer.wrap((line.toString() + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
        }

        /** One line, at most [LIMIT] bytes, as a request; what is not one is answered with why. */
        private fun read(channel: SocketChannel): Read {
            val line = ByteArrayOutputStream()
            val byte = ByteBuffer.allocate(1)
            while (true) {
                byte.clear()
                if (channel.read(byte) < 0) return Read.Bad("the request ended before its line did")
                val value = byte.get(0)
                if (value == '\n'.code.toByte()) break
                if (line.size() >= LIMIT) return Read.Bad("the request is longer than $LIMIT bytes")
                line.write(value.toInt())
            }
            val json = runCatching { Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
                ?: return Read.Bad("the request is not one JSON object")
            if (json["v"]?.jsonPrimitive?.intOrNull != 1) return Read.Bad("the request is not of version 1")
            val cmd = (json["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return Read.Bad("the request names no command")
            val args = (json["args"] ?: JsonArray(emptyList())) as? JsonArray ?: return Read.Bad("the request's args are not a list")
            val words = args.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return Read.Bad("the request's args are not all strings") }
            val tty = (json["tty"] as? JsonPrimitive)?.booleanOrNull ?: false
            return Read.Command(Request(cmd, words, tty))
        }
    }
}
