package kp.mailroom

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kp.model.Delivered
import kp.model.Fact
import kp.model.parseLine
import kotlin.io.path.readLines
import kotlin.time.Duration.Companion.hours

private const val APPROVAL = """{"kind": "approval", "stage": "$STAGE", "subject": "approved"}"""

/** Where a run ends, and whether its restart recorded an operation `failed`, `interrupted`. */
private data class Ending(val cut: Long?, val end: List<String>, val interrupted: Boolean)

/**
 * A fresh office [made], [setUp], its writer then stopped after line [cut] (never, when null) while it [acts], and
 * started again — as Panda's service would after a kill there.
 */
private fun ending(cut: Long?, made: () -> Office, setUp: (Office) -> Unit, acts: (Office) -> Unit): Ending {
    val office = made()
    setUp(office)
    office.stopWriterAfter { line -> line.seq.toLong() == cut }
    try {
        acts(office)
    } catch (_: WriterStopped) {
        // killed there
    }
    office.restart()
    return Ending(cut, office.end(), office.readBack().counted.any { it is kp.model.Op && it.reason == "interrupted" })
}

/**
 * Every cut of what [acts] writes, each started again: those that end neither as the uninterrupted run, nor on an
 * operation in flight (recorded `failed`, `interrupted`), nor on a wake in flight — a cut before a `read` record the
 * uninterrupted run wrote after a wake: a seat is woken once its message's judgement is on disk and a start wakes no one
 * again (a wake's own record is S2.4's, Amendment 22), so its message stays unread, and only that differs.
 */
private fun cutsEndingOtherwise(made: () -> Office, setUp: (Office) -> Unit = {}, acts: (Office) -> Unit): List<Ending> {
    val whole = made().also(setUp).also { it.stopWriterAfter { false } }
    val from = whole.readBack().lines.size + 1L
    acts(whole)
    val lines = whole.readBack().lines
    whole.restart()
    val uninterrupted = whole.end()
    fun beforeARead(cut: Long?) = lines.any { it.seq.toLong() > cut!! && (it as? kp.model.Record)?.event == "read" }
    fun read(end: List<String>) = end.filterNot { it.startsWith("unread ") }
    return (from..lines.size.toLong()).map { cut -> ending(cut, made, setUp, acts) }
        .filter { it.end != uninterrupted && !it.interrupted && !(beforeARead(it.cut) && read(it.end) == read(uninterrupted)) }
}

/** T5 — a start voids an unfinished judgement and carries its first line again (contracts.md §3, Amendment 23). */
class AStartCarriesAgainSpec : StringSpec({
    afterTest { Office.checkAndCloseAll() }

    "a message's judgement stopped after any of its lines — the rules' emit among them — ends as the uninterrupted one" {
        cutsEndingOtherwise({ Office(seed = "building", robots = true) }) { it.play("review-request") }.shouldBeEmpty()
    }

    "a fact's judgement stopped after any of its lines — the robots' send and the result it asked for among them — ends as the uninterrupted one, or on the operation in flight" {
        cutsEndingOtherwise({ Office(seed = "building", robots = true) }) {
            it.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
        }.shouldBeEmpty()
    }

    "an operation's result stopped after any of its judgement's lines ends as the uninterrupted one, or on the operation in flight" {
        cutsEndingOtherwise({ Office(seed = "proposed") }) { it.bora(APPROVAL) }.shouldBeEmpty()
    }

    "an expiry's judgement stopped after any of its lines ends as the uninterrupted one" {
        cutsEndingOtherwise({ Office(seed = "building") }, setUp = { it.play("review-request") }) { office ->
            office.clock.advance(1.hours)
            office.mailroom.tick()
        }.shouldBeEmpty()
    }

    "a judgement stopped again while its first line is carried again still ends as the uninterrupted one" {
        val uninterrupted = Office(seed = "building", robots = true).apply { play("review-request") }.end()
        val office = Office(seed = "building", robots = true)
        office.stopWriterAfter { it is Delivered }
        shouldThrow<WriterStopped> { office.play("review-request") }
        office.stop()

        shouldThrow<WriterStopped> { office.startStoppedAfter { it is Delivered } }
        office.start()

        office.readBack().voidedJudgements().map { it.first() } shouldBe listOf("review-request by seat:coder-1", "voided")
        office.end() shouldBe uninterrupted
    }

    "a request whose judgement was stopped before its judged line stands, and the start records it failed, interrupted" {
        val office = Office()
        office.stopWriterAfter { it is kp.model.Op }
        office.texts.edit("rules/vocabulary.yaml", "facts:", "facts: [")
        shouldThrow<WriterStopped> { office.mailroom.reload() } // the refused reload's notify.bora: requested, its judged line never written
        office.texts.edit("rules/vocabulary.yaml", "facts: [", "facts:")

        office.restart()

        office.readBack().ops("rules") shouldBe listOf("notify.bora requested", "notify.bora failed interrupted")
        office.operations.requested shouldBe emptyList()
    }

    "a fact record carries its params and its stage, and goes to that stage's file (contracts.md §2.2, Amendment 24)" {
        val office = Office(seed = "building", robots = true)

        office.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))

        office.readBack().lines.filterIsInstance<Fact>().map { "${it.fact} ${it.subject} ${it.params} ${it.stage}" } shouldBe
            listOf("""seat.process_gone coder-1 {"seat":"coder-1"} $STAGE""")
        office.project.resolve("mail/KP/p2-s2.1.jsonl").readLines().map(::parseLine).filterIsInstance<Fact>().map { "${it.fact} ${it.subject}" } shouldBe
            listOf("seat.process_gone coder-1")
    }

    "a start carries a fact again with the params and the stage its record names, whatever the vocabulary's params: say" {
        fun gone(office: Office) = office.changed("seat.process_gone", true, stage = STAGE, params = mapOf("seat" to "coder-1"))
        val uninterrupted = Office(seed = "building", robots = true).apply { gone(this) }.end()
        val office = Office(seed = "building", robots = true)
        office.stopWriterAfter { it is Fact }
        gone(office) // a fact has no sender: stopped, it goes to the journal and to no caller (contracts.md §3, Amendment 29)
        office.journal.single() shouldContain "the writer was stopped here"
        office.stop()

        office.texts.edit("rules/vocabulary.yaml", "  seat.process_gone:\n    params: seat\n", "  seat.process_gone:\n    params: the seat whose process is gone\n")
        office.start()

        office.readBack().voidedJudgements().map { it.first() } shouldBe listOf("fact seat.process_gone")
        office.end() shouldBe uninterrupted
    }

    "at a start the rules record comes first: alone, or right after the voided record, before what that carries again" {
        val office = Office(seed = "building", robots = true)
        office.stopWriterAfter { it is Delivered }
        shouldThrow<WriterStopped> { office.play("review-request") }

        office.restart()
        office.restart()

        office.readBack().judgements().filter { it.first() == "voided" || it.first() == "rules" }.takeLast(2).map { it.take(3) } shouldBe listOf(
            listOf("voided", "rules", "delivered"),
            listOf("rules", "judged"),
        )
    }
})
