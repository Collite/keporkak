package kp.seatapi

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/**
 * `POST /hook/github-token` (contracts.md §4.4, Amendment 35) on the seat API's listener: the session token as bearer, no
 * body. A dead or unknown token never gets here — the token gate answers it 401, before anything is minted. The answer
 * is `{"token": …, "expires": …}`; a refusal is 403 and a failed mint 502, each `{"error": <the reason>}`.
 */
internal class GitHubTokenRoute(private val github: GitHubTokens) {
    fun route(application: Application) = application.routing {
        post(HOOK_GITHUB_TOKEN) {
            val bound = call.attributes[BOUND]
            val (status, body) = when (val answer = withContext(Dispatchers.IO) { github.ask(bound.binding) }) {
                is TokenAnswer.Handed -> HttpStatusCode.OK to buildJsonObject { put(TOKEN, answer.token); put(EXPIRES, answer.expires) }
                is TokenAnswer.Refused -> HttpStatusCode.Forbidden to buildJsonObject { put(ERROR, answer.reason) }
                is TokenAnswer.Failed -> HttpStatusCode.BadGateway to buildJsonObject { put(ERROR, answer.reason) }
            }
            call.respondText(body.toString(), ContentType.Application.Json, status)
        }
    }

    private companion object {
        const val HOOK_GITHUB_TOKEN = "/hook/github-token"
        const val TOKEN = "token"
        const val EXPIRES = "expires"
        const val ERROR = "error"
    }
}
