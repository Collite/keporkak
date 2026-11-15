package kp.driver.claude

import kp.driver.Answerer
import kp.driver.Driver
import kp.driver.Listener
import java.nio.file.Path
import java.time.Clock

/**
 * The driver's one binding in v1: the Claude adapter over ACP (`@agentclientprotocol/claude-agent-acp`, pinned in
 * `office/adapter/`). It sets `CLAUDE_CONFIG_DIR` to the seat's config home and reads the token from [tokenFile] into
 * the variable the pinned adapter reads.
 */
object ClaudeAdapter {
    fun driver(tokenFile: Path, seatsRoot: Path, clock: Clock, answerer: Answerer, listener: Listener): Driver = TODO("S2.1 T2")
}
