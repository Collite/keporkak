package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kp.engine.Datum
import kp.engine.FactRef

private val ROOT = Peer("root")

/** S1.5 T5 — answering: Bora's six state-changing commands become messages `by: bora-cli`, signed only from a root peer. */
class AnsweringSpec : FreeSpec({
    "each of Bora's six commands is its kind, by bora-cli, in reply to the item it names, signed only from a root peer" {
        TestOffice().use { office ->
            office.send("teamlead", proposal("KP/P2/S2.1"))
            office.send("teamlead", proposal("KP/P2/S2.2"))
            office.send("principal", """{"kind": "ruling-request", "stage": "KP/P2/S2.1", "subject": "which stage", "args": {"default": "cut it", "citation": "contracts.md §4.5"}}""")

            office.kp("approve", "4", peer = ROOT)
            office.kp("decline", "#11", "not", "now", peer = ROOT)
            office.kp("rule", "18", "stage", "cut", "the", "owning", "stage", peer = ROOT)
            office.kp("reverse", "m-panda-000003", "minor", peer = ROOT)
            office.kp("queue", "KP/P2/S2.3", "KP/P2/S2.4", peer = ROOT)
            office.kp("away")

            office.messages().filter { it.by == "bora-cli" }.map { listOf(it.kind, it.re?.value, it.stage?.value, it.signed, it.args?.toString(), it.body) } shouldBe listOf(
                listOf("approval", "m-panda-000003", "KP/P2/S2.1", true, null, null),
                listOf("decline", "m-panda-000010", "KP/P2/S2.2", true, null, "not now"),
                listOf("ruling", "m-panda-000017", "KP/P2/S2.1", true, """{"outcome":"stage"}""", "cut the owning stage"),
                listOf("reverse", "m-panda-000003", "KP/P2/S2.1", true, """{"digest_item":"m-panda-000003","severity":"minor"}""", null),
                listOf("queue-approval", null, null, true, """{"stages":["KP/P2/S2.3","KP/P2/S2.4"]}""", null),
                listOf("away", null, null, false, null, null),
            )
        }
    }

    "kp rule carries each key=value word after the outcome as one more arg, and the words after them as its text" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())

            office.kp("rule", "4", "resume", "seat=coder-1", "cause=anomaly", "the anomaly", "is=cleared", peer = ROOT)

            office.messages().single { it.kind == "ruling" }.let { listOf(it.args.toString(), it.body) } shouldBe
                listOf("""{"outcome":"resume","seat":"coder-1","cause":"anomaly"}""", "the anomaly is=cleared")
        }
    }

    "an unsigned answer is refused and logged, and kp says how to sign it; a refusal exits 1" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())

            val unsigned = office.kp("approve", "4")

            unsigned shouldBe Ran(1, "refused m-panda-000010 — approval: signed by Bora's CLI\n   (unsigned: Bora's answers count only under sudo — sudo kp …)\n")
            office.office.state.stage("KP/P2/S2.1") shouldBe "proposed"
        }
    }

    "a ticket that was never issued sends nothing; a command without its words is not one" {
        TestOffice().use { office ->
            val before = office.messages().size

            office.kp("approve", "42", peer = ROOT) shouldBe Ran(1, "kp: no ticket #42 — kp inbox --all lists them\n")
            office.kp("rule", "3", peer = ROOT) shouldBe Ran(2, "kp: kp rule <ticket> <outcome> [key=value …] [text]\n")
            office.kp("approve", "three", peer = ROOT) shouldBe Ran(2, "kp: kp approve <ticket>\n")
            office.kp("away", "now", peer = ROOT) shouldBe Ran(2, "kp: kp away\n")
            office.messages().size shouldBe before
        }
    }

    "every command from a root peer — a read too — is noted as Bora acting; an unsigned one is not" {
        TestOffice().use { office ->
            val idle = FactRef("bora.idle_for")
            val known = office.office.presence.observe(idle)
            office.kp("status")
            val afterUnsigned = office.office.presence.observe(idle)
            office.kp("status", peer = ROOT)
            val afterSigned = office.office.presence.observe(idle)
            office.now = office.now.plusSeconds(90)

            listOf(known, afterUnsigned, afterSigned) shouldBe listOf(null, null, Datum.of(0))
            office.office.presence.observe(idle) shouldBe Datum.of(90)
            office.office.presence.observe(FactRef("bora.acted")) shouldBe Datum.Bool(true)
        }
    }
})
