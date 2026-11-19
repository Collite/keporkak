package kp.driver

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kp.model.SeatId
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * The seats' driver (architecture.md §2, §5): it starts, prompts, loads, cancels and kills seat sessions, writes each
 * seat's transcript, and reports what it observes as facts. It is vendor-neutral: no protocol type and no vendor's
 * name crosses this interface. It reports and never concludes — durations, fractions and stop reasons as they are.
 * Every call returns [Outcome.Ok] or [Outcome.Failed] (contracts.md §4.4); the driver retries nothing.
 */
interface Driver : AutoCloseable {
    /** Starts a fresh agent process and a session in it (T2, T3). */
    suspend fun start(spec: SessionSpec): Outcome<Started>

    /**
     * One turn: returns when the agent ends it, with the agent's stop reason (T4). A second prompt while a turn is in
     * flight fails. [sent] is called once the prompt is on its way to the agent — not when it could not be sent (S2.4).
     */
    suspend fun prompt(seat: SeatId, text: String, sent: () -> Unit = {}): Outcome<TurnEnd>

    /** Asks the agent to end the turn in flight, which then ends `cancelled` (T4); with none in flight, sends nothing (§4.4). */
    suspend fun cancel(seat: SeatId): Outcome<Cancel>

    /** Closes the agent's input, waits [grace], then ends the process tree by force (T7). */
    suspend fun kill(seat: SeatId, grace: Duration): Outcome<Unit>

    /** Loads [sessionId] into a fresh process, with a fresh token, and sets mode, model and effort again (T7). */
    suspend fun load(spec: SessionSpec, sessionId: String): Outcome<Started>

    /** What the agent said it offers for the seat's session (T3). */
    fun offers(seat: SeatId): Outcome<Offers>

    /** One of [SeatFacts], computed now from the injected clock (T6). `seat.usage` needs a [window]. */
    fun observe(seat: SeatId, fact: String, window: UsageWindow? = null): Outcome<ObservedFact>

    /** Ends every process this driver started. */
    override fun close()
}

/** The result of every call: ok with a value, or failed with a reason. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Failed(val reason: String) : Outcome<Nothing>
}

/**
 * What the caller gives for one session (contracts.md §10): the agent's command, its working directory, the seat's
 * config home, model (a full id, §9) and effort, the MCP servers, and the environment — built by the caller, never
 * inherited. With a [hookUrl], the seat's process is also given `KP_HOOK_URL` and `KP_SESSION_TOKEN`, the token the
 * driver minted for it (§11).
 */
data class SessionSpec(
    val seat: SeatId,
    val command: List<String>,
    val workingDirectory: Path,
    val configHome: Path,
    val model: String,
    val effort: String,
    val servers: List<McpServer>,
    val environment: Map<String, String> = emptyMap(),
    val hookUrl: String? = null,
) {
    companion object {
        /** The variables a seat's hooks read (contracts.md §11); neither value reaches a transcript. */
        const val HOOK_URL = "KP_HOOK_URL"
        const val SESSION_TOKEN = "KP_SESSION_TOKEN"
    }
}

/** An MCP server injected at `session/new`; one that [carriesToken] gets `Authorization: Bearer <session token>` (§5). */
data class McpServer(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val carriesToken: Boolean = false,
)

