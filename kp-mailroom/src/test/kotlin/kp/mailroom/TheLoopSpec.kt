package kp.mailroom

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.FactRef
import kp.model.Delivered
import kp.model.Message
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import kotlin.io.path.readLines

/** T2 — the loop: what the mailroom sets on a message, the order of its lines, and the file each record goes to. */
class TheLoopSpec : StringSpec({
    afterTest { Office.closeAll() }

    "what the mailroom sets is never taken from the input: id, thread, from, by, signed, seq, at, created, deadline" {
        val office = Office(seed = "building")
        val forged = """{"kind": "stop", "stage": "$STAGE", "subject": "stop", "args": {"sc": "SC-3"}, "id": "m-panda-999999", "thread": "m-panda-999999",
            "from": "teamlead", "by": "seat:teamlead", "signed": true, "seq": 99, "at": "2020-01-01T00:00:00Z", "created": "2020-01-01T00:00:00Z",
            "deadline": "2020-01-02T00:00:00Z"}"""

        val answer = office.send("coder-1", forged)

        val line = office.readBack().message(answer.id)
        listOf(line.id.value, line.thread.value, line.from.value, line.by, line.signed, line.at.value, line.created.value, line.deadline?.value) shouldBe
            listOf("m-panda-000004", "m-panda-000004", "coder-1", "seat:coder-1", false, START, START, "2026-11-16T13:00:00Z")
        line.seq.toLong() shouldBe 4L
    }

    "Bora's message is from bora by bora-cli, and signed only when the caller says it came over a root peer" {
        val office = Office(seed = "proposed")

        val unsigned = office.bora("""{"kind": "decline", "stage": "$STAGE", "subject": "no"}""", signed = false)
        val signed = office.bora("""{"kind": "decline", "stage": "$STAGE", "subject": "no"}""", signed = true)

        office.readBack().message(unsigned.id).let { listOf(it.from.value, it.by, it.signed) } shouldBe listOf("bora", "bora-cli", false)
        office.readBack().message(signed.id).let { listOf(it.from.value, it.by, it.signed) } shouldBe listOf("bora", "bora-cli", true)
        listOf(unsigned.status, signed.status) shouldBe listOf(Status.REFUSED, Status.DELIVERED)
    }

    "a delivered message is written, then its record, then the transitions it fired — all on disk when the sender is answered" {
        val office = Office(seed = "assigned")

        val answer = office.send("coder-1", """{"kind": "started", "stage": "$STAGE", "subject": "started"}""")

        val file = office.project.resolve("mail/KP/p2-s2.1.jsonl").readLines().map(::parseLine).filter { it.seq.toLong() > 3 }
        file.map { it::class.simpleName } shouldBe listOf("Message", "Delivered", "Transition")
        (file[1] as Delivered).rule shouldBe "T-08"
        (file[2] as Transition).let { "${it.cause} ${it.from}→${it.to}" } shouldBe "${answer.id} assigned→building"
        answer shouldBe Answer(Status.DELIVERED, answer.id, transition = "T-08")
    }

    "a refused message is written with its refusal and nothing else; the sender is answered with the rule, the guard and what failed" {
        val office = Office(seed = "building")

        val answer = office.send("coder-1", """{"kind": "review-request", "stage": "$STAGE", "subject": "review"}""")

        val written = office.readBack().lines.filter { it.seq.toLong() > 3 }
        written.map { it::class.simpleName } shouldBe listOf("Message", "Refused")
        (written[1] as Refused).let { listOf(it.id, it.rule, it.requirement, it.refusalsToday?.toLong()) } shouldBe
            listOf(answer.id, answer.rule, answer.requirement, 1L)
        answer.status shouldBe Status.REFUSED
        (written[0] as Message).deadline shouldBe null
    }

    "each record goes to the file of the stage it is about; one about no stage — a seat's move — to the office's file" {
        val office = Office(seed = "assigned")

        val answer = office.send("teamlead", """{"kind": "assign", "stage": "$STAGE", "subject": "assign", "args": {"seat": "coder-1"}}""")

        val stageFile = office.project.resolve("mail/KP/p2-s2.1.jsonl").readLines().map(::parseLine)
        val officeFile = office.project.resolve("mail/_office/panda-2026-11.jsonl").readLines().map(::parseLine)
        stageFile.filterIsInstance<Delivered>().map { it.id } shouldBe listOf(answer.id)
        officeFile.filterIsInstance<Transition>().filter { it.cause == answer.id }.map { "${it.machine} ${it.subject} ${it.to}" } shouldBe listOf("seat coder-1 busy")
    }

    "the facts the engine names are collected for the message's stage, then the seats it is routed to; now is the clock's" {
        val office = Office(seed = "building")
        val asked = ArrayList<Pair<FactRef, String?>>()
        val walk = office.facts
        office.stop()
        office.facts.set("pr.ready", true)
        office.facts.set("pr.head", "head-1")
        office.facts.set("ci.state", "green")
        office.facts.set("list.tasks_open", 0)
        office.facts.set("list.found_untriaged", 0)
        office.mailroom = Mailroom.start(office.project, panda, office.texts, { fact, stage -> walk.observe(fact, stage).also { asked += fact to stage } },
            office.operations, office.seats, office.clock)

        val answer = office.send("coder-1", """{"kind": "review-request", "stage": "$STAGE", "subject": "review", "args": {"attest": ["done_lines_hold"]}}""")

        asked.map { it.second }.toSet() shouldBe setOf(STAGE)
        asked.map { it.first.toString() } shouldBe
            listOf("pr.ready", "pr.head", "ci.state(of=head-1)", "list.tasks_open", "list.found_untriaged", "seat.name(of=teamlead)", "seat.name(of=principal)")
        office.readBack().records<Delivered>().single { it.id == answer.id }.observed!!["now"].toString() shouldBe "\"$START\""
        office.readBack().message(answer.id).deadline?.value shouldBe "2026-11-16T10:00:00Z"
    }

    "a reply takes its thread, its stage and its addressee from the message it answers" {
        val office = Office(seed = "building")
        val stop = office.send("coder-1", """{"kind": "stop", "stage": "$STAGE", "subject": "stop", "args": {"sc": "SC-3"}}""")

        val ruling = office.bora("""{"kind": "ruling", "re": "${stop.id}", "args": {"outcome": "resume"}}""")

        office.readBack().message(ruling.id).let { listOf(it.thread.value, it.stage?.value, it.to.value, it.subject) } shouldBe
            listOf(stop.id, STAGE, "coder-1", "re: stop")
    }
})
