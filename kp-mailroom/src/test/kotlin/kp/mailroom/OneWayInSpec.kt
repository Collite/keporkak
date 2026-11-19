package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * S2.7 T4 — one way in (contracts.md §3, §5): the mailroom answers input that is no message as it answers a seat's tool,
 * whoever submits it, and writes nothing; the slices below `TheSeatEndsSpec`'s sentence, for the next developer.
 */
class OneWayInSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "the mailroom has one way in: its source declares submit, and no other entry that takes a sender's input" {
        val source = File(root, "kp-mailroom/src/main/kotlin/kp/mailroom/Mailroom.kt").readText()

        Regex("""fun (\w+)\(sender: Sender, input: JsonObject\)""").findAll(source).map { it.groupValues[1] }.toList() shouldBe listOf("submit")
        Regex("""\bfun offer\b""").containsMatchIn(source) shouldBe false
    }

    "input that is no message is answered by the field that is wrong, from a seat and from Bora alike, and nothing is written — not even his presence" {
        val office = Office(robots = true)
        val before = office.readBack().lines.size
        office.bora("""{"kind": "away"}""")
        val away = office.readBack().lines.size

        val answers = listOf(
            office.mailroom.submit(Sender.Seat("coder-1"), json("""{"subject": "no kind"}""")),
            office.mailroom.submit(Sender.Seat("coder-1"), json("""{"kind": "Started", "stage": "$STAGE"}""")),
            office.mailroom.submit(Sender.Bora(signed = true), json("""{"kind": "approval", "re": "nowhere", "stage": "$STAGE"}""")),
            office.mailroom.submit(Sender.Bora(signed = false), json("""{"kind": "decline", "stage": "KP/S2.1"}""")),
        )

        answers.map { it.shouldBeInstanceOf<Offer.Malformed>().field } shouldBe listOf("kind", "kind", "re", "stage")
        (away > before) shouldBe true
        office.readBack().lines.size shouldBe away
        office.mailroom.state.presence("bora") shouldBe "absent"
    }
})