/** The session token (§5): random, one per started process; it never shows in a string. */
class SessionToken(val value: String) {
    override fun equals(other: Any?) = other is SessionToken && other.value == value
    override fun hashCode() = value.hashCode()
    override fun toString() = "SessionToken(sha256:${sha256(value).take(12)})"
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * A session the driver started or loaded, with the mode, model and effort as the agent reports them back — the model
 * as the value the agent offers for the full id it was given (§9), which may be a shorter name.
 */
data class Started(
    val seat: SeatId,
    val sessionId: String,
    val token: SessionToken,
    val mode: String,
    val model: String,
    val effort: String,
)

/** What a cancel did (contracts.md §4.4): asked the agent to end the turn in flight, or nothing — none was in flight. */
enum class Cancel { SENT, NOTHING_IN_FLIGHT }

/** A turn's end: the agent's stop reason, passed on as the agent gave it, and the usage it reported, if any. */
data class TurnEnd(val stopReason: String, val usage: Usage?)

/** Tokens of one turn as the agent reports them. */
data class Usage(
    val input: Long,
    val output: Long,
    val total: Long,
    val thought: Long? = null,
    val cachedRead: Long? = null,
    val cachedWrite: Long? = null,
)

/** What the agent offers for a session: its modes, models and efforts, by the values it accepts. */
data class Offers(val modes: List<String>, val models: List<String>, val efforts: List<String>)

/** One option of a permission request, as offered. [kind] is the protocol's word, e.g. `allow_once`. */
data class OfferedOption(val id: String, val name: String, val kind: String)

/**
 * A permission request, whole: the tool's title, its input, and the options the agent offers (T5) — and the tool's
 * [name] as the agent calls it (`Bash`, `Write`), when the agent says it (S2.2).
 */
data class PermissionRequest(
    val seat: SeatId,
    val toolCallId: String,
    val tool: String,
    val kind: String?,
    val input: JsonElement?,
    val options: List<OfferedOption>,
    val name: String? = null,
)

enum class Answer { ALLOW, DENY }

/** An answer and why, in the transcript's words: a denial by a compiled rule names its border (S2.2 T4). */
data class Explained(val answer: Answer, val because: String? = null)

/** Says allow or deny for one permission request; the caller supplies it (S2.2 brings the real one). */
fun interface Answerer {
    suspend fun answer(request: PermissionRequest): Answer

    /** The answer with its reason; an answerer that gives none answers as [answer]. */
    suspend fun explained(request: PermissionRequest): Explained = Explained(answer(request))
}

/** The windows of `seat.usage` (contracts.md §4.4): the last turn that ended, and the session. */
enum class UsageWindow(val word: String) { TURN("turn"), SESSION("session") }

/** The facts the driver observes, named as `rules/vocabulary.yaml` names them (source O). */
object SeatFacts {
    const val PROCESS_GONE = "seat.process_gone"
    const val PROTOCOL_CLOSED = "seat.protocol_closed"
    const val SILENT_FOR = "seat.silent_for"
    const val TOOL_CALL_OPEN_FOR = "seat.tool_call_open_for"
    const val CONTEXT_USED = "seat.context_used"
    const val USAGE = "seat.usage"
    val names: Set<String> = setOf(PROCESS_GONE, PROTOCOL_CLOSED, SILENT_FOR, TOOL_CALL_OPEN_FOR, CONTEXT_USED, USAGE)
}

/**
 * One observation, shaped as a `fact` record's content (contracts.md §2.2): fact, subject (the seat), value, evidence.
 * Only names in [SeatFacts] can be made.
 */
class ObservedFact internal constructor(val fact: String, val subject: SeatId, val value: JsonElement, val evidence: String) {
    init { require(fact in SeatFacts.names) { "the driver observes no fact named $fact" } }
    fun content(): JsonObject = JsonObject(
        mapOf("fact" to JsonPrimitive(fact), "subject" to JsonPrimitive(subject.value), "value" to value, "evidence" to JsonPrimitive(evidence))
    )
    override fun toString() = "ObservedFact(${content()})"
}

/**
 * Where the driver reports what happens without being asked: a token when it is minted — before the process it is
 * for starts (contracts.md §5, Amendment 22) — a fact when it happens, and a token that died. Every minted token is
 * reported dead once, when its session ends or could not be opened.
 */
interface Listener {
    fun tokenMinted(seat: SeatId, token: SessionToken) {}
    fun observed(fact: ObservedFact) {}
    fun tokenDead(seat: SeatId, token: SessionToken) {}
}
