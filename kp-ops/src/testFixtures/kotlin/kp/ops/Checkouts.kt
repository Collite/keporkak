package kp.ops

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The fixtures of S3.3's performer specs: repositories the spec builds on disk with the real `git` — a bare remote, the
 * checkouts cloned from it, another clone that moves the remote — and nothing on GitHub.
 */

/** The spec's own `git` in [dir], reading no user or system configuration; its output, trimmed. */
fun git(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).apply {
        environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_AUTHOR_NAME" to "Fixture", "GIT_AUTHOR_EMAIL" to "fixture@example.invalid",
                "GIT_COMMITTER_NAME" to "Fixture", "GIT_COMMITTER_EMAIL" to "fixture@example.invalid"))
        }
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} in $dir failed:\n$output" }
    return output.trim()
}

/** A folder of the spec's own under the build's. */
fun folder(prefix: String): Path = Files.createTempDirectory(Path.of(System.getProperty("kp.root") ?: ".").resolve("build").createDirectories(), prefix)

/** [text] written at [path] under [dir], its folders made. */
fun write(dir: Path, path: String, text: String): Path = dir.resolve(path).also { it.parent.createDirectories() }.apply { writeText(text) }

/** [text] added at the end of the file at [path] under [dir]. */
fun append(dir: Path, path: String, text: String) = write(dir, path, dir.resolve(path).readText() + text)

/**
 * A bare remote [name] whose branch `master` holds [files], committed once, and a way to clone it and to move it — a
 * commit pushed from a clone of its own, as another office or Bora would.
 */
class Remote(name: String, files: Map<String, String>) {
    val home: Path = folder("remote-")
    val dir: Path = home.resolve(name).also { git(home, "init", "--quiet", "--bare", "--initial-branch=master", it.toString()) }
    private val elsewhere: Path = clone("elsewhere").also { clone ->
        files.forEach { (path, text) -> write(clone, path, text) }
        git(clone, "add", "-A")
        git(clone, "commit", "--quiet", "-m", "the first commit")
        git(clone, "push", "--quiet", "origin", "master")
    }

    /** A clone of this remote at [name] beside it, its `master` tracking the remote's. */
    fun clone(name: String): Path = home.resolve(name).also { git(home, "clone", "--quiet", dir.toString(), it.toString()) }

    /** The remote moves: one commit, by someone else, that writes [files]. */
    fun moves(files: Map<String, String>, message: String = "moved elsewhere") {
        git(elsewhere, "pull", "--quiet", "--no-rebase", "origin", "master")
        files.forEach { (path, text) -> write(elsewhere, path, text) }
        git(elsewhere, "add", "-A")
        git(elsewhere, "commit", "--quiet", "-m", message)
        git(elsewhere, "push", "--quiet", "origin", "master")
    }

    /** The sha of the remote's [branch]. */
    fun head(branch: String = "master"): String = git(dir, "rev-parse", branch)

    /** The text of [path] in the remote's [branch]. */
    fun show(path: String, branch: String = "master"): String = git(dir, "show", "$branch:$path")
}
