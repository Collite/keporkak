package kp.daemon

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private val ROOT = Peer("root")

/** S1.5 T6 — `kp check` (contracts.md §6, Amendment 5): a daemon's log re-judged from its recorded facts; a hand log judged for what needs none. */
class CheckSpec : FreeSpec({
    "a daemon's log with a proposal, an unsigned and a signed approval and a refused second answer is reproduced line by line" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())
            office.kp("approve", "4")
            office.kp("approve", "4", peer = ROOT)
            office.kp("approve", "4", peer = ROOT)
            val mail = office.office.project.resolve("mail")

            val check = office.kp("check", mail.toString())

            check.code shouldBe 0
            check.text.lines() shouldContain "4 messages re-judged: every recorded outcome is reproduced"
        }
    }

    "one recorded fact edited, the message's line is printed with what was recorded and what is judged now" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())
            val copy = copyOf(office.office.project.resolve("mail"))
            edit(copy, """"event":"delivered","id":"m-panda-000003"""", """"list.tasks_total":8""", """"list.tasks_total":1""")

            val check = office.kp("check", copy.toString())

            check.code shouldBe 1
            check.text.lines().filter { "m-panda-000003" in it } shouldBe listOf(
                "$copy/KP/p2-s2.1.jsonl:1 m-panda-000003 assignment-proposal: recorded delivered T-01 · stage KP/P2/S2.1 planned→proposed by T-01 proposals=1 · class B · ticket" +
                    " · deadline 2026-11-18T09:00:00Z · counted proposals=1; re-judged refused assignment-proposal: " + office.office.rules.ruleSet.kinds.getValue("assignment-proposal").guard +
                    """ · failed {"fact":"list.tasks_total","gte":{"cap":"stage_tasks_min"}}""",
            )
            check.text.lines() shouldContain "1 message re-judged: 1 differs"
        }
    }

    "a log written under another rule set says so before its differences" {
        TestOffice().use { office ->
            office.send("teamlead", proposal())
            val copy = copyOf(office.office.project.resolve("mail"))
            edit(copy, """"event":"rules"""", """"hash":"""", """"hash":"0000000000000""")

            val lines = office.kp("check", copy.toString()).text.lines()

            lines[1] shouldBe "the log was written under rules 000000000000, not those in force: a difference may come from that"
        }
    }

    "S0.3's hand log reads facts a hand log does not record: its lines are not checked, and leave the stage planned" {
        TestOffice().use { office ->
            val hand = handLog("p0-s0.3.jsonl", """
                {"v":1,"seq":1,"at":"2026-11-14T12:50:10Z","id":"m-hand-000001","thread":"m-hand-000001","from":"coder","to":"teamlead","kind":"started","stage":"KP/P0/S0.3","subject":"started KP/P0/S0.3","created":"2026-11-14T12:50:10Z","by":"hand:coder"}
                {"v":1,"seq":2,"at":"2026-11-14T12:50:21Z","id":"m-hand-000002","thread":"m-hand-000002","from":"coder","to":"teamlead","kind":"review-request","stage":"KP/P0/S0.3","subject":"review-request KP/P0/S0.3","args":{"attest":["done_lines_hold"]},"created":"2026-11-14T12:50:21Z","by":"hand:coder"}
            """)

            val check = office.kp("check", hand.toString())

            check.code shouldBe 0
            check.text shouldContain "$hand:1 m-hand-000001 started: not checked — it reads git.branch_exists, which a hand log does not record; KP/P0/S0.3 is planned"
            check.text shouldContain "$hand:2 m-hand-000002 review-request: not checked — it reads pr.ready, which a hand log does not record; KP/P0/S0.3 is planned"
            check.text.lines() shouldContain "KP/P0/S0.3 is left in planned"
        }
    }

    "a hand-carried answer of Bora's is not checked for a signature it cannot carry; a stray sender is refused" {
        TestOffice().use { office ->
            val hand = handLog("p1-s1.8.jsonl", """
                {"v":1,"seq":1,"at":"2026-11-16T10:00:00Z","id":"m-hand-000045","thread":"m-hand-000045","from":"reviewer","to":"bora","kind":"classify-request","stage":"KP/P1/S1.8","subject":"blocker or follow-up","args":{"question":"B1?","default":"follow-up"},"created":"2026-11-16T10:00:00Z","by":"hand:reviewer"}
                {"v":1,"seq":2,"at":"2026-11-16T10:05:00Z","id":"m-hand-000046","thread":"m-hand-000045","re":"m-hand-000045","from":"bora","to":"reviewer","kind":"ruling","stage":"KP/P1/S1.8","subject":"re: blocker","args":{"outcome":"blocker"},"created":"2026-11-16T10:05:00Z","by":"hand:bora"}
                {"v":1,"seq":3,"at":"2026-11-16T10:06:00Z","id":"m-hand-000047","thread":"m-hand-000047","from":"coder","to":"bora","kind":"ruling","stage":"KP/P1/S1.8","subject":"a ruling","args":{"outcome":"blocker"},"created":"2026-11-16T10:06:00Z","by":"hand:coder"}
            """)

            val text = office.kp("check", hand.toString()).text

            text shouldContain "$hand:1 m-hand-000045 classify-request: delivered"
            text shouldContain "$hand:2 m-hand-000046 ruling: not checked — it needs a signature, which a hand-carried line does not have"
            text shouldContain "$hand:3 m-hand-000047 ruling: refused — from: bora"
            text.lines() shouldContain "not checked: signatures — a hand-carried line has none"
        }
    }

    "a file that is not a log — a token named by mistake — is reported by its place, never by its text" {
        TestOffice().use { office ->
            val token = handLog("claude-token", """
                sk-not-a-real-token-but-secret
                {"token": sk-another-secret
            """)

            val check = office.kp("check", token.toString())

            check.text shouldContain "$token:1: not a message or record of contracts.md §2 (a line is one JSON object)"
            check.text shouldContain "$token:2: not a message or record of contracts.md §2 (not JSON)"
            check.text shouldNotContain "secret"
        }
    }

    "a line that is no message or record is reported, and a path that is not there" {
        TestOffice().use { office ->
            val broken = handLog("p9-s9.9.jsonl", """{"v":1,"seq":1,"kind":"started"}""")
            val missing = broken.resolveSibling("nowhere.jsonl")

            val check = office.kp("check", broken.toString(), missing.toString())

            check.code shouldBe 1
            check.text shouldContain "$broken:1: not a message or record of contracts.md §2"
            check.text shouldContain "$missing: no such file or folder"
            check.text shouldNotContain "re-judged"
        }
    }
    "a hand log that moves a stage says the state it leaves it in; a later line refused for that state is a slip" {
        val rules = TestOffice.copyOfTheRules().also(::proposalsReadNoFact)
        TestOffice(root = rules).use { office ->
            val hand = Files.createTempDirectory(checkout.resolve("build"), "hand-").resolve("p9-s9.1.jsonl").apply {
                writeText("""
                    {"v":1,"seq":1,"at":"2026-11-17T09:00:00Z","id":"m-hand-000001","thread":"m-hand-000001","from":"teamlead","to":"bora","kind":"assignment-proposal","stage":"KP/P9/S9.1","subject":"a proposal","args":{"role":"coder","level":"senior","seat":"coder-1"},"created":"2026-11-17T09:00:00Z","by":"hand:teamlead"}
                    {"v":1,"seq":2,"at":"2026-11-17T09:05:00Z","id":"m-hand-000002","thread":"m-hand-000002","from":"coder","to":"reviewer","kind":"repair-done","stage":"KP/P9/S9.1","subject":"repair-done","args":{"verdict":"m-hand-000000"},"created":"2026-11-17T09:05:00Z","by":"hand:coder"}
                """.trimIndent() + "\n")
            }

            val check = office.kp("check", hand.toString())

            check.text.lines() shouldContain "$hand:1 m-hand-000001 assignment-proposal: delivered T-01 · stage KP/P9/S9.1 planned→proposed"
            check.text.lines() shouldContain "$hand:2 m-hand-000002 repair-done: refused — fix_round|fix_round_3|fix_round_4→re_review — KP/P9/S9.1 is proposed"
            check.text.lines() shouldContain "KP/P9/S9.1 is left in proposed"
            check.text shouldNotContain "middle"
            check.code shouldBe 1
        }
    }
})

