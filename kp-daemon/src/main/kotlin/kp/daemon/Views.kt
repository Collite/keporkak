package kp.daemon

import kp.engine.Datum
import kp.engine.FactRef
import kp.engine.State
import kp.engine.Ticket
import kp.log.LogLine
import kp.mailroom.FactSource
import kp.model.Delivered
import kp.model.Envelope
import kp.model.Expired
import kp.model.Held
import kp.model.Message

/**
 * What `kp inbox` and `kp status` print (contracts.md §6), read from the folded state — and, for what the state does
 * not keep (an item's class when an expiry reported it, its message's `args.default`), from the line the ticket's
 * number is the `seq` of. A ticket is that `seq`: issued once, never renumbered (contracts.md §1, Amendment 15).
 */
internal class Views(private val office: Office, private val world: FactSource) {
    /** Bora's inbox: the open tickets — with [all], the answered ones too — each with its class, kind, stage, the list's three lines and the default. */
    fun inbox(all: Boolean): List<String> {
        val state = office.state
        val tickets = state.tickets.filterValues { all || it.answeredBy == null }.toSortedMap()
        if (tickets.isEmpty()) return listOf(if (all) "no ticket has been issued" else "nothing waits for Bora")
        val lines = Index(office.lines())
        return tickets.flatMap { (number, ticket) -> item(number, ticket, lines) }
    }

    /** The items of [numbers] — those `--follow` has not shown yet. */
    fun items(numbers: Collection<Long>): List<String> {
        val tickets = office.state.tickets
        val lines = Index(office.lines())
        return numbers.sorted().flatMap { number -> tickets[number]?.let { item(number, it, lines) }.orEmpty() }
    }

    private fun item(number: Long, ticket: Ticket, lines: Index): List<String> {
        val message = lines.message(ticket.item)
        val raised = lines.seq[number]
        val itemClass = when (raised) {
            is Delivered -> raised.deliveryClass
            is Expired -> raised.deliveryClass ?: "-"
            is Held -> "held by ${raised.by}"
            else -> "-"
        }
        val stage = message?.stage?.value
        val head = listOfNotNull("#$number", itemClass, message?.kind ?: "?", stage ?: "no stage", message?.from?.value?.let { "from $it" })
            .joinToString(" · ") + (if (raised is Expired) " — its deadline passed" else "") + (ticket.answeredBy?.let { " — answered by $it" } ?: "")
        val three = stage?.let { at -> "   " + THREE.joinToString(" · ") { line -> "$line: ${header(line, at)}" } }
        val default = (message?.args?.get(DEFAULT) as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { "   default: $it" }
        return listOfNotNull(head, three, default)
    }

    /** A line of the stage list's header (PL-9: `rolls:`, `live:`, `touches:`), as the fact `stage.<line>` gives it. */
    private fun header(line: String, stage: String): String =
        when (val value = world.observe(FactRef("stage.$line", mapOf(STAGE to Datum.Text(stage))), stage)) {
            null -> "unknown"
            is Datum.Text -> value.value
            else -> value.json()
        }

    /** Position, seats, budgets, held items (contracts.md §6) — all of it, or what concerns one effort, stage or seat. */
    fun status(filter: String?): List<String> {
        val state = office.state
        val rules = office.rules
        val stages = state.machines[STAGE].orEmpty().filterKeys { filter == null || it == filter || it.startsWith("$filter/") }
        val seats = state.machines[SEAT].orEmpty().filterKeys { filter == null || it == filter }
        if (filter != null && stages.isEmpty() && seats.isEmpty()) return listOf("nothing on the log is '$filter': an effort, a stage or a seat")
        val presence = state.presence(BORA) ?: rules.ruleSet.lifecycle.machines[PRESENCE]?.states?.keys?.firstOrNull() ?: "unknown"
        val inScope = { stage: String? -> filter == null || stage in stages }
        val slots = rules.ruleSet.pool.budgets.slots
        return buildList {
            add("office ${office.name} · rules ${rules.hash.take(HASH)} · log at seq ${state.seq}")
            add("Bora: $presence")
            stages.toSortedMap().forEach { (stage, machine) -> add("stage $stage: ${machine.state}" + counters(state, stage)) }
            seats.toSortedMap().forEach { (seat, machine) -> add("seat $seat: ${machine.state}" + counters(state, seat)) }
            add("held: " + state.held.filterKeys { inScope(state.pending[it]?.stage) }.entries.joinToString("; ") { (id, hold) -> "$id by ${hold.by} until ${hold.until}" }.ifEmpty { "none" })
            add("waiting: " + state.deadlines.filterValues { inScope(it.stage) }.entries.joinToString("; ") { (id, wait) -> "$id ${wait.kind} → ${wait.to}, deadline ${wait.deadline ?: "suspended"}" }.ifEmpty { "none" })
            add("operations in flight: " + state.operations.filter { inScope(state.delivered[it.cause]?.stage) }.joinToString("; ") { "${it.op} for ${it.cause} since ${it.since}" }.ifEmpty { "none" })
            add("open tickets: " + state.openTickets().keys.sorted().joinToString(", ") { "#$it" }.ifEmpty { "none" })
            add("budgets: ${slots.total} slots · per subscription ${slots.perSubscription.entries.joinToString(", ") { "${it.key} ${it.value}" }}" +
                " · per office ${slots.perOffice.entries.joinToString(", ") { "${it.key} ${it.value}" }} · ${slots.stagesInFlightPerEffort} stages in flight per effort")
            office.refusedReload?.let { refused ->
                add("reload refused at ${refused.at} — the rules ${rules.hash.take(HASH)} stay in force:")
                refused.problems.forEach { add("   $it") }
            }
        }
    }

    private fun counters(state: State, subject: String): String =
        state.counters[subject].orEmpty().entries.joinToString("") { " · ${it.key} ${it.value}" }

    /** The lines of the log by `seq`, and its messages by id. */
    private class Index(lines: List<LogLine>) {
        val seq: Map<Long, Envelope> = lines.associate { it.entry.seq.toLong() to it.entry }
        private val messages: Map<String, Message> = lines.mapNotNull { it.entry as? Message }.associateBy { it.id.value }
        fun message(id: String): Message? = messages[id]
    }

    private companion object {
        val THREE = listOf("rolls", "live", "touches")
        const val DEFAULT = "default"
        const val STAGE = "stage"
        const val SEAT = "seat"
        const val PRESENCE = "presence"
        const val BORA = "bora"
        const val HASH = 12
    }
}
