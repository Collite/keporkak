package kp.facts

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * A git checkout a spec builds in a temp folder with the real `git` and dated commits (S3.1 T1). The fixture's own git
 * reads no user or system configuration, so what a spec builds is the same on every machine; the observers read it with
 * the office's `git` as it is.
 */
class Checkout(val dir: Path) {
    /** Writes [text] at [path], relative to the checkout. */
    fun write(path: String, text: String): Checkout = apply { dir.resolve(path).also { it.parent.createDirectories() }.writeText(text) }

    fun delete(path: String): Checkout = apply { dir.resolve(path).deleteIfExists() }

    /** Commits everything in the working tree at [at] — the author's and the committer's time; its sha. */
    fun commit(at: String, message: String = "at $at"): String {
        git("add", "-A")
        git("commit", "--quiet", "--allow-empty", "-m", message, dated = at)
        return git("rev-parse", "HEAD").trim()
    }

    fun git(vararg args: String, dated: String? = null): String = runGit(dir, args.toList(), dated)

    /**
     * What an observer must leave as it was: the status — ignored files too — the refs, the object count and the index's
     * bytes.
     */
    fun state(): String = listOf(
        git("status", "--porcelain=v1", "--ignored", "--untracked-files=all"),
        git("for-each-ref", "--format=%(refname) %(objectname)"),
        git("count-objects", "-v"),
        dir.resolve(".git/index").takeIf(Files::exists)?.readBytes()?.contentHashCode()?.toString() ?: "no index",
    ).joinToString("\n--\n")

    companion object {
        /** A new repository on the branch `main`, with no commit. */
        fun init(dir: Path): Checkout = Checkout(dir.createDirectories()).apply { git("init", "--quiet", "--initial-branch=main") }
    }
}

/** Runs the fixture's `git` in [dir]; a failure fails the spec. */
fun runGit(dir: Path, args: List<String>, dated: String? = null): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).apply {
        environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_AUTHOR_NAME", "Fixture")
            put("GIT_AUTHOR_EMAIL", "fixture@example.invalid")
            put("GIT_COMMITTER_NAME", "Fixture")
            put("GIT_COMMITTER_EMAIL", "fixture@example.invalid")
            put("LC_ALL", "C")
            if (dated != null) {
                put("GIT_AUTHOR_DATE", dated)
                put("GIT_COMMITTER_DATE", dated)
            }
        }
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} did not end" }
    check(process.exitValue() == 0) { "git ${args.joinToString(" ")} in $dir failed:\n$output" }
    return output
}

/** What the log knows, as a spec sets it: each stage's state, the exits it dates, the states the rules call active. */
class KnownLog(
    val states: MutableMap<String, String> = mutableMapOf(),
    val exits: MutableMap<String, String> = mutableMapOf(),
    private val active: Set<String> = setOf("assigned", "building"),
) : WhatTheLogKnows {
    override fun state(stage: String): StageState? = states[stage]?.let { StageState(it, exits[stage] ?: "2026-11-01T09:00:00Z") }
    override fun stages(): Map<String, StageState> = states.keys.associateWith { state(it)!! }
    override fun activeStates(): Set<String> = active

    /** [stage] exited at [at], as the log dates it. */
    fun exited(stage: String, at: String) {
        states[stage] = "exited"
        exits[stage] = at
    }
}

/**
 * An office's checkouts on a temp folder: the project checkout — the effort KP at its root, TOY under `toy/` — and two
 * main checkouts, `keporkak` and `kp-toy`, each with one commit. [observe] asks `kp-facts`' one entry and holds that the
 * observation changed nothing in any of them.
 */
class Office(folder: java.io.File, val log: KnownLog = KnownLog(), private val path: String? = System.getenv("PATH")) {
    private val root: Path = folder.toPath()
    val project: Checkout = Checkout.init(root.resolve("project"))
    val keporkak: Checkout = Checkout.init(root.resolve("keporkak")).apply { write("README.md", "keporkak\n").commit("2026-11-01T09:00:00+01:00") }
    val toy: Checkout = Checkout.init(root.resolve("kp-toy")).apply { write("README.md", "kp-toy\n").commit("2026-11-01T09:00:00+01:00") }

    val checkouts = Checkouts(
        office = "panda",
        project = project.dir,
        efforts = mapOf("KP" to ".", "TOY" to "toy"),
        repos = mapOf("keporkak" to keporkak.dir, "kp-toy" to toy.dir),
    )

    fun facts(): ProjectFacts = ProjectFacts(checkouts, log, Git(path))

    /** [fact] with [params] — the observation, after holding that no checkout changed under it. */
    fun observe(fact: String, vararg params: Pair<String, Any>): Observation {
        val before = listOf(project, keporkak, toy).map { it.state() }
        val answer = facts().observe(fact, params.toMap())
        listOf(project, keporkak, toy).map { it.state() } shouldBe before
        return answer
    }
}

/** A value with its evidence. */
fun value(value: Any, evidence: String) = Observation.Value(value, evidence)

/** The value alone, for a spec that reads it alone; null when unknown. */
val Observation.value: Any? get() = (this as? Observation.Value)?.value

/** What was missing, when unknown; null when known. */
val Observation.missing: String? get() = (this as? Observation.Unknown)?.missing
