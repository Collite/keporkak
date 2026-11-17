package kp.daemon

import java.nio.file.Path

/** `just rehearse <walk>` (architecture.md §7): the daemon on a throwaway project, scripted seats and facts from a walk. */
class Rehearsal private constructor() : AutoCloseable {
    /** Blocks until the walk waits for Bora — the kind it waits for — or has ended (null). */
    fun awaitBora(): String? = TODO("S1.5 T7")

    override fun close() = Unit

    companion object {
        fun start(root: Path, walk: Path, project: Path, office: String, socket: Path, peers: PeerReader, out: (String) -> Unit): Rehearsal = TODO("S1.5 T7")
    }
}
