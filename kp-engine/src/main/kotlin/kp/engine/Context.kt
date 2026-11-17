package kp.engine


/** The fields of a message a judgement reads (contracts.md §2.1): `{msg: …}`, `{arg: …}`, the stage, the thread, `signed`. */
internal data class View(
    val id: String, val kind: String, val from: String, val to: String, val by: String, val stage: String?, val thread: String,
    val re: String?, val args: Datum.Fields, val body: String?, val signed: Boolean,
)

/**
 * What one judgement is about: the kind whose rules apply, the message (the one that arrived, the request whose
 * operation ended, the one whose deadline passed), what the trigger adds, and the message a `{re: …}` names.
 */
internal data class Context(
    val event: kp.rules.Event,
    val kind: String?,
    val message: View?,
    val replyTo: Delivery?,
    val operation: String? = null,
    val ok: Boolean? = null,
    val changed: String? = null,
    /** What a robot's `{trigger: <field>}` reads (contracts.md §4.1): the fields of what triggered it. */
    val trigger: Datum.Fields = Datum.Fields(emptyMap()),
    /** The message `{re: …}` names is held, not delivered: its class is the one it would go under now. */
    val replyHeld: Boolean = false,
    /** The stage a fact that changed concerns, when no message says it. */
    val about: String? = null,
) {
    val stage: String? get() = message?.stage ?: about
    val args: Datum.Fields get() = message?.args ?: Datum.Fields(emptyMap())

    companion object {
        /** The context the engine's guard judges in; null for what only a robot hears. */
        fun of(trigger: Trigger, state: State): Context? = when (trigger) {
            is Trigger.Arrival -> arrival(trigger, state)
            is Trigger.Result -> state.delivered[trigger.request]?.let { request ->
                Context(kp.rules.Event.OP_RESULT, request.kind, request.view(), request.re?.let(state.delivered::get), trigger.operation, trigger.ok)
            }
            // An expiry's `{re: …}` — "the asker" — is the message whose deadline passed.
            is Trigger.Expiry -> state.delivered[trigger.message]?.let { waiting ->
                Context(kp.rules.Event.EXPIRY, waiting.kind, waiting.view(), waiting)
            }
            is Trigger.Changed -> Context(kp.rules.Event.FACT, null, null, null, changed = trigger.fact, about = trigger.stage)
            is Trigger.Delivered, is Trigger.Presence, is Trigger.Release -> null
        }

        /**
         * The context a robot's rules are read in (contracts.md §8): the message that arrived, was delivered or expired;
         * the request whose operation ended; the fact that changed; the presence that moved; or, when what is held may be let go, the
         * last request a queue handed over. Its `trigger` holds what `{trigger: …}` reads: the message's fields and its
         * `args` — and the `seat` it names — the operation's seat, the fact's parameters and stage.
         */
        fun ofRobots(trigger: Trigger, state: State): Context? = when (trigger) {
            is Trigger.Arrival -> arrival(trigger, state)?.let { it.copy(trigger = fields(it.message!!)) }
            is Trigger.Delivered -> state.delivered[trigger.message]?.let { delivered ->
                Context(kp.rules.Event.MESSAGE, delivered.kind, delivered.view(), delivered.re?.let(state.delivered::get), trigger = fields(delivered.view()))
            }
            is Trigger.Expiry -> of(trigger, state)?.let { it.copy(trigger = fields(it.message!!)) }
            is Trigger.Result -> state.delivered[trigger.request]?.let { request ->
                val seat = listOf(trigger.result, trigger.with, request.args).firstNotNullOfOrNull { it.fields[SEAT] }
                Context(kp.rules.Event.OP_RESULT, request.kind, request.view(), request.re?.let(state.delivered::get), trigger.operation, trigger.ok,
                    trigger = Datum.Fields(fields(request.view()).fields + listOfNotNull(seat?.let { SEAT to it })))
            }
            is Trigger.Changed -> Context(kp.rules.Event.FACT, null, null, null, changed = trigger.fact, about = trigger.stage,
                trigger = Datum.Fields(trigger.params + listOfNotNull(trigger.stage?.let { STAGE to Datum.Text(it) })))
            is Trigger.Presence -> Context(kp.rules.Event.FACT, null, null, null, trigger = Datum.Fields(mapOf(STATE to Datum.Text(trigger.state))))
            is Trigger.Release -> Context(kp.rules.Event.FACT, null, trigger.job?.let(state.delivered::get)?.view(), null)
        }

        /** An arrival: its `{re: …}` is a delivered message, or one a robot holds — read as it would go now. */
        private fun arrival(trigger: Trigger.Arrival, state: State): Context? {
            val message = trigger.message
            val view = View(
                message.id.value, message.kind, message.from.value, message.to.value, message.by, message.stage?.value, message.thread.value,
                message.re?.value, Datum.of(message.plainArgs) as Datum.Fields, message.body, message.signed,
            )
            val re = message.re?.value
            val delivered = re?.let(state.delivered::get)
            val held = if (delivered == null) re?.let { state.heldView(it) } else null
            return Context(kp.rules.Event.MESSAGE, message.kind, view, delivered ?: held, replyHeld = held != null)
        }

        /** What `{trigger: …}` reads of a message: its fields, its `args`, and the seat its `args` name. */
        private fun fields(view: View): Datum.Fields = Datum.Fields(listOfNotNull(
            "id" to Datum.Text(view.id), "kind" to Datum.Text(view.kind), "from" to Datum.Text(view.from), "to" to Datum.Text(view.to),
            "by" to Datum.Text(view.by), "thread" to Datum.Text(view.thread), view.stage?.let { STAGE to Datum.Text(it) },
            view.re?.let { "re" to Datum.Text(it) }, "args" to view.args, view.args.fields[SEAT]?.let { SEAT to it },
        ).toMap())
    }
}

internal fun Delivery.view() = View(id, kind, from, to, by, stage, thread, re, args, null, signed)

/** A message a robot holds, as a `{re: …}` reads it: written, held, not delivered — so no class yet. */
internal fun State.heldView(id: String): Delivery? {
    val keeping = held[id] ?: return null
    val message = pending[id] ?: return null
    return Delivery(id, message.kind, message.from, message.to, message.stage, message.thread, message.re, message.by, message.args, "", keeping.since, message.signed)
}

private const val SEAT = "seat"
private const val STAGE = "stage"
private const val STATE = "state"
