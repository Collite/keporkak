package kp.engine

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kp.model.Envelope
import kp.model.parseLine
import kp.model.utc
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kp.model.Delivered
import kp.model.Expired
import kp.model.Held
import kp.model.Message
import kp.model.Record
import kotlin.random.Random

/**
 * Valid logs, generated from a seed: messages between seats of two stages, each followed (or not yet) by its delivery,
 * refusal or hold; transitions with counters; expiries that re-arm or end a wait, some reported to Bora with a ticket;
 * reads; operations; rule sets; facts — in judgements, closed by `judged` lines, some voided, the last perhaps
 * unfinished. `seq` runs from 1; `at` moves forward and crosses UTC days.
 */
private class LogGenerator(seed: Int) {
    private val random = Random(seed)
    private val seats = listOf("coder-1", "coder-2", "teamlead", "principal", "reviewer-1", "bora", "daemon", "mailroom")
    private val stages = listOf("KP/P1/S1.1", "TOY/P0/S0.1")
    private val lines = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private val delivered = mutableMapOf<String, String>()
    private val waiting = mutableListOf<String>()
    private var clock = LocalDateTime.of(2026, 11, 14, 20, 0)
    private var ticket = 0
    /** The first line of the judgement no `judged` line has closed yet. */
    private var first: Int? = null

    private fun time(minutes: Long = 0) = clock.plusMinutes(minutes).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    private fun pick(list: List<String>) = list[random.nextInt(list.size)]
    private fun line(body: String) {
        clock = clock.plusMinutes(random.nextLong(1, 90))
        lines += """{"v":1,"seq":${lines.size + 1},"at":"${time()}",$body}"""
        if (first == null) first = lines.size
    }
    private fun maybe(chance: Int) = random.nextInt(100) < chance
    private fun counted() = """"counted":{"${pick(listOf("stops", "fix_rounds", "review_requests"))}":${random.nextInt(1, 5)}}"""
    private fun ticketField() = if (maybe(40)) ""","ticket":${++ticket}""" else ""

    fun generate(size: Int): List<Envelope> {
        while (lines.size < size) when (random.nextInt(12)) {
            0, 1, 2 -> message()
            3 -> line(""""event":"transition","machine":"${pick(listOf("stage", "found", "flag", "seat", "phase", "presence"))}","subject":"${pick(stages + seats)}","from":"${pick(listOf("a", "b", "c"))}","to":"${pick(listOf("a", "b", "c"))}","cause":"${messages.lastOrNull() ?: "fact"}"${if (maybe(50)) "," + counted() else ""}""")
            4 -> waiting.randomOrNull(random)?.let { id ->
                val again = maybe(50)
                line(""""event":"expired","id":"$id"${if (again) ""","deadline":"${time(200)}"""" else if (maybe(50)) ""","report":"bora","class":"C"${ticketField()}""" else ""}""")
                if (!again) waiting -= id
            }
            5 -> delivered.keys.randomOrNull(random)?.let { id -> line(""""event":"read","seat":"${delivered.getValue(id)}","ids":["$id"]""") }
            6 -> messages.randomOrNull(random)?.let { id ->
                val op = pick(listOf("git.merge", "seat.start"))
                val result = if (op == "seat.start") ""","result":{"seat":"${pick(seats)}"}""" else ""
                line(""""event":"op","op":"$op","phase":"${pick(listOf("requested", "ok", "failed"))}","cause":"$id","with":{"seat":"${pick(seats)}"}$result""")
            }
            7 -> line(""""event":"rules","hash":"${random.nextInt().toString(16)}","files":{"rules/engagement.yaml":"x"}""")
            9, 10 -> first?.let { opened -> line(""""event":"judged","first":$opened""").also { first = null } }
            11 -> first?.let { opened -> lines.size.let { last -> first = null; line(""""event":"voided","first":$opened,"last":$last""") } }
            else -> line(""""event":"fact","fact":"bora.present","subject":"bora","value":${random.nextBoolean()}""")
        }
        return lines.take(size).map(::parseLine)
    }

