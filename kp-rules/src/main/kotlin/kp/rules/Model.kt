package kp.rules

import java.math.BigDecimal
import kotlin.time.Duration

/** The rule set: the four rules files, the robots, the pool files and the personas' modes, typed and checked. */
class RuleSet internal constructor(
    val lifecycle: Lifecycle,
    val kinds: Map<String, Kind>,
    val engagement: Engagement,
    val vocabulary: Vocabulary,
    /** By file name (`deputy.yaml`). */
    val robots: Map<String, Robot>,
    val pool: Pool,
    /** The modes a role's persona declares (`agents/<role>/AGENT.md`); a robot's `wake:` names one of its own. */
    val personaModes: Map<String, Set<String>>,
)

/** The parties that are not seats (contracts.md §1): a `to:` may name one, and none of them is a seat. */
val PARTIES: Set<String> = setOf("bora", "daemon", "mailroom", "ledger", "board")

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
    /** The prose of who answers, with its conditions ("if FIX FIRST", "optional"). */
    val reply: String?,
    /** The kinds that answer this one (contracts.md §4.2): a delivery of one that names a waiting message of this kind in `re` answers it. */
    val replies: List<String>,
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

/**
 * `engagement.yaml:stop_gate` (contracts.md §4.3, §11): [rule], the prose a block quotes; the cap on consecutive
 * blocks; the requirements under which a turn may not end yet — `{trigger: seat}` is the seat whose turn is ending.
 */
data class StopGate(val rule: String?, val maxConsecutiveBlocks: Value, val require: List<Requirement>)

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
    /** The autonomy profile in force (contracts.md §4.3): a name of [autonomyProfiles]. */
    val profile: String,
    val autonomyProfiles: Map<String, Map<String, Literal>>,
    val qa: EngagementQa?,
    val identity: Map<String, String>,
    val whatNoGuardHolds: List<String>,
    /**
     * `stop_gate`, named by the turn it guards: the engine reads it, and the engine's source holds no kind's name —
     * `stop` is one (contracts.md §4.3, S1.2's fence).
     */
    val turnGate: StopGate?,
    val permissions: Map<String, String>,
    /** Every scalar of the file by its dotted path — what `{engagement: <path>}` reads. */
    val values: Map<String, Literal>,
) {
    /**
     * The blocks of requirements a `{holds: …}` may name, by name — `second_key`. `stop_gate` is none (contracts.md §4.1,
     * Amendment 19): it is judged only when a turn is about to end, and a rule whose requirement equals its own repeats nothing.
     */
    val blocks: Map<String, List<Requirement>>
        get() = listOfNotNull(secondKey?.let { "second_key" to it.require }).toMap()
}

// rules/vocabulary.yaml (contracts.md §4.4)

enum class Source { O, L, P, C }

data class FactSpec(val name: String, val params: String, val returns: String, val sources: Set<Source>)

data class OperationSpec(val name: String, val requestedBy: String?, val identity: String?, val results: String?)

data class Vocabulary(val facts: Map<String, FactSpec>, val operations: Map<String, OperationSpec>, val attestations: Map<String, String>)

// robots/*.yaml (contracts.md §8)

sealed interface RobotTrigger {
    data class Fact(val name: String) : RobotTrigger
    data class Delivered(val kind: String) : RobotTrigger
    data class Arrives(val kind: String) : RobotTrigger
    data class Expired(val kind: String) : RobotTrigger
    data class Presence(val state: String) : RobotTrigger
    data class Operation(val operation: String, val outcome: Outcome) : RobotTrigger
}

sealed interface Action {
    data class Send(val kind: String, val to: Value, val args: Map<String, Value>) : Action
    /** Starts or wakes the robot's own seat in one of its persona's modes. */
    data class Wake(val mode: String) : Action
}

/** An executed rule has [on] and [then]; a rule `for: persona` has neither and is only rendered. */
data class RobotRule(
    val id: String,
    val whenText: String?,
    val note: String?,
    val forPersona: Boolean,
    val on: RobotTrigger?,
    val condition: List<Requirement>,
    val then: List<Action>,
)

sealed interface Intercept {
    data class Kind(val name: String) : Intercept
    data class ClassTo(val messageClass: MessageClass, val to: String) : Intercept
}

data class ProseRule(val id: String, val whenText: String?, val note: String?)

/** A delivery discipline (contracts.md §8): [id] is what a hold by the queue names, `by: robot:<id>`. */
data class Queue(val id: String, val order: List<String>, val nextWhen: Requirement, val prose: List<ProseRule>)

data class Robot(
    val file: String,
    val role: String,
    val activeWhen: Requirement,
    val intercepts: List<Intercept>,
    val rules: List<RobotRule>,
    val queue: Queue?,
    val thresholds: Map<String, Literal>,
    /** Prose the persona reads; never executed. */
    val limits: List<String>,
)

// pool/*.yaml (contracts.md §9)

data class Line(val vendor: String, val model: String, val effort: String)

/** A vendor's adapter and its pin; the SDK's pin lives in the version catalog only (contracts.md §9, Amendment 10). */
data class Vendor(val name: String, val adapter: String, val adapterVersion: String?)

data class RoleModel(val model: String, val effort: String)

data class Roster(
    val vendors: Map<String, Vendor>,
    /** Low → high; one line per (model, effort). */
    val order: List<Line>,
    val levels: Map<String, Map<String, Line>>,
    val reviewFloor: String,
    /** A model only the listed roles may run. */
    val reserved: Map<String, List<String>>,
    val roleModels: Map<String, RoleModel>,
)

data class Office(
    val name: String,
    val os: String,
    val user: String,
    val roots: Map<String, String>,
    val project: Project,
    val repos: Map<String, String>,
    val liveTargets: List<String>,
    val computerUse: String,
    val qaVendor: String,
    val listen: Map<String, String>,
    /** `watch.every`: how often the daemon reports its seats' facts (contracts.md §9, Amendment 22), as the file writes it. */
    val watchEvery: String,
)

/** The office's one project checkout, and each effort's folder relative to it; `mail/` is at its root (Amendment 4). */
data class Project(val checkout: String, val efforts: Map<String, String>)

data class Slots(val total: Int, val perSubscription: Map<String, Int>, val perOffice: Map<String, Int>, val stagesInFlightPerEffort: Int)

data class Budgets(val slots: Slots, val warn: List<BigDecimal>, val onCap: String)

data class Pool(
    val roster: Roster,
    val offices: Map<String, Office>,
    val budgets: Budgets,
    /** Every scalar of a pool file by its dotted path — what `{roster: …}`, `{budget: …}` and (per office) `{office: …}` read. */
    val values: Map<PoolFile, List<Map<String, Literal>>>,
)
