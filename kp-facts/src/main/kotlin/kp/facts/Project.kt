package kp.facts

import kotlin.io.path.isRegularFile

/** A dated entry of STATUS (Amendment 32): a line that begins `- **<yyyy-mm-dd>`. */
private val ENTRY = Regex("^- \\*\\*[0-9]{4}-[0-9]{2}-[0-9]{2}")

internal fun Reading.statusEntries(): Observation {
    val status = Folder.of(where, asked.text("effort")).read("STATUS.md")
    val entries = status.lines.indices.filter { ENTRY.containsMatchIn(status.lines[it]) }
    return Observation.Value(entries.size, if (entries.isEmpty()) "${status.path}: no dated entry" else status.cite(entries))
}

/** True when a dated entry names the stage first: `- **<date> — S<n.m> `. */
internal fun Reading.statusEntryPresent(): Observation {
    val stage = stage()
    val status = Folder.of(where, stage.effort).read("STATUS.md")
    val first = Regex("^- \\*\\*[0-9]{4}-[0-9]{2}-[0-9]{2} — ${Regex.escape(stage.local)} ")
    val entry = status.lines.indexOfFirst { first.containsMatchIn(it) }
    return if (entry >= 0) Observation.Value(true, status.cite(entry)) else Observation.Value(false, "${status.path}: no dated entry names ${stage.local} first")
}

/**
 * True when `REGISTER.md` at the checkout's root is in a commit not older than the last commit of any effort's
 * `STATUS.md`; false with no `REGISTER.md`. Older is by committer time.
 */
internal fun Reading.registerFresh(): Observation {
    if (!where.project.resolve(REGISTER).isRegularFile()) return Observation.Value(false, "$REGISTER does not exist")
    val project = git.root(where.project)
    val register = git.commits(project, "-1", "--", REGISTER).firstOrNull() ?: return Observation.Value(false, "$REGISTER is in no commit")
    val statuses = where.efforts.keys.sorted().mapNotNull { effort ->
        val path = Folder.of(where, effort).prefix + "STATUS.md"
        git.commits(project, "-1", "--", path).firstOrNull()?.let { path to it }
    }
    val (path, status) = statuses.maxByOrNull { it.second.time } ?: throw Missing("no effort's STATUS.md is in a commit")
    return Observation.Value(register.time >= status.time,
        "$REGISTER: commit ${register.short} (${register.at}); $path: commit ${status.short} (${status.at})")
}

/**
 * True when the project checkout has no changed path, `mail/` included, and its branch holds no commit its upstream — as
 * the checkout last saw it — lacks; false with no upstream.
 */
internal fun Reading.cleanAndPushed(): Observation {
    val project = git.root(where.project)
    val changed = git.changedPaths(project)
    val branch = git.run(project, "symbolic-ref", "--quiet", "--short", "HEAD", ok = setOf(0, 1))
    if (branch.code != 0) return Observation.Value(false, "project: $changed changed; a detached HEAD has no upstream")
    val name = branch.output.trim()
    val upstream = git.run(project, "for-each-ref", "--format=%(upstream)", "refs/heads/$name").output.trim()
    if (upstream.isEmpty()) return Observation.Value(false, "project: $changed changed; $name has no upstream")
    if (git.run(project, "rev-parse", "--verify", "--quiet", "$upstream^{commit}", ok = setOf(0, 1)).code != 0) {
        return Observation.Value(false, "project: $changed changed; $name's upstream $upstream was never seen")
    }
    val ahead = git.run(project, "rev-list", "--count", "$upstream..HEAD").output.trim().toInt()
    return Observation.Value(changed == 0 && ahead == 0, "project: $changed changed; $name is $ahead ahead of $upstream")
}

/**
 * The tracked files of the project checkout, outside its `mail/`, in a shape the evidence rules refuse (EV-5): `*.log`,
 * `*.xml`, `*.jsonl`, or under a folder named `review` or `proofs`.
 */
internal fun Reading.proofFiles(): Observation {
    val project = git.root(where.project)
    val tracked = git.run(project, "ls-files", "-z").output.split("\u0000").filter { it.isNotEmpty() }
    val refused = tracked.filter { path ->
        val parts = path.split("/")
        parts.first() != "mail" && (REFUSED_ENDINGS.any { path.endsWith(it) } || parts.dropLast(1).any { it in REFUSED_FOLDERS })
    }.sorted()
    return Observation.Value(refused.size, "project: ${tracked.size} tracked files" + if (refused.isEmpty()) "" else "; refused: ${refused.joinToString(", ")}")
}

/** The changed paths of the checkout [dir] — tracked or not, not ignored — as `n changed path(s)` would say; the number. */
internal fun Git.changedPaths(dir: java.nio.file.Path): Int {
    val fields = run(dir, "status", "--porcelain=v1", "-z", "--untracked-files=all").output.split("\u0000").filter { it.isNotEmpty() }
    var entries = 0
    var index = 0
    while (index < fields.size) {
        val status = fields[index].take(2)
        entries++
        index += if ('R' in status || 'C' in status) 2 else 1
    }
    return entries
}

private const val REGISTER = "REGISTER.md"
private val REFUSED_ENDINGS = listOf(".log", ".xml", ".jsonl")
private val REFUSED_FOLDERS = setOf("review", "proofs")
