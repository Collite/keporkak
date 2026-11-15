package kp.rules

import java.math.BigDecimal

/** A constant written in a rules file (YAML 1.2: only `true`/`false` are booleans). */
sealed interface Literal {
    data class Text(val value: String) : Literal
    data class Number(val value: BigDecimal) : Literal
    data class Bool(val value: Boolean) : Literal
    data object Null : Literal
}

/** A value (contracts.md §4.1): a literal of the file's own, or a reference resolved when it is used. */
sealed interface Value {
    data class Constant(val literal: Literal) : Value
    data class Items(val items: List<Value>) : Value
    /** A map of values where the form takes one: a fact's `where:`, `since: {wake: <mode>}`, an effect's `with:`/`args:`. */
    data class Fields(val fields: Map<String, Value>) : Value
    data class Cap(val name: String) : Value
    data class Arg(val path: String) : Value
    data class Fact(val fact: FactCall) : Value
    /** `{msg: <field>}` as a value — the message's own field (it stands in `with:` on master). */
    data class Message(val field: MessageField) : Value
    /** `{re: <field>}` — a field of the message this one answers: an emit to the asker is `to: {re: from}` (contracts.md §4.2). */
    data class Reply(val field: ReplyField) : Value
    data class Pool(val file: PoolFile, val path: String) : Value
    data class Engagement(val path: String) : Value
    data class Robot(val threshold: String) : Value
    data class Trigger(val path: String) : Value
}

enum class PoolFile(val key: String) { ROSTER("roster"), OFFICE("office"), BUDGET("budget") }

/** `{fact: <name>, <params…>}`: the fact's name and its named parameters. */
data class FactCall(val name: String, val params: Map<String, Value>)

enum class Operator(val key: String) { EQ("eq"), NE("ne"), IN("in"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte") }

data class Comparison(val operator: Operator, val value: Value)

enum class MessageField(val key: String) { BY("by"), FROM("from"), TO("to"), STAGE("stage") }

enum class ReplyField(val key: String) { KIND("kind"), CLASS("class"), FROM("from"), TO("to") }

enum class Outcome(val key: String) { OK("ok"), FAILED("failed") }

/** A requirement (contracts.md §4.1); a missing comparison means "is true" (or "present and not empty" for an arg). */
sealed interface Requirement {
    data class Fact(val fact: FactCall, val comparison: Comparison?) : Requirement
    data class Arg(val name: String, val comparison: Comparison?) : Requirement
    data class Message(val field: MessageField, val comparison: Comparison?) : Requirement
    data class Reply(val field: ReplyField, val comparison: Comparison?) : Requirement
    data class Counter(val name: String, val comparison: Comparison?) : Requirement
    data class Attest(val name: String) : Requirement
    data class Signed(val signer: String) : Requirement
    data class Operation(val operation: String, val outcome: Outcome) : Requirement
    data class Any(val items: List<Requirement>) : Requirement
    data class Not(val item: Requirement) : Requirement
    /** `{always: true}` — a robot that is always active (contracts.md §8). */
    data class Always(val holds: Boolean) : Requirement
    /** `{holds: <block>}` — every requirement of the named block of `engagement.yaml` holds (contracts.md §4.1, Amendment 7). */
    data class Holds(val block: String) : Requirement
}

enum class MessageClass { A, B, C }

/** An effect of a transition (`emit`, `do`, `count`) or of a kind's expiry (`emit`, `report`, `transition`, `do`, `wait`). */
sealed interface Effect {
    val condition: Requirement?
    data class Emit(val kind: String, val to: Value, val args: Map<String, Value>, override val condition: Requirement?) : Effect
    data class Do(val operation: String, val with: Map<String, Value>, override val condition: Requirement?) : Effect
    data class Count(val counter: String, override val condition: Requirement?) : Effect
    data class Report(val to: String, val messageClass: MessageClass, override val condition: Requirement?) : Effect
    data class Transition(val id: String, override val condition: Requirement?) : Effect
    /**
     * `{wait: again, if: …}` — the wait starts again for the kind's deadline, counted from the expiry (contracts.md
     * §4.2). Its `if` reads a counter against a cap; without a wait that holds, the wait is over.
     */
    data class Wait(override val condition: Requirement?) : Effect
}
