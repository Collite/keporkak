package kp.engine

import kp.model.Message

/** What the engine judges (contracts.md §4.1, triggers): a message that arrives, an operation's result, an expiry, a fact that changed. */
sealed interface Trigger {
    /**
     * `on: message`. Whether it was signed is the message's own `signed` (contracts.md §2.1); when the sender's session
     * began is in the log (its last `seat.start` that is `ok`).
     */
    data class Arrival(val message: Message) : Trigger
    /** `on: op-result`: [operation], which the delivered message [request] requested, ended `ok` or not. */
    data class Result(val request: String, val operation: String, val ok: Boolean) : Trigger
    /** `on: expiry`: the deadline of the delivered message [message] passed. */
    data class Expiry(val message: String) : Trigger
    /** `on: fact`: [fact] changed; such a transition names no sender. */
    data class Changed(val fact: String) : Trigger
}

/** A fact as the engine reads it: its name and its parameters, resolved. Written `name` or `name(key=value, …)`. */
data class FactRef(val name: String, val params: Map<String, Datum> = emptyMap()) {
    override fun toString(): String =
        if (params.isEmpty()) name
        else params.toSortedMap().entries.joinToString(", ", "$name(", ")") { (key, value) -> "$key=" + ((value as? Datum.Text)?.value ?: value.json()) }
}

/**
 * A fact the engine names before a judgement (contracts.md §4.4). A parameter is [Param.Known], or the value of
 * another fact, which the mailroom collects first ([Facts.collect]).
 */
data class Need(val name: String, val params: Map<String, Param>) {
    sealed interface Param {
        data class Known(val value: Datum) : Param
        data class Of(val need: Need) : Param
    }
}

/** The table of facts handed over with a trigger: a value per fact; a fact not in it is unknown. */
class Facts(private val values: Map<FactRef, Datum>) {
    operator fun get(ref: FactRef): Datum? = values[ref]
    val refs: Set<FactRef> get() = values.keys

    companion object {
        val NONE = Facts(emptyMap())

        /** Collects [needs] through [observe], a parameter's fact before the fact; what [observe] cannot answer stays unknown. */
        fun collect(needs: List<Need>, observe: (FactRef) -> Datum?): Facts {
            val values = LinkedHashMap<FactRef, Datum>()
            fun resolve(need: Need): FactRef? {
                val params = need.params.mapValues { (_, param) ->
                    when (param) {
                        is Need.Param.Known -> param.value
                        is Need.Param.Of -> resolve(param.need)?.let(values::get) ?: return null
                    }
                }
                val ref = FactRef(need.name, params)
                if (ref !in values) observe(ref)?.let { values[ref] = it }
                return ref
            }
            needs.forEach { resolve(it) }
            return Facts(values)
        }
    }
}
