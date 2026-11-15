package kp.engine

import kp.model.toLine

/** The fields of a message a judgement reads (contracts.md §2.1): `{msg: …}`, `{arg: …}`, the stage, the thread. */
internal data class View(
    val id: String, val kind: String, val from: String, val to: String, val by: String, val stage: String?, val thread: String,
    val re: String?, val args: Datum.Fields, val body: String?,
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
    val signed: Boolean = false,
    val session: String? = null,
    val changed: String? = null,
) {
    val stage: String? get() = message?.stage
    val args: Datum.Fields get() = message?.args ?: Datum.Fields(emptyMap())

    companion object {
        fun of(trigger: Trigger, state: State): Context? = when (trigger) {
            is Trigger.Arrival -> {
                val line = (Datum.parse(trigger.message.toLine()) as Datum.Fields).fields
                val message = trigger.message
                val view = View(
                    message.id.value, message.kind, message.from.value, message.to.value, message.by, message.stage?.value, message.thread.value,
                    message.re?.value, line["args"] as? Datum.Fields ?: Datum.Fields(emptyMap()), message.body,
                )
                Context(kp.rules.Event.MESSAGE, message.kind, view, message.re?.value?.let(state.delivered::get), signed = trigger.signed, session = trigger.session)
            }
            is Trigger.Result -> state.delivered[trigger.request]?.let { request ->
                Context(kp.rules.Event.OP_RESULT, request.kind, request.view(), request.re?.let(state.delivered::get), trigger.operation, trigger.ok)
            }
            // An expiry's `{re: …}` — "the asker" — is the message whose deadline passed.
            is Trigger.Expiry -> state.delivered[trigger.message]?.let { waiting ->
                Context(kp.rules.Event.EXPIRY, waiting.kind, waiting.view(), waiting)
            }
            is Trigger.Changed -> Context(kp.rules.Event.FACT, null, null, null, changed = trigger.fact)
        }
    }
}

internal fun Delivery.view() = View(id, kind, from, to, by, stage, thread, re, args, null)
