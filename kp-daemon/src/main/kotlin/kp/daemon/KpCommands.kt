package kp.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.mailroom.Answer
import kp.mailroom.FactSource
import kp.mailroom.Sender
import kp.mailroom.Status

/**
 * `kp`'s commands (contracts.md §6), carried for one peer. Every command a root peer sends is a signed command — reads
 * too — and reaches the mailroom as Bora acting before it is carried (§6: "any command may be run with `sudo` and then
 * counts"); an unsigned one moves nothing. Nothing here decides: reads come from the folded state, and Bora's answers
 * are messages the mailroom judges like any other.
 */
class KpCommands(private val office: Office, world: FactSource) : Commands {
    private val views = Views(office, world)

    override fun run(request: Request, peer: Peer, out: Out): Int {
        if (peer.root) office.acted()
        val args = request.args
        return when (request.cmd) {
            "inbox" -> inbox(args, out)
            "status" -> if (args.size > 1) usage(out, "kp status [effort | stage | seat]") else print(out, views.status(args.firstOrNull()))
            "help" -> print(out, HELP)
            "check" -> if (args.isEmpty()) usage(out, "kp check <log…>") else Check(office.rules).run(args.map { java.nio.file.Path.of(it) }).let { (lines, code) -> code.also { lines.forEach(out::line) } }
            "approve" -> answer(out, peer, args, 1..1, "kp approve <ticket>") { item -> message(APPROVAL, item) }
            "decline" -> answer(out, peer, args, 1..Int.MAX_VALUE, "kp decline <ticket> [reason]") { item -> message(DECLINE, item, body = args.drop(1)) }
            "rule" -> answer(out, peer, args, 2..Int.MAX_VALUE, "kp rule <ticket> <outcome> [text]") { item ->
                message(RULING, item, mapOf(OUTCOME to JsonPrimitive(args[1])), body = args.drop(2))
            }
            "reverse" -> reverse(out, peer, args)
            "queue" -> if (args.isEmpty()) usage(out, "kp queue <stage>…")
                else submit(out, peer, input(QUEUE_APPROVAL, args = mapOf(STAGES to JsonArray(args.map(::JsonPrimitive)))))
            "away" -> if (args.isNotEmpty()) usage(out, "kp away") else submit(out, peer, input(AWAY))
            else -> usage(out, "no command '${request.cmd}' — kp help")
        }
    }

    /** `kp inbox [--all] [--follow]`: what waits for Bora; with `--follow`, then every item as it comes, until kp is interrupted. */
    private fun inbox(args: List<String>, out: Out): Int {
        val unknown = args.filterNot { it in setOf(ALL, FOLLOW) }
        if (unknown.isNotEmpty()) return usage(out, "kp inbox [--all] [--follow]")
        val seen = office.state.tickets.keys.toMutableSet()
        var version = office.changes.version()
        print(out, views.inbox(ALL in args))
        if (FOLLOW !in args) return OK
        while (!office.closed) {
            version = office.changes.await(version, FOLLOW_MILLIS)
            val fresh = office.state.openTickets().keys - seen
            if (fresh.isEmpty()) continue
            seen += fresh
            print(out, views.items(fresh))
        }
        return OK
    }

    /**
     * A command that answers an item by its ticket ([args]' first word, `5` or `#5`): the message [compose] makes for the
     * item, submitted in Bora's name — signed when the peer is root. An unknown ticket is not a message: nothing is sent.
     */
    private fun answer(out: Out, peer: Peer, args: List<String>, count: IntRange, form: String, compose: (Item) -> JsonObject): Int {
        if (args.size !in count) return usage(out, form)
        val number = args.first().removePrefix("#").toLongOrNull() ?: return usage(out, form)
        val ticket = office.state.tickets[number] ?: return NOT_FOUND.also { out.line("kp: no ticket #$number — kp inbox --all lists them") }
        return submit(out, peer, compose(Item(ticket.item, stageOf(ticket.item))))
    }

