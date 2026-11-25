package kp.facts

/** How many of a main checkout's changed paths the evidence of `git.stray_changes` names. */
private const val SHOWN = 10

/**
 * `git.stray_changes`: the number of changed paths — tracked or not, not ignored — summed over the office's main
 * checkouts (Amendment 32). The project checkout is not one: the daemon and the seats write there. A main checkout that
 * cannot be read makes the fact unknown, naming it. The evidence names each main checkout with its count and, of one
 * with changed paths, up to ten of them (Amendment 45: what the daemon's report holds).
 */
internal fun Reading.strayChanges(): Observation {
    val office = asked.optionalText("office") ?: where.office
    if (office != where.office) throw Missing("the daemon reads its own office, ${where.office}, not $office")
    if (where.repos.isEmpty()) throw Missing("the office ${where.office} holds no main checkout")
    val counts = where.repos.toSortedMap().map { (repo, dir) ->
        val changed = try {
            git.changed(git.root(dir))
        } catch (missing: Missing) {
            throw Missing("$repo: ${missing.message}")
        }
        repo to changed
    }
    return Observation.Value(counts.sumOf { it.second.size }, counts.joinToString("; ") { (repo, changed) ->
        "$repo: ${changed.size} changed ${if (changed.size == 1) "path" else "paths"}" +
            if (changed.isEmpty()) "" else " — " + changed.take(SHOWN).joinToString(", ") + if (changed.size > SHOWN) ", …" else ""
    })
}

/**
 * `git.branch_exists`: whether the stage's branch is a local or a remote-tracking branch in the main checkout of the
 * stage's repository: the `repo` parameter, else the repository. Both are those of its `worktree.create` record
 * (Amendment 40), never its list's first line; with no record, unknown. A repository the office does not hold: unknown.
 */
internal fun Reading.branchExists(): Observation {
    val made = made(stage())
    val branch = made.branch
    val repo = asked.optionalText("repo") ?: made.repository
    val dir = where.repos[repo] ?: throw Missing("the office holds no repository '$repo'")
    val refs = git.run(git.root(dir), "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes").output.lines()
    val ref = refs.firstOrNull { it == "refs/heads/$branch" }
        ?: refs.firstOrNull { it.startsWith("refs/remotes/") && it.removePrefix("refs/remotes/").substringAfter('/') == branch }
    return Observation.Value(ref != null, "${made.record}; $repo: ${ref ?: "no ref of $branch"}")
}
