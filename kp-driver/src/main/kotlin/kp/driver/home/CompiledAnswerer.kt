package kp.driver.home

import kp.driver.Answer
import kp.driver.Answerer
import kp.driver.Explained
import kp.driver.PermissionRequest
import java.nio.file.Path

/**
 * The answerer of S2.1's driver, read from the seat's `permissions.json` (contracts.md §10, T4): a deny rule denies,
 * naming its border; no rule — the default, by the paths in the request's input; a request whose paths cannot be
 * read is denied. It decides nothing else and reads no rule file.
 */
class CompiledAnswerer(private val seatsRoot: Path) : Answerer {
    override suspend fun answer(request: PermissionRequest): Answer = explained(request).answer
    override suspend fun explained(request: PermissionRequest): Explained = TODO("T4")
}
