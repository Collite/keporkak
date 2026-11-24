package kp.ops

import java.nio.file.Path
import java.time.Duration

/**
 * `register.rebuild` (contracts.md §7, Amendment 36): `scripts/build-register.py` of the keporkak checkout — [script] —
 * run with [python] on the project checkout, for the efforts — each with its folder — in `offices.yaml`'s order. `ok`
 * once `REGISTER.md` is written; `failed` with what the script said. What it says while it stays `ok` — a STATUS that
 * lacks a field of ST-2, its file and the field (Amendment 43) — [say], the journal, hears. The script needs the standard
 * library only.
 */
class Register(private val script: Path, private val python: String = "python3", private val limit: Duration = LIMIT, private val say: (String) -> Unit = {}) {
    fun rebuild(project: Path, efforts: List<Pair<String, String>>): Performed {
        val ran = runOnce(listOf(python, script.toString(), "--project", project.toString()) + efforts.flatMap { (effort, folder) -> listOf("--effort", "$effort=$folder") }, limit)
        if (!ran.ok) return Performed.Failed("${script.fileName} failed: ${ran.output.trim()}")
        ran.output.lines().filter { it.isNotBlank() }.forEach { say("register.rebuild: $it") }
        return Performed.Ok()
    }

    private companion object {
        /** How long the script may take: it reads a few STATUS files. */
        val LIMIT: Duration = Duration.ofMinutes(1)
    }
}