private fun handLog(name: String, lines: String): Path =
    Files.createTempDirectory(checkout.resolve("build").createDirectories(), "hand-").resolve(name).also { it.writeText(lines.trimIndent() + "\n") }

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun copyOf(mail: Path): Path = Files.createTempDirectory(checkout.resolve("build"), "copy-").resolve("mail").also { mail.copyToRecursively(it, followLinks = false, overwrite = false) }

/** In a copy of the rules: the proposal and T-01 read no fact, so a hand log can move a stage out of `planned`. */
private fun proposalsReadNoFact(rules: Path) {
    fun replace(file: String, from: String, to: String) {
        val path = rules.resolve(file)
        val text = path.toFile().readText()
        check(text.split(from).size == 2) { "'${from.lines().first()}…' is not once in $file" }
        path.toFile().writeText(text.replace(from, to))
    }
    val facts = "  - fact: plan.predecessors_exited\n  - fact: pool.seat_available\n    role:\n      arg: role\n    level:\n      arg: level\n" +
        "  - fact: budget.slot_open\n    dimension: office\n  - fact: budget.slot_open\n    dimension: subscription\n  - fact: budget.slot_open\n    dimension: effort\n" +
        "  - fact: list.fence_complete\n"
    replace("rules/stage-lifecycle.yaml", "  require:\n$facts  effects:\n  - count: proposals\n", "  require: []\n  effects:\n  - count: proposals\n")
    val sizes = "    - fact: list.tasks_total\n      gte:\n        cap: stage_tasks_min\n    - fact: list.tasks_total\n      lte:\n        cap: stage_tasks_max\n" +
        "    - fact: list.lines\n      lte:\n        cap: stage_list_lines\n"
    replace("rules/message-kinds.yaml", "    require:\n" + facts.lines().filter { it.isNotEmpty() }.joinToString("") { "  $it\n" } + sizes + "    expiry:\n    - transition: T-07\n", "    require: []\n    expiry:\n    - transition: T-07\n")
}
