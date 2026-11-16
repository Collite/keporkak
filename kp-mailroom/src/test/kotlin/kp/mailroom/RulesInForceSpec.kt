package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.model.Refused
import kp.model.Rules
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** `sha256sum`'s hex digest of [text]: an oracle that shares no code with the mailroom. */
private fun sha256sum(text: String): String {
    val process = ProcessBuilder("sha256sum").start()
    process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return process.inputStream.bufferedReader().readText().substringBefore(' ').also { process.waitFor() }
}

/** T7 — the rules in force: through `kp-rules` at start and on reload; a `rules` record with the hashes of §2.2. */
class RulesInForceSpec : StringSpec({
    afterTest { Office.closeAll() }

    "at start the rules record names every file of the set with its SHA-256, and the set's hash is that of the lines `<path> <hash>`, sorted" {
        val office = Office()
        val texts = office.texts.read()

        val record = office.readBack().rules().single()

        record.files.keys shouldBe texts.keys
        record.files.getValue("rules/engagement.yaml") shouldBe sha256sum(texts.getValue("rules/engagement.yaml"))
        record.files.getValue("agents/coder/AGENT.md") shouldBe sha256sum(texts.getValue("agents/coder/AGENT.md"))
        record.hash shouldBe sha256sum(texts.keys.sorted().joinToString("") { "$it ${sha256sum(texts.getValue(it))}\n" })
        office.mailroom.state.rules shouldBe record.hash
    }

    "a mailroom whose rules are not valid does not start, and writes nothing" {
        val office = Office()
        office.stop()
        val before = office.readBack().lines.size
        office.texts.edit("rules/message-kinds.yaml", "  review-request:\n", "  review-request:\n    max_fires: many\n")

        shouldThrow<MailroomRefusal> { office.start() }.message shouldContain "rules/message-kinds.yaml"

        office.readBack().lines.size shouldBe before
        Office.opened.remove(office)
    }

    "an accepted reload records the set even when nothing changed; a rejected one writes nothing" {
        val office = Office()

        val same = office.mailroom.reload()
        office.texts.edit("rules/vocabulary.yaml", "facts:", "facts: [")
        val broken = office.mailroom.reload()

        office.readBack().rules().map { it.hash } shouldBe listOf((same as Reload.Accepted).hash, same.hash)
        (broken as Reload.Rejected).problems.map { it.file }.toSet() shouldBe setOf("rules/vocabulary.yaml")
    }

    "a reload waits for the message being carried, which is judged under the set it began with" {
        val office = Office()
        val judging = CountDownLatch(1)
        val release = CountDownLatch(1)
        office.stop()
        office.mailroom = Mailroom.start(office.project, panda, office.texts, { fact, stage ->
            if (fact.name == "plan.predecessors_exited") { judging.countDown(); release.await(10, TimeUnit.SECONDS) }
            office.facts.observe(fact, stage)
        }, office.operations, office.seats, office.clock)
        val twoLines = """{"kind": "assignment-proposal", "stage": "$STAGE", "subject": "propose", "body": "one\ntwo", "args": {"role": "coder", "level": "senior", "seat": "coder-1"}}"""
        office.texts.edit("rules/engagement.yaml", "body_lines: 40", "body_lines: 1")

        var answer: Answer? = null
        val sender = thread { answer = office.send("teamlead", twoLines) }
        judging.await(10, TimeUnit.SECONDS)
        var reloaded: Reload? = null
        val reloader = thread { reloaded = office.mailroom.reload() }
        reloader.join(300)
        val reloadedWhileJudging = reloaded
        release.countDown()
        sender.join()
        reloader.join()

        reloadedWhileJudging shouldBe null
        answer!!.status shouldBe Status.DELIVERED
        office.readBack().lines.last()::class.simpleName shouldBe "Rules"
        office.send("teamlead", twoLines).requirement shouldBe "caps.body_lines: 1"
        office.readBack().records<Refused>().size shouldBe 1
        office.readBack().records<Rules>().size shouldBe 3 // two starts and the reload
    }
})
