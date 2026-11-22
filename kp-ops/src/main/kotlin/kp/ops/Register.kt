package kp.ops

import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * `register.rebuild` (contracts.md §7, Amendment 36): `scripts/build-register.py` of the keporkak checkout — [script] —
 * run with [python] on the project checkout, for the efforts — each with its folder — in `offices.yaml`'s order. `ok`
 * once `REGISTER.md` is written; `failed` with what the script said. The script needs the standard library only.
 */
class Register(private val script: Path, private val python: String = "python3", private val limit: Duration = LIMIT) {
    fun rebuild(project: Path, efforts: List<Pair<String, String>>): Performed {
        val command = listOf(python, script.toString(), "--project", project.toString()) + efforts.flatMap { (effort, folder) -> listOf("--effort", "$effort=$folder") }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        } catch (error: java.io.IOException) {
            return Performed.Failed("$python could not be started: ${error.message}")
        }
        val output = StringBuilder()
        val reader = Thread.ofVirtual().start { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return Performed.Failed("${script.fileName} did not end in ${limit.toSeconds()} s")
        }
        reader.join(READER_MILLIS)
        return if (process.exitValue() == 0) Performed.Ok() else Performed.Failed("${script.fileName} failed: ${output.toString().trim()}")
    }

    private companion object {
        val LIMIT: Duration = Duration.ofMinutes(1)
        const val READER_MILLIS = 10_000L
    }
}
