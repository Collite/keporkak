package kp.daemon.seats

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kp.daemon.FakeSeating
import kp.daemon.hero.Hero
import kp.model.Message

/**
 * S2.4 T8 — `just rehearse h1-prime --seats fake`, played in a spec: the daemon as a rehearsal starts it with S2.1's fake
 * agent as the walk's seats ([FakeSeating]), Bora's steps the real `kp` over its socket with `sudo` simulated (as the
 * heroes have it), panes in a tmux of the spec's own. On Panda the same runs with the real tmux and Bora at `sudo kp`.
 */
class RehearsedWithFakeSeatsSpec : StringSpec({
    "H-1′ with fake seats runs to the walk's end: its seats woken with their mail, coder-1 killed and re-seated, a pane each" {
        val tmux = kotlin.io.path.createTempFile("tmux-", ".sh").also { file ->
            file.toFile().writeText("#!/bin/sh\necho \"$*\" >> \"$file.log\"\ncase \"$1\" in -V) echo tmux ;; list-windows) cat \"$file.windows\" 2>/dev/null ;; has-session) [ -f \"$file.windows\" ] ;; new-session|new-window) echo \"$6\" >> \"$file.windows\" ;; esac\n")
            file.toFile().setExecutable(true)
        }
        Hero("h1-prime", fake = FakeSeating(System.getProperty("kp.driver.fake.classpath"), tmux = tmux.toString(), panes = "kp-rehearsal")).use { hero ->
            hero.rest()

            hero.office.state.stage(hero.stage()) shouldBe "exited"
            val lines = hero.office.counted()
            val agents = FakeAgents(System.getProperty("kp.driver.fake.classpath"), hero.project.resolve("office/fake"))
            // Each seat read all its mail, woken with it; coder-1 died once — a process of its own each time — and was re-seated.
            listOf("coder-1", "reviewer-1").map { hero.office.state.unread(it) } shouldBe listOf(emptyList(), emptyList())
            listOf("coder-1", "reviewer-1").map { seat -> agents.received(seat).count { (it["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "start" } } shouldBe listOf(2, 1)
            listOf("coder-1", "reviewer-1").forEach { seat -> agents.received(seat).any { (it["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "session/prompt" } shouldBe true }
            lines.filterIsInstance<kp.model.Op>().filter { it.op == "seat.reseat" }.map { it.phase } shouldBe listOf("requested", "ok")
            lines.filterIsInstance<Message>().map { it.kind } shouldContain "re-seated"
            // A pane each, made when the seat started; coder-1's closed when it died and made again when it was re-seated.
            val tmuxed = java.nio.file.Files.readAllLines(java.nio.file.Path.of("$tmux.log"))
            tmuxed.filter { it.startsWith("new-") }.map { it.split(" ")[5] } shouldBe listOf("coder-1", "reviewer-1", "coder-1")
            tmuxed shouldContain "kill-window -t kp-rehearsal:coder-1"
        }
    }
})
