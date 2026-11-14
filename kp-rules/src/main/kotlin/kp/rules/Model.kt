package kp.rules

import kotlin.time.Duration

/** The rule set: the four rules files, typed and checked. */
class RuleSet internal constructor(
    val lifecycle: Lifecycle,
    val kinds: Map<String, Kind>,
    val engagement: Engagement,
    val vocabulary: Vocabulary,
)

// rules/stage-lifecycle.yaml (contracts.md §4.1)

enum class Event(val key: String) { MESSAGE("message"), OP_RESULT("op-result"), EXPIRY("expiry"), FACT("fact") }

data class State(val name: String, val description: String?, val terminal: Boolean, val boraGate: Boolean, val overlay: Boolean)

data class Transition(
    val id: String,
    val from: String,
    val to: String,
    /** Absent only for `on: fact`, which names no sender. */
    val kind: String?,
    val on: Event,
    val guard: String?,
    val require: List<Requirement>,
    val effects: List<Effect>,
)

data class Machine(val name: String, val states: Map<String, State>, val transitions: List<Transition>)

enum class Scope(val key: String) { STAGE("stage"), SEAT("seat"), EFFORT("effort") }

data class Counter(val name: String, val scope: Scope)

/** The stage lifecycle and its companion machines (found_item, flag_thread, seat, phase, presence). */
data class Lifecycle(
    val stage: Machine,
    val companions: Map<String, Machine>,
    val activeStates: List<String>,
    val counters: Map<String, Counter>,
) {
    val machines: Map<String, Machine> get() = mapOf(stage.name to stage) + companions
}

// rules/message-kinds.yaml (contracts.md §4.2)

enum class Window(val key: String) { STAGE("stage"), DAY("day"), RETURN("return"), SESSION("session") }

sealed interface Destination {
    /** One or more roles or parties (`teamlead | pool`). */
    data class Parties(val names: List<String>) : Destination
    /** `to: {re: from}` — the asker. */
    data class Reply(val field: ReplyField) : Destination
    /** `to: a seat` — any seat, named by the message's args. */
    data object AnySeat : Destination
}

enum class ArgType(val key: String) { STRING("string"), ARRAY("array"), OBJECT("object"), BOOLEAN("boolean"), INTEGER("integer"), NUMBER("number") }

data class ArgSpec(val type: ArgType?, val enum: List<Literal>?, val required: Boolean, val items: ArgSpec?, val requiredFields: List<String>)

data class ClassCase(val condition: Requirement, val messageClass: MessageClass)

data class ClassRule(val default: MessageClass, val cases: List<ClassCase>)

data class Kind(
    val name: String,
    val from: List<String>,
    val to: Destination,
    val cc: List<String>,
    val fires: String?,
    val guard: String?,
    val reply: String?,
    val note: String?,
    /** The prose companion of [expiry]. */
    val onExpiry: String?,
    val args: Map<String, ArgSpec>,
    val classRule: ClassRule,
    /** `null`: the engagement's default applies. */
    val maxFires: Int?,
    val window: Window,
    val deadline: Duration?,
    val expiry: List<Effect>,
    val require: List<Requirement>,
)

// rules/engagement.yaml (contracts.md §4.3)

data class Role(val host: String?, val may: String?, val never: String?)

data class Graduation(val rule: String?, val n: Int?, val never: List<String>)

data class Presence(val idleHoursToAbsent: Duration?, val signals: List<String>, val whileAbsent: String?, val onReturn: String?)

data class SecondKey(val rule: String?, val require: List<Requirement>, val otherwise: MessageClass)

data class StopGate(val maxConsecutiveBlocks: Value, val require: List<Requirement>)

data class EngagementQa(
    val instancesPerOffice: Int,
    val queue: String?,
    val computerUse: Map<String, String>,
    val candidates: String?,
    val durableRoll: String?,
    val rollback: String?,
    val exclusivity: String?,
)

data class Engagement(
    val principles: List<String>,
    val roles: Map<String, Role>,
    val classes: Map<MessageClass, String>,
    val graduation: Graduation,
    /** A cap is a number, a duration, or — for the expiry default — prose. */
    val caps: Map<String, Literal>,
    val presence: Presence?,
    val mailroomDeclaredEffects: List<String>,
    val secondKey: SecondKey?,
    val autonomyProfiles: Map<String, Map<String, Literal>>,
    val qa: EngagementQa?,
    val identity: Map<String, String>,
    val whatNoGuardHolds: List<String>,
    val stopGate: StopGate?,
    val permissions: Map<String, String>,
    /** Every scalar of the file by its dotted path — what `{engagement: <path>}` reads. */
    val values: Map<String, Literal>,
)

// rules/vocabulary.yaml (contracts.md §4.4)

enum class Source { O, L, P, C }

data class FactSpec(val name: String, val params: String, val returns: String, val sources: Set<Source>)

data class OperationSpec(val name: String, val requestedBy: String?, val identity: String?, val results: String?)

data class Vocabulary(val facts: Map<String, FactSpec>, val operations: Map<String, OperationSpec>, val attestations: Map<String, String>)
