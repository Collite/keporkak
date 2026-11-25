package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import kp.daemon.hero.Hero
import kp.mailroom.Sender
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * KP/P3/S3.4 T1 — `tools/kp-notify` (contracts.md §12, Amendment 45), run on Linux as on Bora's Mac: a rehearsal's daemon
 * ([Hero]) behind a fake `ssh`, first on its path, that runs `scripts/kp` on the daemon's socket for the host it is given,
 * and a fake `osascript` that notes each notification it is asked for — `<title>|<subtitle>|<message>`.
 */
class KpNotifySpec : StringSpec({
    "kp-notify raises one notification per class-B item and none for another class, one count for what waited at its start, shows no line of a body but the first, and comes back after its ssh ended" {
        Hero("thin-thread").use { hero ->
            val fakes = Fakes(hero.socket)
            val stage = hero.stage()
            fun send(seat: String, kind: String, args: String) = hero.office.submit(Sender.Seat(seat), json("""{"kind": "$kind", "stage": "$stage", "subject": "$kind", "args": $args}"""))
            fun classify(question: String) = send("reviewer-1", "classify-request", """{"question": "$question", "default": "follow-up"}""")
            fun inbox(): List<String> = hero.kp("inbox").text.lines()
            classify("waiting before it starts?")
            val started = fakes.notify("dsk")
            try {
                fakes.until("the count of what waited") { fakes.notifications().size == 1 }
                fakes.notifications() shouldBe listOf("Keporkak|dsk|1 item waits for you")

                // Bora away: the Deputy keeps a merge for him — class C, no notify.bora — and a class-B request, which asks it.
                hero.office.submit(Sender.Bora(signed = true), json("""{"kind": "away"}"""))
                send("teamlead", "merge", "{}")
                classify("a blocker?")
                fakes.until("the class-B item raised") { fakes.notifications().size == 2 }
                val held = inbox().filter { it.startsWith("#") }.drop(1)
                held.map { it.substringAfter(" · ").substringBefore(" · $stage") } shouldBe listOf("held by robot:DEP-3 · merge", "B, held by robot:DEP-9 · classify-request")
                fakes.notifications().last() shouldBe "${held.last().substringBefore(" · ")} classify-request|$stage|rolls: none"
                inbox() shouldContain "   default: follow-up" // a line of the item kp-notify does not show

                // The connection ends: kp-notify says so once, tries again, and the next item is raised too.
                fakes.endSsh()
                fakes.until("the fake ssh started again") { fakes.connections() == 3 }
                classify("after the connection came back?")
                fakes.until("the next item raised") { fakes.notifications().size == 3 }
                fakes.notifications().last().substringAfter("classify-request|") shouldBe "$stage|rolls: none"
                fakes.notifications().filter { "merge" in it } shouldBe emptyList()
            } finally {
                started.destroy()
                started.waitFor(10, TimeUnit.SECONDS)
            }
            fakes.said().count { "ended" in it } shouldBe 1
        }
    }
})

/** The fake `ssh` and `osascript` of kp-notify's spec, and kp-notify run with them first on its path. */
private class Fakes(socket: Path) {
    private val dir: Path = Files.createTempDirectory("kp-notify-")
    private val raised: Path = dir.resolve("notifications")
    private val pids: Path = dir.resolve("ssh-pids")

    init {
        // `ssh <host> kp <args>`: scripts/kp on the daemon's socket, its pid noted — exec keeps it, so ending it ends the connection.
        executable("ssh", """
            #!/bin/sh
            echo $$ >> "$pids"
            shift; shift
            KP_SOCKET="$socket" exec "${checkout.resolve("scripts/kp")}" "$@"
        """)
        // `osascript - <title> <subtitle> <message>`, the script on its stdin: each notification on a line.
        executable("osascript", """
            #!/bin/sh
            shift
            cat > /dev/null
            printf '%s|%s|%s\n' "$1" "$2" "$3" >> "$raised"
        """)
    }

    private val output: Path = dir.resolve("said")

    fun notify(host: String): Process = ProcessBuilder(checkout.resolve("tools/kp-notify").toString(), host).redirectErrorStream(true).redirectOutput(output.toFile())
        .apply { environment()["PATH"] = "$dir:" + System.getenv("PATH") }.start()

    /** What kp-notify said on its terminal, line by line. */
    fun said(): List<String> = if (output.exists()) output.readLines() else emptyList()

    fun notifications(): List<String> = if (raised.exists()) raised.readLines() else emptyList()

    /** How many times the fake ssh was run: once for the count, then once for each connection followed. */
    fun connections(): Int = if (pids.exists()) pids.readLines().size else 0

    /** Ends the connection being followed: its process, the last the fake ssh ran. */
    fun endSsh() {
        ProcessHandle.of(pids.readLines().last().trim().toLong()).ifPresent { it.destroy() }
    }

    fun until(what: String, seconds: Long = 30, done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!done()) {
            check(System.nanoTime() < end) { "$what: not within ${seconds}s — notifications ${notifications()}" }
            Thread.sleep(50)
        }
    }

    private fun executable(name: String, text: String) {
        dir.resolve(name).apply { writeText(text.trimIndent() + "\n") }.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
    }
}
