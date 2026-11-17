package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** S1.5 T4 — reading: `kp inbox` and `kp status` from the folded state; tickets issued once, never renumbered. */
class ReadingSpec : FreeSpec({
    "an item keeps its ticket — the seq of the line that opened it — across a restart and later items" {
        TestOffice().use { office ->
            office.send("teamlead", proposal("KP/P2/S2.1"))
            val first = office.kp("inbox").text
            office.restart()
            office.send("teamlead", proposal("KP/P2/S2.2"))

            first.lines().first() shouldStartWith "#3 · B · assignment-proposal · KP/P2/S2.1"
            office.kp("inbox").text.lines().filter { it.startsWith("#") } shouldBe listOf(
                "#3 · B · assignment-proposal · KP/P2/S2.1 · from teamlead",
                "#9 · B · assignment-proposal · KP/P2/S2.2 · from teamlead",
            )
        }
    }

    "an item that asks Bora to choose shows its default; one no longer waiting shows only with --all" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())
            office.send("principal", """{"kind": "ruling-request", "stage": "KP/P2/S2.1", "subject": "which stage owns it", "args": {"default": "cut the owning stage", "citation": "contracts.md §4.5"}}""")
            office.office.submit(kp.mailroom.Sender.Bora(signed = true), json("""{"kind": "approval", "re": "m-panda-000002", "stage": "KP/P2/S2.1"}"""))

            office.kp("inbox").text shouldBe """
                #8 · B · ruling-request · KP/P2/S2.1 · from principal
                   rolls: none · live: none · touches: unknown
                   default: cut the owning stage

            """.trimIndent()
            office.kp("inbox", "--all").text.lines() shouldContain "#3 · B · assignment-proposal · KP/P2/S2.1 · from teamlead — answered by m-panda-000011"
        }
    }

    "`--follow` prints what waits, then each new item as it comes, until kp goes" {
        TestOffice().use { office ->
            office.send("teamlead", proposal("KP/P2/S2.1"))
            val printed = java.util.concurrent.LinkedBlockingQueue<String>()
            val follower = Thread.ofVirtual().start {
                office.commands.run(Request("inbox", listOf("--follow"), tty = false), Peer("bora")) { line ->
                    if (line == "gone") throw java.io.IOException("kp went away")
                    printed += line
                }
            }
            val first = printed.poll(10, java.util.concurrent.TimeUnit.SECONDS)
            printed.poll(10, java.util.concurrent.TimeUnit.SECONDS)
            office.send("teamlead", proposal("KP/P2/S2.2"))
            val next = printed.poll(10, java.util.concurrent.TimeUnit.SECONDS)
            office.close()
            follower.join(5_000)

            first shouldBe "#3 · B · assignment-proposal · KP/P2/S2.1 · from teamlead"
            next shouldBe "#8 · B · assignment-proposal · KP/P2/S2.2 · from teamlead"
            follower.isAlive shouldBe false
        }
    }

    "with nothing open the inbox says so" {
        TestOffice().use { office -> office.kp("inbox").text shouldBe "nothing waits for Bora\n" }
    }

    "status gives the position, Bora's presence, the open tickets and the budgets — or one stage's part of it" {
        TestOffice().use { office ->
            office.send("teamlead", proposal("KP/P2/S2.1"))
            office.send("teamlead", proposal("KP/P2/S2.2"))

            val all = office.kp("status").text.lines()
            val one = office.kp("status", "KP/P2/S2.2").text.lines()

            all shouldContainAll listOf("Bora: present", "stage KP/P2/S2.1: proposed · proposals 1", "stage KP/P2/S2.2: proposed · proposals 1", "open tickets: #3, #8")
            all.first() shouldStartWith "office panda · rules "
            all.single { it.startsWith("budgets: ") } shouldBe "budgets: 4 slots · per subscription claude 4 · per office panda 4 · 2 stages in flight per effort"
            one.filter { it.startsWith("stage ") } shouldBe listOf("stage KP/P2/S2.2: proposed · proposals 1")
            office.kp("status", "KP/P9/S9.9").text shouldBe "nothing on the log is 'KP/P9/S9.9': an effort, a stage or a seat\n"
        }
    }

    "status shows a reload that was refused, with its problems, and the rules that stay in force" {
        val rules = TestOffice.copyOfTheRules()
        TestOffice(root = rules).use { office ->
            val before = office.office.rules.hash
            val kinds = rules.resolve("rules/message-kinds.yaml")
            kinds.writeText(kinds.readText().replaceFirst("    from: teamlead\n", "    from: nobody-at-all\n"))

            val reload = office.office.reloadIfChanged()
            val again = office.office.reloadIfChanged()
            val status = office.kp("status").text

            reload shouldBe kp.mailroom.Reload.Rejected((reload as kp.mailroom.Reload.Rejected).problems)
            again shouldBe null
            office.office.rules.hash shouldBe before
            status shouldContain "reload refused at 2026-11-17T09:00:00Z — the rules ${before.take(12)} stay in force:\n   "
            status shouldContain "nobody-at-all"
        }
    }
})
