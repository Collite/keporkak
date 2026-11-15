package kp.driver.acp

import kp.driver.Answer
import kp.driver.OfferedOption

/**
 * How an answer becomes the option the driver selects (D-18 (4)): `allow_once` for allow, `reject_once` for deny —
 * only ever a one-time option. When the one-time option an answer needs is not offered, the request is denied:
 * with `reject_once` if that is offered, otherwise by answering `cancelled`. An option that persists is never chosen.
 */
internal object Permissions {
    const val ALLOW_ONCE = "allow_once"
    const val REJECT_ONCE = "reject_once"

    /** The choice: the option to select (null: answer `cancelled`), and the transcript's words for it. */
    class Choice(val option: OfferedOption?, val words: String)

    fun choose(answer: Answer, offered: List<OfferedOption>): Choice {
        val needed = if (answer == Answer.ALLOW) ALLOW_ONCE else REJECT_ONCE
        offered.firstOrNull { it.kind == needed }?.let { return Choice(it, needed) }
        val missing = "$needed is not offered (offered: ${offered.joinToString(", ") { it.kind }.ifEmpty { "none" }})"
        offered.firstOrNull { it.kind == REJECT_ONCE }?.let { return Choice(it, "$REJECT_ONCE: $missing") }
        return Choice(null, "denied: $missing")
    }
}
