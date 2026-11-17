package kp.daemon

import kp.mailroom.FactSource

/**
 * `kp`'s commands (contracts.md §6), carried for one peer. Every command a root peer sends is a signed command — reads
 * too — and is noted as Bora acting (§6: "any command may be run with `sudo` and then counts"). Nothing here decides:
 * reads come from the folded state, and Bora's answers are messages the mailroom judges like any other.
 */
class KpCommands(private val office: Office, world: FactSource) : Commands {
    private val views = Views(office, world)

    override fun run(request: Request, peer: Peer, out: Out): Int {
        if (peer.root) office.presence.signed()
        val args = request.args
        return when (request.cmd) {
            "inbox" -> inbox(args, out)
            "status" -> if (args.size > 1) usage(out, "kp status [effort | stage | seat]") else print(out, views.status(args.firstOrNull()))
            "help" -> print(out, HELP)
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

    private fun print(out: Out, lines: List<String>): Int = OK.also { lines.forEach(out::line) }

    private fun usage(out: Out, text: String): Int = ControlSocket.USAGE.also { out.line("kp: $text") }

    private companion object {
        const val OK = 0
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
