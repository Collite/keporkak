package kp.daemon

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
