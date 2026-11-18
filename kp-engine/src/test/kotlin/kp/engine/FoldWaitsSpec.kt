package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun send(id: String, kind: String, from: String, to: String, re: String? = null, deadline: String? = null, thread: String = re ?: id) =
    """{"id":"$id","thread":"$thread",""" + (re?.let { """"re":"$it",""" } ?: "") +
        """"from":"$from","to":"$to","kind":"$kind","stage":"KP/P1/S1.1","subject":"$kind",""" +
        (deadline?.let { """"deadline":"$it",""" } ?: "") + """"created":"2026-11-14T09:00:00Z","by":"seat:$from"}"""
private fun delivered(id: String, vararg to: String, ticket: Int? = null, answers: String? = null) =
    """{"event":"delivered","id":"$id","to":[${to.joinToString(",") { "\"$it\"" }}],"class":"A"""" + (ticket?.let { ""","ticket":$it""" } ?: "") +
        (answers?.let { ""","answers":"$it"""" } ?: "") + "}"
private fun expired(id: String, deadline: String?, extra: String = "") =
    """{"event":"expired","id":"$id","rule":"T-74"""" + (deadline?.let { ""","deadline":"$it"""" } ?: "") + "$extra}"

class FoldWaitsSpec : StringSpec({
    "a delivered message with a deadline waits; the delivered record that answers it ends the wait, a reply that does not answer it leaves it" {
        val lines = log(
            send("m-panda-000001", "assign", "teamlead", "coder-1", deadline = "2026-11-14T09:15:00Z"),
            delivered("m-panda-000001", "coder-1"),
            send("m-panda-000002", "nudge", "mailroom", "teamlead", re = "m-panda-000001"),
            delivered("m-panda-000002", "teamlead"),
            send("m-panda-000003", "started", "coder-1", "teamlead", re = "m-panda-000001"),
            delivered("m-panda-000003", "teamlead", answers = "m-panda-000001"),
        )
        folded(lines.take(4)).deadlines shouldBe mapOf("m-panda-000001" to Wait("assign", "KP/P1/S1.1", "teamlead", "coder-1", "2026-11-14T09:15:00Z", 0))
        folded(lines).deadlines shouldBe emptyMap()
    }
    "an expiry with a new deadline re-arms the wait and counts it; an expiry without one ends it" {
        val lines = log(
            send("m-panda-000001", "review-request", "coder-1", "teamlead", deadline = "2026-11-14T10:00:00Z"),
            delivered("m-panda-000001", "teamlead"),
            expired("m-panda-000001", "2026-11-14T11:00:00Z"),
            expired("m-panda-000001", null, extra = ""","report":"bora","class":"C","ticket":4"""),
        )
        folded(lines.take(3)).deadlines.getValue("m-panda-000001").let { listOf(it.deadline, it.expiries) } shouldBe listOf("2026-11-14T11:00:00Z", 1)
        folded(lines).deadlines shouldBe emptyMap()
    }
    "a request to the daemon waits until its operation's result" {
        val lines = log(
            send("m-panda-000001", "merge", "teamlead", "daemon", deadline = "2026-11-14T09:15:00Z"),
            delivered("m-panda-000001", "daemon"),
            """{"event":"op","op":"git.merge","phase":"requested","cause":"m-panda-000001"}""",
            """{"event":"op","op":"git.merge","phase":"ok","cause":"m-panda-000001"}""",
        )
        folded(lines.take(3)).deadlines.keys shouldBe setOf("m-panda-000001")
        folded(lines).deadlines shouldBe emptyMap()
    }
    "a held message stays held until it is delivered, refused, or its wait ends; its deadline runs while it is held" {
        val lines = log(
            send("m-panda-000001", "classify-request", "reviewer-1", "bora", deadline = "2026-11-15T09:00:00Z"),
            """{"event":"held","id":"m-panda-000001","by":"robot:DEP-1","until":"presence","ticket":2}""",
            send("m-panda-000002", "classify-request", "reviewer-1", "bora", deadline = "2026-11-15T09:00:00Z"),
            """{"event":"held","id":"m-panda-000002","by":"robot:DEP-1","until":"presence"}""",
            delivered("m-panda-000001", "bora"),
            expired("m-panda-000002", null),
        )
        folded(lines.take(4)).held shouldBe mapOf(
            "m-panda-000001" to Hold(by = "robot:DEP-1", until = "presence", since = "2026-11-14T09:01:00Z"),
            "m-panda-000002" to Hold(by = "robot:DEP-1", until = "presence", since = "2026-11-14T09:03:00Z"),
        )
        folded(lines.take(4)).deadlines.keys shouldBe setOf("m-panda-000001", "m-panda-000002")
        folded(lines).held shouldBe emptyMap()
        folded(lines).deadlines.keys shouldBe setOf("m-panda-000001")
    }
    "a ticket is raised by the record that carries it and answered by the delivered record that answers its item; another reply of Bora's leaves it open" {
        val lines = log(
            send("m-panda-000001", "classify-request", "reviewer-1", "bora", deadline = "2026-11-15T09:00:00Z"),
            delivered("m-panda-000001", "bora", ticket = 7),
            send("m-panda-000002", "away", "bora", "mailroom", re = "m-panda-000001"),
            delivered("m-panda-000002", "mailroom"),
            send("m-panda-000003", "ruling", "bora", "reviewer-1", re = "m-panda-000001"),
            delivered("m-panda-000003", "reviewer-1", answers = "m-panda-000001"),
        )
        folded(lines.take(4)).openTickets() shouldBe mapOf(7L to Ticket(item = "m-panda-000001", raisedBy = "delivered", at = "2026-11-14T09:01:00Z"))
        folded(lines).tickets shouldBe mapOf(7L to Ticket(item = "m-panda-000001", raisedBy = "delivered", at = "2026-11-14T09:01:00Z", answeredBy = "m-panda-000003"))
        folded(lines).openTickets() shouldBe emptyMap()
    }
    "holding a message suspends its deadline; its release carries the deadline that then applies — the last one any line about it carries" {
        val lines = log(
            send("m-panda-000001", "classify-request", "reviewer-1", "bora", deadline = "2026-11-15T09:00:00Z"),
            """{"event":"held","id":"m-panda-000001","by":"robot:DEP-1","until":"presence","deadline":null}""",
            """{"event":"delivered","id":"m-panda-000001","to":["bora"],"class":"B","deadline":"2026-11-16T10:00:00Z"}""",
        )
        folded(lines.take(2)).deadlines.getValue("m-panda-000001").deadline shouldBe null
        folded(lines).deadlines.getValue("m-panda-000001").deadline shouldBe "2026-11-16T10:00:00Z"
    }
    "the operations in flight: each requested and with no result yet, in the order requested" {
        val lines = log(
            """{"event":"op","op":"git.merge","phase":"requested","cause":"m-panda-000001","with":{"sha":"abc"}}""",
            """{"event":"op","op":"worktree.create","phase":"requested","cause":"m-panda-000002"}""",
            """{"event":"op","op":"git.merge","phase":"failed","cause":"m-panda-000001","reason":"interrupted"}""",
        )
        folded(lines.take(2)).operations shouldBe listOf(
            InFlight("git.merge", "m-panda-000001", Datum.Fields(mapOf("sha" to Datum.Text("abc"))), "2026-11-14T09:00:00Z"),
            InFlight("worktree.create", "m-panda-000002", Datum.Fields(emptyMap()), "2026-11-14T09:01:00Z"),
        )
        folded(lines).operations.map { it.op } shouldBe listOf("worktree.create")
    }
    "per seat, what was delivered and not read, in the order it was delivered" {
        val state = folded(log(
            send("m-panda-000001", "assign", "teamlead", "coder-1"),
            delivered("m-panda-000001", "coder-1", "qa-panda"),
            send("m-panda-000002", "nudge", "mailroom", "coder-1"),
            delivered("m-panda-000002", "coder-1"),
            """{"event":"read","seat":"coder-1","ids":["m-panda-000001"]}""",
        ))
        state.unread shouldBe mapOf("coder-1" to listOf("m-panda-000002"), "qa-panda" to listOf("m-panda-000001"))
        state.unread("teamlead") shouldBe emptyList()
    }
    "a thread's hops are its delivered messages; a refused one is not a hop" {
        val state = folded(log(
            send("m-panda-000001", "stop", "coder-1", "principal"),
            delivered("m-panda-000001", "principal"),
            send("m-panda-000002", "triage", "principal", "coder-1", re = "m-panda-000001"),
            """{"event":"refused","id":"m-panda-000002","rule":"triage","requirement":"args.outcome is present"}""",
            send("m-panda-000003", "triage", "principal", "coder-1", re = "m-panda-000001"),
            delivered("m-panda-000003", "coder-1"),
        ))
        state.hops("m-panda-000001") shouldBe 2
        state.hops("m-panda-000009") shouldBe 0
    }
    "a seat's refusals of the day are the count its last refusal that day carries, the day taken in UTC from its at" {
        val refusal = { id: String, at: String, today: Int -> """{"event":"refused","at":"$at","id":"$id","rule":"stop","requirement":"r","refusals_today":$today}""" }
        val state = folded(log(
            send("m-panda-000001", "stop", "coder-1", "principal"),
            refusal("m-panda-000001", "2026-11-14T09:01:00Z", 1),
            send("m-panda-000002", "stop", "coder-1", "principal"),
            refusal("m-panda-000002", "2026-11-14T23:30:00-02:00", 1),
            send("m-panda-000003", "stop", "coder-1", "principal"),
            refusal("m-panda-000003", "2026-11-15T02:00:00Z", 2),
        ))
        state.refusalsOn("coder-1", "2026-11-15") shouldBe 2L
        state.refusalsOn("coder-2", "2026-11-15") shouldBe 0L
    }
})
