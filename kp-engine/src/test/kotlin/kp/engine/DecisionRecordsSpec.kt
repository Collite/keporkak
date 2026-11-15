package kp.engine

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kp.engine.Trigger.Arrival
import kp.model.Delivered
import kp.model.Refused
import kp.model.Transition
import kp.model.parseLine
import kp.model.toLine
import kp.rules.RuleSet
import java.io.File

private val building = fold(log(move("stage", STAGE, "assigned", "building")))
private fun review(id: String = "m-panda-000100") = Arrival(message("review-request", from = "coder-1", id = id, args = """{"attest": ["done_lines_hold"]}"""))
private fun ready(found: Int = 0) = table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "green", "list.tasks_open" to 0, "list.found_untriaged" to found)

/** The names of the facts a record's `observed` holds, read from the line as kp-model writes it. */
private fun observed(record: kp.model.Envelope): Set<String> =
    (((Datum.parse(record.toLine()) as Datum.Fields).fields.getValue("observed")) as Datum.Fields).fields.keys

/** A record line with `v`, `seq` and `at`, read back by kp-model — which validates it against contracts.md §2.2. */
private fun read(record: String) = parseLine("""{"v":1,"seq":7,"at":"$NOW",""" + record.removePrefix("{"))

/**
 * Review-requests sent one after another, each answered by a fix round that takes the stage back to building, until
 * the engine refuses one: how many were delivered, and the rule that refused the next.
 */
private fun deliveriesBeforeRefusal(rules: RuleSet): Pair<Int, String?> {
    val engine = supervised(rules)
    var state = building
    var delivered = 0
    while (true) {
        val id = "m-panda-%06d".format(200 + delivered)
        val decision = engine.judge(state, review(id), ready())
        if (decision.outcome == Outcome.REFUSE) return delivered to decision.rule
        delivered++
        val message = """{"id":"$id","thread":"$id","from":"coder-1","to":"teamlead","kind":"review-request","stage":"$STAGE","subject":"r","created":"$NOW","by":"seat:coder-1","args":{}}"""
        state = fold(after(state, message, deliveredRecord(decision, id, "teamlead"), *transitionRecords(decision, id).toTypedArray(),
            """{"event":"transition","machine":"stage","subject":"$STAGE","from":"ready_for_review","to":"building","cause":"$id"}"""), state)
    }
}

/** A Kotlin file's comments, its string and character literals, and its code — a template's code counts as code. */
private class Source(private val text: String) {
    val code = StringBuilder()
    val comments = StringBuilder()
    val literals = ArrayList<String>()

    init {
        var at = 0
        while (at < text.length) at = token(at)
    }

    /** Reads the token at [at] and returns where the next one begins. */
    private fun token(at: Int): Int = when {
        text.startsWith("//", at) -> (text.indexOf('\n', at).takeIf { it >= 0 } ?: text.length).also { comments.append(text, at, it).append('\n') }
        text.startsWith("/*", at) -> (text.indexOf("*/", at) + "*/".length).also { comments.append(text, at, it).append('\n') }
        text.startsWith("\"\"\"", at) -> string(at + "\"\"\"".length, "\"\"\"")
        text[at] == '"' -> string(at + 1, "\"")
        text[at] == '\'' -> {
            var end = at + 1
            while (text[end] != '\'') end += if (text[end] == '\\') 2 else 1
            literals += text.substring(at + 1, end)
            end + 1
        }
        else -> { code.append(text[at]); at + 1 }
    }

    private fun string(start: Int, quote: String): Int {
        val literal = StringBuilder()
        var at = start
        while (!text.startsWith(quote, at)) {
            when {
                quote == "\"" && text[at] == '\\' -> { literal.append(text, at, at + 2); at += 2 }
                text.startsWith("\${", at) -> {
                    at += "\${".length
                    var depth = 1
                    while (true) {
                        val char = text[at]
                        if (char == '}' && --depth == 0) { at++; break }
                        if (char == '{') depth++
                        at = token(at)
                    }
                }
                else -> literal.append(text[at++])
            }
        }
        literals += literal.toString()
        return at + quote.length
    }
}

/** T7: the decision carries what its records need (contracts.md §2.2), and the rules — not the engine — hold every number. */
class DecisionRecordsSpec : StringSpec({
    "a delivery's decision fills its delivered record and its transition record, and kp-model reads both" {
        val decision = supervised().judge(building, review(), ready())

        val delivered = read(deliveredRecord(decision, "m-panda-000100", "teamlead")) as Delivered
        val transition = read(transitionRecords(decision, "m-panda-000100").single()) as Transition

        listOf(delivered.rule, delivered.deliveryClass, delivered.counted, delivered.attested) shouldBe listOf("T-17", "A", mapOf("review_requests" to 1.toBigInteger()), listOf("done_lines_hold"))
        observed(delivered) shouldBe setOf("pr.ready", "pr.head", "ci.state(of=abc)", "list.tasks_open", "list.found_untriaged", "now")
        listOf(transition.machine, transition.subject, transition.from, transition.to, transition.rule, transition.counted) shouldBe
            listOf("stage", STAGE, "building", "ready_for_review", "T-17", mapOf("review_requests" to 1.toBigInteger()))
    }
    "a refusal's decision fills its refused record: the rule, the requirement in the rule file's prose, every fact read" {
        val decision = supervised().judge(building, review(), table("now" to NOW, "pr.ready" to true, "pr.head" to "abc", "ci.state(of=abc)" to "red"))

        val refused = read(refusedRecord(decision, "m-panda-000100")) as Refused

        refused.rule shouldBe "review-request"
        refused.requirement shouldBe "PR ready · CI green on head · every task done <sha> · DONE lines ticked · Found lines carried (fan-out found-batch)"
        observed(refused) shouldBe setOf("pr.ready", "pr.head", "ci.state(of=abc)")
    }
    "the effects requested come with their if already decided: found-batch when Found lines wait, nothing when none do" {
        supervised().judge(building, review(), ready(found = 2)).effects shouldBe listOf(Requested.Emit("T-17", "found-batch", "principal", emptyMap()))
        supervised().judge(building, review(), ready(found = 0)).effects shouldBe emptyList()
    }
    "with caps.review_requests_per_stage changed in a fixture rule set, the number of deliveries before the first refusal changes accordingly" {
        fun capped(at: Int) = ruleSet(Edit("rules/engagement.yaml", "  review_requests_per_stage: 3\n", "  review_requests_per_stage: $at\n"))

        deliveriesBeforeRefusal(master) shouldBe (3 to "review-request")
        deliveriesBeforeRefusal(capped(2)) shouldBe (2 to "T-17")
        deliveriesBeforeRefusal(capped(1)) shouldBe (1 to "T-17")
    }
    "the engine's source holds no number but zero, and no kind name" {
        val kinds = master.kinds.keys
        val sources = File(root, "kp-engine/src/main").walk().filter { it.name.endsWith(".kt") }.map { it.name to Source(it.readText()) }.toList()

        val numbers = sources.flatMap { (file, source) -> Regex("""(?<![\w.$])\d[\d_]*(\.\d+)?""").findAll(source.code).map { "$file: ${it.value}" }.filter { !it.endsWith(": 0") }.toList() }
        val kindNames = sources.flatMap { (file, source) ->
            val words = Regex("""[a-z][a-z-]*""").findAll(source.literals.joinToString(" ") + " " + source.comments + " " + source.code).map { it.value }.toSet()
            (words intersect kinds).map { "$file: $it" }
        }

        sources.size shouldBe 10
        numbers shouldBe emptyList()
        kindNames shouldBe emptyList()
    }
})
