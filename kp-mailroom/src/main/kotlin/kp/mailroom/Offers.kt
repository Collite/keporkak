package kp.mailroom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What a submission through the mailroom's one way in, [Mailroom.submit], came to (contracts.md §3, §5). */
sealed interface Offer {
    /** A message: written, judged, and answered as [answer] says. */
    data class Answered(val answer: Answer) : Offer

    /** Input that is no message at all: [field] is missing or not of its form, as [problem] says. Nothing was written. */
    data class Malformed(val field: String, val problem: String) : Offer {
        /** What a sender is told: `<field>: <problem>`. */
        override fun toString(): String = "$field: $problem"
    }
}

/**
 * The fields a sender supplies (contracts.md §2.1), each against its form in the message's schema: a kind, and — when
 * given and not null — `to`, a stage, a subject, a body, pointers, `args` and `re`. The first that is not, with what is
 * wrong; null when the input can be a message. Of `to` only its type is checked here (§5): text that names no party is
 * an addressee that cannot be resolved, written `to: mailroom` and refused (contracts.md §3). The problem names no
 * value the input carried.
 */
internal fun malformed(input: JsonObject): Offer.Malformed? {
    fun given(name: String): JsonElement? = input[name]?.takeIf { it !is JsonNull }
    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    val kind = given(KIND) ?: return Offer.Malformed(KIND, "missing — a message names its kind")
    if (string(kind)?.let(KIND_FORM::matches) != true) return Offer.Malformed(KIND, "not a kind's name (${KIND_FORM.pattern})")
    given(TO)?.let { if (string(it) == null) return Offer.Malformed(TO, "not text — a party's name") }
    given(STAGE)?.let { if (string(it)?.let(STAGE_FORM::matches) != true) return Offer.Malformed(STAGE, "not a stage (${STAGE_FORM.pattern})") }
    given(SUBJECT)?.let { subject ->
        val length = string(subject)?.let { it.codePointCount(0, it.length) }
        if (length == null || length !in 1..SUBJECT_LIMIT) return Offer.Malformed(SUBJECT, "not text of 1 to $SUBJECT_LIMIT characters")
    }
    given(BODY)?.let { if (string(it) == null) return Offer.Malformed(BODY, "not text") }
    given(POINTERS)?.let { pointers ->
        val each = (pointers as? JsonArray)?.map { pointer -> string(pointer)?.let { it.codePointCount(0, it.length) >= POINTER_MIN } == true }
        if (each == null || false in each) return Offer.Malformed(POINTERS, "not a list of pointers, each text of at least $POINTER_MIN characters")
    }
    given(ARGS)?.let { if (it !is JsonObject) return Offer.Malformed(ARGS, "not an object") }
    given(RE)?.let { if (string(it)?.let(MESSAGE_ID::matches) != true) return Offer.Malformed(RE, "not a message id (${MESSAGE_ID.pattern})") }
    return null
}

/**
 * The result a sender is given (contracts.md §5): `{status, id, transition?, rule?, requirement?, failed?,
 * refusals_today?}` — a refusal is a result, not an error.
 */
fun Answer.result(): JsonObject = buildJsonObject {
    put(STATUS, JsonPrimitive(status.name.lowercase()))
    put(ID, JsonPrimitive(id))
    transition?.let { put(TRANSITION, JsonPrimitive(it)) }
    rule?.let { put(RULE, JsonPrimitive(it)) }
    requirement?.let { put(REQUIREMENT, JsonPrimitive(it)) }
    failed?.let { put(FAILED, it.element()) }
    refusalsToday?.let { put(REFUSALS_TODAY, JsonPrimitive(it)) }
}

/** The message's forms (contracts.md §2.1). */
private val KIND_FORM = Regex("^[a-z][a-z-]*$")
private val STAGE_FORM = Regex("^[A-Z][A-Z0-9-]*/P[0-9]+/S[0-9]+\\.[0-9]+$")
private val MESSAGE_ID = Regex("^m-[a-z0-9]+-[0-9]{6,}$")
private const val SUBJECT_LIMIT = 120
private const val POINTER_MIN = 3

private const val KIND = "kind"
private const val TO = "to"
private const val STAGE = "stage"
private const val SUBJECT = "subject"
private const val BODY = "body"
private const val POINTERS = "pointers"
private const val ARGS = "args"
private const val RE = "re"
private const val STATUS = "status"
private const val ID = "id"
private const val TRANSITION = "transition"
private const val RULE = "rule"
private const val REQUIREMENT = "requirement"
private const val FAILED = "failed"
private const val REFUSALS_TODAY = "refusals_today"
