package kp.daemon

import kp.daemon.seats.Roots
import kp.daemon.seats.Seats
import kp.engine.Datum
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.mailroom.Operations
import kp.ops.App
import kp.ops.GitCommand
import kp.ops.GitHubApps
import kp.ops.Minted
import kp.ops.Performed
import kp.ops.ProjectPush
import kp.ops.Register
import kp.ops.StageWorktree
import kp.ops.Worktrees
import kp.rules.Office as OfficeEntry
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The office's performers (architecture.md §2, `kp-ops`; contracts.md §7, Amendment 36): `worktree.create`,
 * `worktree.remove`, `register.rebuild` and `project.commit_push`, each on the checkout the rules in force name for the
 * office [office] — the project checkout [project], the main checkout of the stage's repository, its worktree. A
 * stage's repository and branch are its list's title and first line, as `kp-facts` reads them (Amendment 32); its
 * default branch is the profile's. `notify.bora` ends `ok` at once: the inbox is the notification. Every other
 * operation without a performer answers `failed`, naming itself. [keporkak] is the checkout whose `scripts/` and
 * `docs/profiles/` it reads; [api] is GitHub's REST root; [say] is the journal.
 */
class OfficeOperations(
    private val keporkak: Path,
    private val office: String,
    private val project: Path,
    private val git: GitCommand = GitCommand(),
    private val api: URI = GitHubApps.GITHUB,
    private val say: (String) -> Unit = Journal::say,
) : Operations {
    @Volatile private var reading: Office? = null

    /** Reads [office]'s rules in force and its fold from now on: what a request's stage and checkouts are read from. */
    fun reading(office: Office) {
        reading = office
    }

    override fun perform(request: OperationRequest, done: (OperationResult) -> Unit) {
        done(when (request.operation) {
            NOTIFY_BORA -> OperationResult.Ok()
            WORKTREE_CREATE -> worktree(request) { Worktrees(git).create(it) }
            WORKTREE_REMOVE -> worktree(request) { Worktrees(git).remove(it) }
            REGISTER_REBUILD -> entry()?.let { entry -> Register(keporkak.resolve(REGISTER_SCRIPT)).rebuild(project, entry.project.efforts.toList()).answer() }
                ?: OperationResult.Failed(noOffice())
            PROJECT_COMMIT_PUSH -> ProjectPush(project, ::mint, git).commitPush(cause(request)).answer()
            else -> OperationResult.Failed("no performer for ${request.operation} on this office yet")
        })
    }

    /**
     * What [request] works on (contracts.md §7, Amendment 36): a stage's worktree operations, the main checkout of its
     * repository — they fetch and add worktrees there — the project's, the project checkout.
     */
    override fun on(request: OperationRequest): String? {
        val stage = stageOf(request)
        return checkout(request, stage, stage?.let { runCatching { place(it) }.getOrNull()?.main })
    }

    /**
     * What [request], interrupted, left on disk — only read: at the stage's worktree's path, or in the project checkout a
     * merge in progress (contracts.md §4.4, §7).
     */
    override fun left(request: OperationRequest): String? = when (request.operation) {
        WORKTREE_CREATE, WORKTREE_REMOVE -> stageOf(request)?.let { stage ->
            val worktree = runCatching { place(stage).worktree }.getOrElse { return "${it.message}; nothing touches it" }
            "${Worktrees(git).describe(worktree)}; nothing touches it"
        }
        PROJECT_COMMIT_PUSH -> "${ProjectPush(project, ::mint, git).describe()}; nothing touches it"
        else -> null
    }

    // ---- a stage's places (contracts.md §7, Amendment 36; §4.4, Amendment 32)

    private fun worktree(request: OperationRequest, perform: (StageWorktree) -> Performed): OperationResult {
        val stage = stageOf(request) ?: return OperationResult.Failed("${request.operation} names no stage")
        val place = try {
            place(stage)
        } catch (missing: Missing) {
            return OperationResult.Failed(missing.message)
        }
        return perform(place).answer()
    }

    /** The stage's worktree as the office places it: its repository's main checkout, its branches, its two folders. */
    private fun place(stage: String): StageWorktree {
        val entry = entry() ?: throw Missing(noOffice())
        val name = Seats.worktree(stage) ?: throw Missing("$stage is no stage id")
        val (effort, phase, number) = STAGE_FORM.matchEntire(stage)!!.destructured
        val folder = entry.project.efforts[effort]?.let(project::resolve) ?: throw Missing("pool/offices.yaml gives the effort $effort no folder")
        val lists = folder.takeIf { it.isDirectory() }?.listDirectoryEntries("tasks-p$phase-s$number-*.md").orEmpty().sortedBy { it.name }
        val list = lists.singleOrNull() ?: throw Missing(if (lists.isEmpty()) "$stage has no list in $folder" else "$stage has ${lists.size} lists: ${lists.joinToString { it.name }}")
        val first = Files.readAllLines(list).firstOrNull().orEmpty()
        val repo = Regex("^# ${Regex.escape(effort)}-(\\S+)-P$phase(?=\\s|$)").find(first)?.groupValues?.get(1)
            ?: throw Missing("${list.name}:1 names no repository '# $effort-<repo>-P$phase'")
        val branch = Regex("\\(PR: ([^)\\s]+)\\)").find(first)?.groupValues?.get(1) ?: throw Missing("${list.name}:1 names no branch '(PR: <branch>)'")
        val main = entry.repos[repo]?.let(Seats::home) ?: throw Missing("the office holds no repository '$repo'")
        val defaultBranch = defaultBranch(effort) ?: throw Missing("no profile in docs/profiles names the effort $effort and its default_branch")
        val roots = Roots.of(entry.roots)
        return StageWorktree(main, defaultBranch, branch, roots.worktrees.resolve(name), roots.scratch.resolve(name))
    }

    /** The `default_branch` of the profile whose frontmatter names [effort] (contracts.md §10). */
    private fun defaultBranch(effort: String): String? {
        val profiles = keporkak.resolve("docs/profiles").takeIf { it.isDirectory() } ?: return null
        return profiles.listDirectoryEntries("*.md").sortedBy { it.name }.firstNotNullOfOrNull { file ->
            val front = Files.readAllLines(file).drop(1).takeWhile { it.trim() != FENCE }.map(String::trim)
            front.takeIf { "effort: $effort" in it }?.firstOrNull { it.startsWith(DEFAULT_BRANCH) }?.removePrefix(DEFAULT_BRANCH)?.trim()
        }
    }

    /** The stage a request is about: its `with.stage`, else its cause's. */
    private fun stageOf(request: OperationRequest): String? = (request.with.fields[STAGE] as? Datum.Text)?.value
        ?: reading?.state?.let { it.delivered[request.cause]?.stage ?: it.pending[request.cause]?.stage }

    /** What a commit names as its cause: the message, its kind and its stage. */
    private fun cause(request: OperationRequest): String {
        val delivered = reading?.state?.delivered?.get(request.cause)
        return request.cause + (delivered?.let { " (${it.kind}${it.stage?.let { stage -> " of $stage" }.orEmpty()})" }.orEmpty())
    }

    // ---- GitHub (contracts.md §4.4, Amendment 33)

    /** A token of `kp-merger` for [repository], to write its contents: what the project's push is made with. */
    private fun mint(repository: String): Minted {
        val entry = entry() ?: return Minted.Failed(noOffice())
        val block = reading?.rules?.ruleSet?.pool?.github ?: return Minted.Failed("pool/offices.yaml has no block github:")
        val merger = block.apps[MERGER] ?: return Minted.Failed("github.apps holds no App $MERGER")
        return GitHubApps(Roots.of(entry.roots).secrets.resolve("apps"), api).mint(App(MERGER, merger.app, merger.installation), listOf(repository), PUSHING)
    }

    private fun entry(): OfficeEntry? = reading?.rules?.ruleSet?.pool?.offices?.get(office)

    private fun noOffice(): String = if (reading == null) "the daemon has not read its rules yet" else "pool/offices.yaml names no office '$office'"

    private class Missing(override val message: String) : Exception(message)

    companion object {
        private const val NOTIFY_BORA = "notify.bora"
        private const val WORKTREE_CREATE = "worktree.create"
        private const val WORKTREE_REMOVE = "worktree.remove"
        private const val REGISTER_REBUILD = "register.rebuild"
        private const val PROJECT_COMMIT_PUSH = "project.commit_push"
        private const val BOARD_REGENERATE = "board.regenerate"
        private const val STAGE = "stage"
        private const val FENCE = "---"
        private const val DEFAULT_BRANCH = "default_branch:"
        private const val MERGER = "kp-merger"
        private const val REGISTER_SCRIPT = "scripts/build-register.py"
        /** What the project's push asks of `kp-merger`'s token: its repository's contents, to write. */
        private val PUSHING = mapOf("contents" to "write")
        private val STAGE_FORM = Regex("^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\\.[0-9]+)$")

        /**
         * What [request] works on, as the office names it (contracts.md §7, Amendment 36): a stage's worktree operation,
         * the [main] checkout of its repository — or, with none known, the stage's worktree; the project's operations,
         * the project checkout. Null: no checkout another operation works on.
         */
        fun checkout(request: OperationRequest, stage: String?, main: Path?): String? = when (request.operation) {
            WORKTREE_CREATE, WORKTREE_REMOVE -> main?.let { "the main checkout $it" } ?: stage?.let { "the worktree of $it" }
            PROJECT_COMMIT_PUSH, REGISTER_REBUILD, BOARD_REGENERATE -> "the project checkout"
            else -> null
        }
    }
}

/** A performer's answer as the mailroom takes it. */
private fun Performed.answer(): OperationResult = when (this) {
    is Performed.Ok -> OperationResult.Ok(Datum.Fields(result.mapValues { Datum.Text(it.value) }))
    is Performed.Failed -> OperationResult.Failed(reason)
}
