package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.assertions.throwables.shouldThrow
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** S1.5 T2 — the control socket (contracts.md §6): a request in, lines of text and a final code out; the peer from the socket. */
class ControlSocketSpec : FreeSpec({
    "on this JVM the JDK reads a Unix socket peer's user from the socket itself, so the daemon needs no second socket" {
        val path = Files.createTempDirectory("kp-").resolve("peer.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path)).use { server ->
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use {
                server.accept().use { accepted ->
                    SoPeerCred.offered(accepted) shouldBe true
                    SoPeerCred.peer(accepted) shouldBe Peer(System.getProperty("user.name"))
                }
            }
        }
    }

    "one JSON line in, lines of text and a final code out; the peer is the reader's, not the request's" {
        val path = socketPath()
        ControlSocket.open(path, { Peer("root") }) { request, peer, out ->
            out.line("${request.cmd} ${request.args} tty=${request.tty}")
            out.line("from ${peer.user}")
            7
        }.use {
            exchange(path, """{"v":1,"cmd":"approve","args":["5"],"tty":true,"peer":"bora"}""") shouldBe listOf(
                """{"text":"approve [5] tty=true"}""",
                """{"text":"from root"}""",
                """{"done":true,"code":7}""",
            )
        }
    }

    "a line that is not a request is answered with why, and code 2" {
        val path = socketPath()
        ControlSocket.open(path, { Peer("bora") }) { _, _, _ -> 0 }.use {
            exchange(path, """{"v":2,"cmd":"inbox"}""") shouldBe listOf("""{"text":"kp: the request is not of version 1"}""", """{"done":true,"code":2}""")
            exchange(path, """{"v":1,"cmd":"rule","args":["5",7]}""") shouldBe listOf("""{"text":"kp: the request's args are not all strings"}""", """{"done":true,"code":2}""")
            exchange(path, "approve 5") shouldBe listOf("""{"text":"kp: the request is not one JSON object"}""", """{"done":true,"code":2}""")
        }
    }

    "a command that throws is answered with why, and the socket goes on" {
        val path = socketPath()
        ControlSocket.open(path, { Peer("bora") }) { request, _, out -> if (request.cmd == "boom") error("no state") else 0.also { out.line("fine") } }.use {
            exchange(path, """{"v":1,"cmd":"boom","args":[],"tty":false}""") shouldBe listOf("""{"text":"kp: the daemon could not carry this command: no state"}""", """{"done":true,"code":3}""")
            exchange(path, """{"v":1,"cmd":"inbox","args":[],"tty":false}""") shouldBe listOf("""{"text":"fine"}""", """{"done":true,"code":0}""")
        }
    }

    "a second daemon on the same socket is refused; a socket file no daemon answers on is replaced; the folder is private" {
        val path = socketPath()
        ControlSocket.open(path, { Peer("bora") }) { _, _, _ -> 0 }.use {
            shouldThrow<SocketRefusal> { ControlSocket.open(path, { Peer("bora") }) { _, _, _ -> 0 } }.message shouldStartWith "a daemon already answers on $path"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(path.parent)) shouldBe "rwx------"
        }
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path)).close()
        Files.exists(path) shouldBe true
        ControlSocket.open(path, { Peer("bora") }) { _, _, out -> 0.also { out.line("again") } }.use {
            exchange(path, """{"v":1,"cmd":"status"}""") shouldBe listOf("""{"text":"again"}""", """{"done":true,"code":0}""")
        }
        Files.exists(path) shouldBe false
    }
})

/** A socket in a folder that does not exist yet. */
private fun socketPath(): Path = Files.createTempDirectory("kp-").resolve("run/kp.sock")

/** Sends [line] as `kp` would and reads every line of the answer. */
private fun exchange(path: Path, line: String): List<String> = SocketChannel.open(UnixDomainSocketAddress.of(path)).use { channel ->
    channel.write(ByteBuffer.wrap("$line\n".toByteArray()))
    val answer = StringBuilder()
    val buffer = ByteBuffer.allocate(4096)
    while (channel.read(buffer.clear()) >= 0) answer.append(String(buffer.array(), 0, buffer.position()))
    answer.lines().filter { it.isNotEmpty() }
}
