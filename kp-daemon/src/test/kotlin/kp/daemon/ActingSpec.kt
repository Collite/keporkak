package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kp.model.Fact
import kp.model.Transition

private val ROOT = Peer("root")

/** S1.9 T2 — presence (contracts.md §6): every signed command reaches the mailroom as Bora acting; an unsigned one moves nothing. */
class ActingSpec : FreeSpec({
    fun TestOffice.presence(): String = office.state.presence("bora") ?: "present"
    fun TestOffice.facts(): List<String> = office.lines().map { it.entry }.filterIsInstance<Fact>().map { "${it.fact} ${it.evidence}" }

    "a signed command that sends no message — a slip of usage, an unknown ticket, `help` — brings Bora back" {
        TestOffice().use { office ->
            val back = listOf(listOf("approve"), listOf("approve", "99"), listOf("help"), listOf("check", "/nowhere")).map { command ->
                office.kp("away", peer = ROOT)
                office.kp(*command.toTypedArray(), peer = ROOT)
                office.presence()
            }

            back shouldBe listOf("present", "present", "present", "present")
        }
    }

    "an unsigned command while Bora is away writes nothing and leaves him away" {
        TestOffice().use { office ->
            office.kp("away", peer = ROOT)
            val before = office.office.lines().size

            listOf("status", "inbox", "help").forEach { office.kp(it) }
            office.kp("approve", "99")

            office.office.lines().size shouldBe before
            office.presence() shouldBe "absent"
        }
    }

    "a signed message acts once: one `bora.acted`, then the message is judged with Bora back" {
        TestOffice().use { office ->
            office.kp("away", peer = ROOT)

            office.kp("queue", "KP/P2/S2.1", peer = ROOT)

            office.facts() shouldBe listOf("bora.acted signed command")
            office.office.lines().map { it.entry }.filterIsInstance<Transition>().filter { it.machine == "presence" }.map { it.to } shouldBe listOf("absent", "present")
        }
    }
})
