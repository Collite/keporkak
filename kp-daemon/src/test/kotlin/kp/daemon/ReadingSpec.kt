package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kp.model.Delivered
import kp.model.Transition
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
            val (one, two) = listOf(0, 1).map { office.ticket(office.id("assignment-proposal", it)) }

            first.lines().first() shouldStartWith "#$one · B · assignment-proposal · KP/P2/S2.1"
            office.kp("inbox").text.lines().filter { it.startsWith("#") } shouldBe listOf(
                "#$one · B · assignment-proposal · KP/P2/S2.1 · from teamlead",
                "#$two · B · assignment-proposal · KP/P2/S2.2 · from teamlead",
            )
        }
    }

    "an item that asks Bora to choose shows its default; one no longer waiting shows only with --all" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())
            office.send("principal", """{"kind": "ruling-request", "stage": "KP/P2/S2.1", "subject": "which stage owns it", "args": {"default": "cut the owning stage", "citation": "contracts.md §4.5"}}""")
            val proposal = office.id("assignment-proposal")
            office.office.submit(kp.mailroom.Sender.Bora(signed = true), json("""{"kind": "approval", "re": "$proposal", "stage": "KP/P2/S2.1"}"""))

            office.kp("inbox").text shouldBe """
                #${office.ticket(office.id("ruling-request"))} · B · ruling-request · KP/P2/S2.1 · from principal
                   rolls: none · live: none · touches: unknown
                   default: cut the owning stage

            """.trimIndent()
            office.kp("inbox", "--all").text.lines() shouldContain
                "#${office.ticket(proposal)} · B · assignment-proposal · KP/P2/S2.1 · from teamlead — answered by ${office.id("approval")}"
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
            val (one, two) = listOf(0, 1).map { office.ticket(office.id("assignment-proposal", it)) }
            office.close()
            follower.join(5_000)

            first shouldBe "#$one · B · assignment-proposal · KP/P2/S2.1 · from teamlead"
            next shouldBe "#$two · B · assignment-proposal · KP/P2/S2.2 · from teamlead"
            follower.isAlive shouldBe false
        }
    }

    "kp status is unchanged by a voided judgement's lines: it says what the log without them says" {
        // Killed after the proposal's transition: the start voids its delivered record and the transition — their lines
        // read from an uncut run, not pinned (S3.0 T5).
        val lines = TestOffice().use { office -> office.send("teamlead", proposal()); office.office.lines().map { it.entry } }
        val delivered = lines.single { it is Delivered && it.id == idOf(lines, "assignment-proposal") }.seq.toLong()
        val transition = lines.first { it is Transition && it.seq.toLong() > delivered }.seq.toLong()
        val project = killedAfter(cut = transition) { it.send("teamlead", proposal()) }
        TestOffice(project = project).close()
        val withoutTheVoided = copyWithout(project, seqs = setOf(delivered, transition))

        val status = TestOffice(project = project).use { it.kp("status").text }

        TestOffice(project = withoutTheVoided).use { it.kp("status").text } shouldBe status
        status shouldContain "stage KP/P2/S2.1: proposed · proposals 1"
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

            val (first, second) = listOf(0, 1).map { office.ticket(office.id("assignment-proposal", it)) }
            all shouldContainAll listOf("Bora: present", "stage KP/P2/S2.1: proposed · proposals 1", "stage KP/P2/S2.2: proposed · proposals 1", "open tickets: #$first, #$second")
            all.first() shouldStartWith "office panda · rules "
            // The budgets line names every office of the pool: this office among them (one, two, three: TheOfficeIsDskSpec).
            val budgets = all.single { it.startsWith("budgets: ") }
            budgets shouldStartWith "budgets: 4 slots · per subscription claude 4 · per office "
            budgets.substringAfter(" · per office ").substringBefore(" · ").split(", ") shouldContain "panda 4"
            budgets shouldEndWith " · 2 stages in flight per effort"
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