    /**
     * `kp reverse <digest item> minor|serious` (contracts.md §6): `reverse`, in reply to the item — a message id, or a
     * ticket whose item it is — so that it goes to whoever sent it, and naming it in `args.digest_item`.
     */
    private fun reverse(out: Out, peer: Peer, args: List<String>): Int {
        if (args.size != 2) return usage(out, "kp reverse <digest item> minor|serious")
        val named = args[0]
        val item = named.removePrefix("#").toLongOrNull()?.let { office.state.tickets[it]?.item } ?: named
        return submit(out, peer, message(REVERSE, Item(item, stageOf(item)), mapOf(DIGEST_ITEM to JsonPrimitive(named), SEVERITY to JsonPrimitive(args[1]))))
    }

    private class Item(val id: String, val stage: String?)

    private fun stageOf(id: String): String? = office.state.delivered[id]?.stage ?: office.state.pending[id]?.stage

    /** Bora's answer to [item]: its kind, in reply to the item, about the item's stage; [body] is the free text. */
    private fun message(kind: String, item: Item, args: Map<String, JsonElement> = emptyMap(), body: List<String> = emptyList()): JsonObject =
        input(kind, re = item.id, stage = item.stage, args = args, body = body.joinToString(" ").takeIf { it.isNotBlank() })

    /** What a sender supplies (contracts.md §2.1); `from`, `by` and `signed` are the mailroom's to set. */
    private fun input(kind: String, re: String? = null, stage: String? = null, args: Map<String, JsonElement> = emptyMap(), body: String? = null) = JsonObject(
        listOfNotNull(KIND to JsonPrimitive(kind), re?.let { RE to JsonPrimitive(it) }, stage?.let { STAGE to JsonPrimitive(it) },
            SUBJECT to JsonPrimitive(listOfNotNull(kind, re?.let { "re $it" }).joinToString(" ")),
            body?.let { BODY to JsonPrimitive(it) }, args.takeIf { it.isNotEmpty() }?.let { ARGS to JsonObject(it) }).toMap(),
    )

    /** Submits [input] in Bora's name (`by: bora-cli`), signed when the peer is root; the mailroom's answer, in one line. */
    private fun submit(out: Out, peer: Peer, input: JsonObject): Int {
        val answer = office.submit(Sender.Bora(signed = peer.root), input)
        out.line(said(answer))
        if (answer.status == Status.REFUSED && !peer.root) out.line("   (unsigned: Bora's answers count only under sudo — sudo kp …)")
        return if (answer.status == Status.REFUSED) REFUSED else OK
    }

    private fun said(answer: Answer): String = when (answer.status) {
        Status.DELIVERED -> "delivered ${answer.id}" + (answer.transition?.let { " — $it" } ?: "")
        Status.HELD -> "held ${answer.id}"
        Status.REFUSED -> "refused ${answer.id} — ${answer.rule}: ${answer.requirement}"
    }

    private fun print(out: Out, lines: List<String>): Int = OK.also { lines.forEach(out::line) }

    private fun usage(out: Out, text: String): Int = ControlSocket.USAGE.also { out.line("kp: $text") }

    private companion object {
        const val OK = 0
        const val REFUSED = 1
        const val NOT_FOUND = 1
        const val APPROVAL = "approval"
        const val DECLINE = "decline"
        const val RULING = "ruling"
        const val REVERSE = "reverse"
        const val QUEUE_APPROVAL = "queue-approval"
        const val AWAY = "away"
        const val OUTCOME = "outcome"
        const val STAGES = "stages"
        const val DIGEST_ITEM = "digest_item"
        const val SEVERITY = "severity"
        const val KIND = "kind"
        const val RE = "re"
        const val STAGE = "stage"
        const val SUBJECT = "subject"
        const val BODY = "body"
        const val ARGS = "args"
        const val ALL = "--all"
        const val FOLLOW = "--follow"
        const val FOLLOW_MILLIS = 30_000L
        val HELP = listOf(
            "kp inbox [--all] [--follow]        what waits for Bora, by ticket",
            "kp status [effort | stage | seat]  position, seats, budgets, held items",
            "kp check <log…>                    re-judges log files and prints every difference",
            "sudo kp approve <ticket>           approval on that item",
            "sudo kp decline <ticket> [reason]  decline",
            "sudo kp rule <ticket> <outcome> [text]",
            "sudo kp reverse <digest item> minor|serious",
            "sudo kp queue <stage>…             queue-approval for the current wave",
            "sudo kp away",
        )
    }
}
