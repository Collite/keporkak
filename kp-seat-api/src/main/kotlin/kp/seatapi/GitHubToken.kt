package kp.seatapi

/**
 * What a seat's `git` and `gh` are answered when they ask for a GitHub token (contracts.md §4.4, Amendment 35): the
 * daemon mints one then, for the App `github.seats` gives the seat's role, narrowed to the repository of its stage — or
 * says why not. The seat API asks and answers; who mints, and for what, is the daemon's.
 */
fun interface GitHubTokens {
    /** A token for the seat [binding] names, minted now; or why there is none. Nothing is kept from one ask for the next. */
    fun ask(binding: Binding): TokenAnswer

    companion object {
        /** A daemon that hands out no GitHub token — a rehearsal's: every ask is refused. */
        val NONE = GitHubTokens { TokenAnswer.Refused("this daemon hands out no GitHub token") }
    }
}

/** The answer to one ask: a token and when it expires; refused — the role has no entry —; or failed, with the reason. */
sealed interface TokenAnswer {
    /** A token minted for the ask. It is not shown: [toString] hides it. */
    class Handed(val token: String, val expires: String) : TokenAnswer {
        override fun toString(): String = "Handed(a token until $expires)"
    }

    data class Refused(val reason: String) : TokenAnswer

    data class Failed(val reason: String) : TokenAnswer
}
