package kp.facts

/**
 * `git.stray_changes`: the number of changed paths — tracked or not, not ignored — summed over the office's main
 * checkouts (Amendment 32). The project checkout is not one: the daemon and the seats write there. A main checkout that
 * cannot be read makes the fact unknown, naming it.
 */
internal fun Reading.strayChanges(): Observation {
    val office = asked.optionalText("office") ?: where.office
    if (office != where.office) throw Missing("the daemon reads its own office, ${where.office}, not $office")
    if (where.repos.isEmpty()) throw Missing("the office ${where.office} holds no main checkout")
    val counts = where.repos.toSortedMap().map { (repo, dir) ->
        val changed = try {
            git.changedPaths(git.root(dir))
        } catch (missing: Missing) {
            throw Missing("$repo: ${missing.message}")
        }
        repo to changed
    }
    return Observation.Value(counts.sumOf { it.second }, counts.joinToString("; ") { (repo, changed) -> "$repo: $changed changed ${if (changed == 1) "path" else "paths"}" })
}

/**
 * `git.branch_exists`: whether the branch the list's first line names — `(PR: <branch>)` — is a local or a
 * remote-tracking branch in the main checkout of the stage's repository: the `repo` parameter, else the `<repo>` of the
 * list's title `# <EFFORT>-<repo>-P<n>`. A repository the office does not hold: unknown.
 */
internal fun Reading.branchExists(): Observation {
    val stage = stage()
    val list = stageList(stage)
    val first = list.text.lines.firstOrNull().orEmpty()
    val line = list.text.cite(0)
    val branch = Regex("\\(PR: ([^)\\s]+)\\)").find(first)?.groupValues?.get(1) ?: throw Missing("$line names no branch '(PR: <branch>)'")
    val repo = asked.optionalText("repo")
        ?: Regex("^# ${Regex.escape(stage.effort)}-(\\S+)-P${stage.n}(?=\\s|$)").find(first)?.groupValues?.get(1)
        ?: throw Missing("$line names no repository '# ${stage.effort}-<repo>-P${stage.n}'")
    val dir = where.repos[repo] ?: throw Missing("the office holds no repository '$repo'")
    val refs = git.run(git.root(dir), "for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes").output.lines()
    val ref = refs.firstOrNull { it == "refs/heads/$branch" }
        ?: refs.firstOrNull { it.startsWith("refs/remotes/") && it.removePrefix("refs/remotes/").substringAfter('/') == branch }
    return Observation.Value(ref != null, "$line; $repo: ${ref ?: "no ref of $branch"}")
}
