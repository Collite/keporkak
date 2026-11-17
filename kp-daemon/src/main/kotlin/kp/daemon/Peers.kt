package kp.daemon

import jdk.net.ExtendedSocketOptions
import java.nio.channels.SocketChannel

/** The peer of one connection on the control socket (contracts.md §6): the OS user it runs as. */
data class Peer(val user: String) {
    /** Signed (contracts.md §6): the peer is root — `sudo kp …`. */
    val root: Boolean get() = user == ROOT

    companion object {
        const val ROOT = "root"
    }
}

/** Who a connection's peer is: read from the socket itself; a spec replaces it to simulate root (S1.5 T1). */
fun interface PeerReader {
    fun peer(channel: SocketChannel): Peer
}

/**
 * The JDK's own way (architecture.md §6 item 3): `SO_PEERCRED` through its extended socket options, on Linux. The
 * kernel says who connected; nothing the client sends is believed. Whether this JVM offers it is decided by a spec
 * (ControlSocketSpec) — on Panda's Temurin 21 it does, so the fallback, a second socket in a root-owned directory, is
 * not built.
 */
object SoPeerCred : PeerReader {
    override fun peer(channel: SocketChannel): Peer = Peer(channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user().name)

    /** Whether [channel] offers the option at all. */
    fun offered(channel: SocketChannel): Boolean = ExtendedSocketOptions.SO_PEERCRED in channel.supportedOptions()
}
