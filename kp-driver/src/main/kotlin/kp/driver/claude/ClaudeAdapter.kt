package kp.driver.claude

import kp.driver.Answerer
import kp.driver.Driver
import kp.driver.Listener
import kp.driver.Outcome
import kp.driver.SessionSpec
import kp.driver.acp.AcpDriver
import kp.driver.acp.AgentBinding
import kp.driver.acp.Environment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * The driver's one binding in v1: the Claude adapter over ACP (`@agentclientprotocol/claude-agent-acp`, pinned in
 * `office/adapter/`). Its process gets `CLAUDE_CONFIG_DIR` set to the seat's config home, and the token read from
 * [tokenFile] in the variable the pinned adapter reads (design/06 Q10). The file is read at every start; its value is
 * never written anywhere.
 */
class ClaudeAdapter(private val tokenFile: Path) : AgentBinding {
    override fun environment(spec: SessionSpec): Outcome<Environment> {
        val token = try {
            Files.readString(tokenFile).trim()
        } catch (e: IOException) {
            return Outcome.Failed("the Claude token cannot be read from $tokenFile (${e.javaClass.simpleName})")
        }
        if (token.isEmpty()) return Outcome.Failed("the Claude token file $tokenFile is empty")
        val variables = spec.environment + mapOf(CONFIG_DIR to spec.configHome.toString(), TOKEN to token)
        return Outcome.Ok(Environment(variables, secrets = setOf(token)))
    }

    companion object {
        const val CONFIG_DIR = "CLAUDE_CONFIG_DIR"
        const val TOKEN = "CLAUDE_CODE_OAUTH_TOKEN"

        fun driver(tokenFile: Path, seatsRoot: Path, clock: Clock, answerer: Answerer, listener: Listener): Driver =
            AcpDriver(ClaudeAdapter(tokenFile), seatsRoot, clock, answerer, listener)
    }
}
