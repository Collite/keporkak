package kp.daemon.hero

import kp.daemon.checkout
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** What `just hero` prints after the specs ran (S1.6 T7): the lines each part leaves in `kp-daemon/build/hero/`. */
object HeroReport {
    private val folder = checkout.resolve("kp-daemon/build/hero")

    fun sweep(sweep: Sweep) = write("sweep.txt", sweep.report())

    fun write(name: String, lines: List<String>) {
        folder.createDirectories().resolve(name).writeText(lines.joinToString("") { "$it\n" })
    }
}