    private fun message() {
        val id = "m-panda-%06d".format(messages.size + 1)
        val from = pick(seats)
        val to = pick(seats - from)
        val re = if (maybe(40)) messages.randomOrNull(random) else null
        val deadline = if (maybe(50)) ""","deadline":"${time(120)}"""" else ""
        line(""""id":"$id","thread":"${re ?: id}",${re?.let { """"re":"$it",""" } ?: ""}"from":"$from","to":"$to","kind":"k","stage":"${pick(stages)}","subject":"s"$deadline,"created":"${time()}","by":"seat:$from"""")
        messages += id
        when (random.nextInt(4)) {
            0 -> line(""""event":"refused","id":"$id","rule":"k","requirement":"r"${if (maybe(50)) ""","refusals_today":${random.nextInt(1, 4)}""" else ""}""")
            1 -> {
                line(""""event":"held","id":"$id","by":"robot:DEP-1","until":"presence"${ticketField()}${if (maybe(50)) ""","deadline":null""" else ""}""")
                if (deadline.isNotEmpty()) waiting += id
            }
            2 -> {
                val answers = re?.takeIf { it in waiting && maybe(60) }
                val released = if (maybe(20)) ""","deadline":"${time(150)}"""" else ""
                line(""""event":"delivered","id":"$id","to":["$to"],"class":"A"${if (maybe(30)) "," + counted() else ""}${ticketField()}$released${answers?.let { ""","answers":"$it"""" } ?: ""}""")
                delivered[id] = to
                answers?.let { waiting -= it }
                if (deadline.isNotEmpty() || released.isNotEmpty()) waiting += id
            }
            else -> Unit
        }
    }
}

/** Every time the state holds: when it was last folded, since when each machine is in its state, waits, holds, tickets. */
private fun State.times(): List<String> = listOfNotNull(at) +
    machines.values.flatMap { subjects -> subjects.values.map { it.since } } +
    deadlines.values.mapNotNull { it.deadline } + held.values.map { it.since } + tickets.values.map { it.at } + sessions.values + operations.map { it.since }

class FoldDeterminismSpec : StringSpec({
    "a valid log cut at every line, folded in two parts, gives the state of folding it whole — and folding it twice changes nothing" {
        (1..150).forEach { seed ->
            val lines = LogGenerator(seed).generate(size = 10 + seed % 50)
            val whole = fold(lines)
            withClue("seed $seed") {
                fold(lines, whole) shouldBe whole
                (0..lines.size).forEach { cut -> fold(lines.drop(cut), fold(lines.take(cut))) shouldBe whole }
            }
        }
    }
    "every time in the state is a time of the log: taken from the records, never from a clock" {
        (1..150).forEach { seed ->
            val lines = LogGenerator(seed).generate(size = 10 + seed % 50)
            val state = folded(lines)
            val logged = lines.flatMap {
                listOfNotNull(it.at.value, (it as? Message)?.deadline?.value, (it as? Expired)?.deadline?.value, (it as? Delivered)?.deadline?.value, (it as? Held)?.deadline?.value)
            }
            withClue("seed $seed") {
                logged shouldContainAll state.times()
                lines.map { utc(it.at.value).toLocalDate().toString() } shouldContainAll state.refusals.values.map { it.day }
            }
        }
    }
    "the generated logs exercise every record and every question the state answers" {
        val states = (1..150).map { seed -> LogGenerator(seed).generate(size = 10 + seed % 50) }
        states.flatten().map { (it as? Record)?.event ?: "message" }.toSet() shouldBe
            setOf("message", "delivered", "refused", "held", "expired", "transition", "fact", "op", "rules", "read", "judged", "voided")
        val folded = states.map { folded(it) }
        listOf(
            folded.any { it.deadlines.values.any { wait -> wait.expiries > 0 } },
            folded.any { it.held.isNotEmpty() },
            folded.any { it.tickets.values.any { ticket -> ticket.answeredBy != null } },
            folded.any { it.unread.isNotEmpty() },
            folded.any { it.refusals.isNotEmpty() },
            folded.any { it.counters.isNotEmpty() },
            folded.any { it.deadlines.values.any { wait -> wait.deadline == null } },
            folded.any { it.answered.isNotEmpty() },
            folded.any { it.operations.isNotEmpty() },
            folded.any { it.sessions.isNotEmpty() },
        ) shouldBe List(10) { true }
    }
})
