package kp.facts

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The office's `git`, its command line, looked up on [path] — a `PATH`; null or empty: there is none. It reads and never
 * writes: no optional lock is taken, so a status does not refresh the index; no fsmonitor is started; nothing is fetched.
 * A command that cannot run or fails is what was missing for the fact that asked it ([Missing]); one that has not ended
 * within [limit] is too.
 */
class Git(val path: String?, private val limit: Duration = LIMIT) {
    private val executable: Path? = path.orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
        .map { Path.of(it, "git") }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    /** Runs `git` in [dir] with [args]; its standard output. An exit outside [ok] is what was missing. */
    internal fun run(dir: Path, vararg args: String, ok: Set<Int> = setOf(0)): Ran {
        val git = executable ?: throw Missing("no `git` on the path")
        if (!Files.isDirectory(dir)) throw Missing("$dir does not exist")
        val command = listOf(git.toString(), "--no-optional-locks", "-c", "core.quotepath=off", "-c", "core.fsmonitor=false", "-c", "log.showSignature=false",
            "-C", dir.toString()) + args
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).apply {
            environment().keys.removeAll { it.startsWith("GIT_") }
            environment()["GIT_TERMINAL_PROMPT"] = "0"
            environment()["LC_ALL"] = "C"
        }.start()
        // Both streams are read beside the wait, so the limit holds while `git` — or a child it left — keeps its output
        // open (review-025's follow-up, Collite/keporkak#52). One limit holds for all of it: `git`, then each of its two
        // streams, until the same moment (Collite/keporkak#59).
        val deadline = System.nanoTime() + limit.toNanos()
        var output = ""
        var errors = ""
        val readers = listOf(
            thread(isDaemon = true, name = "kp-facts-git") { output = process.inputStream.bufferedReader().readText() },
            thread(isDaemon = true, name = "kp-facts-git") { errors = process.errorStream.bufferedReader().readText() },
        )
        if (!process.waitFor(limit.toNanos(), TimeUnit.NANOSECONDS)) {
            end(process)
            throw Missing("git ${args.first()} in $dir did not end within ${limit.toSeconds()} s; it was ended")
        }
        // `git` has ended; what it wrote is read to its end, unless a process it left holds its output open past the limit.
        for (reader in readers) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) reader.join(left)
        }
        if (readers.any { it.isAlive }) throw Missing("git ${args.first()} in $dir ended, but its output stayed open past ${limit.toSeconds()} s")
        val code = process.exitValue()
        if (code !in ok) throw Missing("git ${args.joinToString(" ")} in $dir failed: " + (errors.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "exit $code"))
        return Ran(code, output)
    }

    /** Ends [process] and every process it started, which may hold its output open. */
    private fun end(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** [dir], when it is the root of a git checkout: what `git` there reads is that checkout's, not one around it. */
    internal fun root(dir: Path): Path {
        val top = run(dir, "rev-parse", "--show-toplevel").output.trim()
        if (Path.of(top).toRealPath() != dir.toRealPath()) throw Missing("$dir is not the root of a git checkout, but in $top")
        return dir
    }

    companion object {
        /** How long a `git` command may take: 30 s. */
        val LIMIT: Duration = Duration.ofSeconds(30)
    }
}

/** A `git` command's exit code and standard output. */
internal data class Ran(val code: Int, val output: String)

/** A commit of a checkout's history: its sha, its committer time in seconds and as `git` writes it (`%cI`). */
internal data class Commit(val sha: String, val time: Long, val at: String) {
    val short: String get() = sha.take(12)
}

/** The commits of [dir]'s history from `HEAD` that `git log` lists with [args]; none when `HEAD` has no commit. */
internal fun Git.commits(dir: java.nio.file.Path, vararg args: String): List<Commit> {
    if (run(dir, "rev-parse", "--verify", "--quiet", "HEAD", ok = setOf(0, 1)).code != 0) return emptyList()
    return run(dir, "log", "--format=%H%x09%ct%x09%cI", *args).output.lines().filter { it.isNotBlank() }.map { line ->
        val (sha, time, at) = line.split("\t")
        Commit(sha, time.toLong(), at)
    }
}
