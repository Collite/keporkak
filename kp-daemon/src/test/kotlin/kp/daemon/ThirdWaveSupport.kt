package kp.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kp.daemon.seats.GitHubIdentity
import kp.engine.Datum
import kp.engine.FactRef
import kp.facts.Answer
import kp.facts.Observation
import kp.facts.RecordedGitHub
import kp.facts.RecordedGitHub.Companion.recorded
import kp.facts.TOY_BRANCH
import kp.facts.TOY_HEAD
import kp.facts.TOY_STAGE
import kp.facts.byTheReviewer
import kp.facts.comment
import kp.facts.filedIssue
import kp.facts.pull
import kp.facts.toyCheckRuns
import kp.facts.toyComments
import kp.facts.toyCompare
import kp.facts.toyPullRequests
import kp.facts.toyReviews
import kp.log.LogWriter
import kp.mailroom.Clock
import kp.mailroom.OperationRequest
import kp.mailroom.OperationResult
import kp.model.OfficeId
import kp.model.StageId
import kp.ops.FakeApps
import kp.seatapi.Binding
import kp.seatapi.Tokens
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** When the records an office of S3.11's specs starts with were written. */
const val RECORDED_AT = "2026-11-24T08:00:00Z"

/** The `worktree.create` an office of S3.11's specs starts with, as the office's performer writes it (contracts.md §4.4, §7; Amendments 40, 42, 43). */
enum class WorktreeMade { OK, FAILED, NONE }

/**
 * KP/P3/S3.11's office (T1): S3.2's office reading GitHub ([GitHubOffice]) made whole for a stage's places, and every
 * reader of a stage's repository and branch in front of it. The toy's stage `TOY/P0/S0.1` is `building`; its list names
 * `kp-toy` and the branch of the toy's pull request #1. The office holds two main checkouts the spec builds — `kp-toy`,
 * which holds that branch, and `keporkak`, which does not — and the stage's worktree, made from `kp-toy`'s on that branch
 * under the office's own worktrees root. With [made] OK the log holds the stage's `worktree.create` as the office's
 * performer writes it: `requested` with the stage, then `ok` naming `kp-toy`, the branch and the worktree's path; FAILED,
 * the same request `failed`; NONE, no such record. Beside it the log holds what GitHub's facts that read the log ask for:
 * a `review-request` and a `merge` whose judgements observed the toy's head, and a `verdict` delivered.
 *
 * The readers are the service's: its world ([ServiceWorld]) — GitHub's nine facts and `git.branch_exists` — its operations
 * ([GitHubOperations] before [OfficeOperations]) and a seat's GitHub identity ([GitHubIdentity]), asked through the seat
 * API's listener as a seat's programs ask. GitHub is one local server: the recordings of `Collite/kp-toy` and the App
 * endpoints, the four Apps with keys of the spec's.
 */
class RecordedOffice(made: WorktreeMade = WorktreeMade.OK) : AutoCloseable {
    private val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "recorded-")
    val project: Path = repository(folder.resolve("project")).also { project ->
        write(project, LIST_PATH, GitHubOffice.LIST)
        git(project, "add", "-A")
        git(project, "commit", "--quiet", "-m", "cut S0.1")
    }
    val worktrees: Path = folder.resolve("worktrees").createDirectories()
    val scratch: Path = folder.resolve("scratch").createDirectories()
    /** The main checkout of `kp-toy`: the stage's branch is there, and the stage's worktree is made from it. */
    val toy: Path = repository(folder.resolve("kp-toy")).also { toy ->
        write(toy, "README.md", "kp-toy\n")
        git(toy, "add", "-A")
        git(toy, "commit", "--quiet", "-m", "the toy")
        git(toy, "branch", TOY_BRANCH)
    }
    /** The main checkout of `keporkak`: no branch of the toy's stage is there. */
    val keporkak: Path = repository(folder.resolve("keporkak")).also { keporkak ->
        write(keporkak, "README.md", "keporkak\n")
        git(keporkak, "add", "-A")
        git(keporkak, "commit", "--quiet", "-m", "keporkak")
    }
    /** The stage's worktree, where the office places it: `<roots.worktrees>/toy-p0-s0.1`. */
    val worktree: Path = worktrees.resolve(WORKTREE).also { git(toy, "worktree", "add", "--quiet", it.toString(), TOY_BRANCH) }
    val secrets: Path = folder.resolve("secrets").createDirectories()
    private val server = RecordedGitHub.local()
    val github = RecordedGitHub(server)
    val apps = FakeApps(server, secrets.resolve("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    val root: Path = TestOffice.copyOfTheRules().also { root ->
        val offices = root.resolve("pool/offices.yaml")
        offices.writeText(offices.readText()
            .replace(Regex("(?m)^    project: \\{checkout: [^,]+,"), "    project: {checkout: \"$project\",")
            .replace(Regex("(?m)^    repos: .*$"), "    repos: {keporkak: \"$keporkak\", kp-toy: \"$toy\"}")
            .replace(Regex("(?m)^      worktrees: .*$"), "      worktrees: \"$worktrees\"")
            .replace(Regex("(?m)^      scratch: .*$"), "      scratch: \"$scratch\"")
            .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"$secrets\""))
        checkout.resolve("docs/profiles").listDirectoryEntries("*.md").forEach { it.copyTo(root.resolve("docs/profiles").createDirectories().resolve(it.name)) }
    }
    val journal = CopyOnWriteArrayList<String>()
    private val rules = InForceRules.of(RuleFiles(root).read()).let { (rules, load) -> rules ?: error("the rules do not load: ${load.problems.joinToString("; ")}") }
    val world = ServiceWorld(ProjectWorld.of(rules, "panda", project) { journal += it }, GitHubWorld.of("panda", project, github.api) { journal += it })
    private val onTheOffice = OfficeOperations(root, "panda", project, api = github.api) { journal += it }
    private val operations = GitHubOperations.of("panda", onTheOffice, github.api) { journal += it }
    val office: Office
    val identity: GitHubIdentity
    private val tokens = Tokens()
    private val listener: kp.seatapi.SeatApi

    init {
        records(project, made, worktree)
        office = Office.start("panda", project, root, world, operations, NoSeats, Clock { Instant.parse("2026-11-24T09:00:00Z") }) { office ->
            world.reading(office)
            onTheOffice.reading(office)
            operations.reading(office)
        }
        identity = identity(this)
        listener = office.seatApi(tokens, "127.0.0.1:0", identity)
        server.start()
        // GitHub as the toy holds the stage's pull request: open, its head checked green, reviewed, answered.
        github.toyPullRequests(pull("state" to "open", "closed_at" to null, "merged_at" to null))
        github.toyCheckRuns(TOY_HEAD, recorded("check-runs.json"))
        github.toyReviews(byTheReviewer(TOY_HEAD, "APPROVED", "2026-11-24T08:30:00Z"))
        github.toyComments(comment("created_at" to "2026-11-24T08:40:00Z", "body" to "B1 — fixed in 028e9e2"))
        github.toyCompare(TOY_HEAD, TOY_HEAD, Answer(200, recorded("compare.json")))
        github.onRequests("PUT", "/repos/Collite/kp-toy/pulls/1/merge", Answer(200, recorded("merge-200.json")))
        github.onRequests("PATCH", "/repos/Collite/kp-toy/pulls/1", Answer(200, recorded("pull-closed.json")))
        github.onRequests("POST", "/repos/Collite/kp-toy/issues", Answer(201, filedIssue("number" to 7)))
    }

    /** The stage's list's first line, rewritten to [first] after the worktree was made — in the working tree, as a coder writes its list. */
    fun rewrite(first: String) {
        val list = project.resolve(LIST_PATH)
        list.writeText((listOf(first) + list.readLines().drop(1)).joinToString("\n") + "\n")
    }

    /** [fact] about the stage, as a judgement asks it: its value and its evidence, or unknown and why. */
    fun observe(fact: String, vararg params: Pair<String, Datum>): Observation {
        val asked = FactRef(fact, params.toMap())
        return world.github.observation(asked, TOY_STAGE) ?: world.project.observation(asked, TOY_STAGE)?.second ?: error("no world answers $fact")
    }

    /** GitHub's nine facts and `git.branch_exists`, each asked as the rules ask it, by its name. */
    fun everyReader(): Map<String, Observation> = mapOf(
        "pr.ready" to observe("pr.ready"),
        "pr.closed" to observe("pr.closed"),
        "pr.merged" to observe("pr.merged"),
        "pr.head" to observe("pr.head"),
        "pr.commits_since" to observe("pr.commits_since", "message" to Datum.Text(REVIEW_REQUEST)),
        "pr.review_on" to observe("pr.review_on", "sha" to Datum.Text(TOY_HEAD)),
        "pr.blocker_responses" to observe("pr.blocker_responses", "ids" to Datum.Items(listOf(Datum.Text("B1")))),
        "pr.evidence_present" to observe("pr.evidence_present"),
        "ci.state" to observe("ci.state", "of" to Datum.Text(TOY_HEAD)),
        "git.branch_exists" to observe("git.branch_exists"),
    )

    /** [operation] for the stage, caused by [cause], performed by the service's performers: `ok <result>` or `failed <reason>`. */
    fun perform(operation: String, cause: String, with: Map<String, Datum> = emptyMap()): String {
        val done = CompletableFuture<OperationResult>()
        operations.perform(OperationRequest(operation, cause, Datum.Fields(mapOf("stage" to Datum.Text(TOY_STAGE)) + with))) { done.complete(it) }
        return when (val result = done.get(60, TimeUnit.SECONDS)) {
            is OperationResult.Ok -> "ok ${result.result.json()}"
            is OperationResult.Failed -> "failed ${result.reason}"
        }
    }

    /** `token.mint` at the start of a seat of [role] bound to the stage: `ok <result>` or `failed <reason>`. */
    fun mint(role: String): String = when (val result = identity.mint(Binding("$role-1", role, "panda", TOY_STAGE, "claude-opus-5-5", "medium"))) {
        is OperationResult.Ok -> "ok ${result.result.json()}"
        is OperationResult.Failed -> "failed ${result.reason}"
    }

    /** A seat of [role] bound to the stage asks for a token, as its programs do: `POST /hook/github-token`; the status and the body. */
    fun ask(role: String): Pair<Int, String> {
        val session = "kp-spec-" + java.util.UUID.randomUUID().toString().replace("-", "")
        tokens.register(session, Binding("$role-1", role, "panda", TOY_STAGE, "claude-opus-5-5", "medium"))
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${listener.address.port}/hook/github-token"))
            .header("Authorization", "Bearer $session").POST(HttpRequest.BodyPublishers.noBody()).build()
        val answer = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return answer.statusCode() to answer.body()
    }

    /** The repositories each token minted so far was narrowed to, in order, by the App it was minted for: `<app> <repositories>`. */
    fun mintedFor(): List<String> = apps.issued.map { "${it.app.identity} ${it.repositories}" }

    /** What GitHub was asked, each `<method> <target>`: the App endpoints left out. */
    fun asked(): List<String> = github.requests.map { "${it.method} ${it.target}" }

    override fun close() {
        listener.close()
        office.close()
        server.stop(0)
        Judgements.check(project)
    }

    companion object {
        const val LIST_PATH = "toy/tasks-p0-s0.1-skeleton.md"
        const val WORKTREE = "toy-p0-s0.1"
        /** The approval that made the worktree; the review-request and the merge whose judgements observed the toy's head; a verdict. */
        const val APPROVAL = "m-panda-900001"
        const val REVIEW_REQUEST = "m-panda-900002"
        const val MERGE = "m-panda-900003"
        const val VERDICT = "m-panda-900004"

        /** The toy's list's first line, with its title naming [repo] and its `(PR: …)` naming [branch]. */
        fun heading(repo: String = "kp-toy", branch: String = TOY_BRANCH) = "# TOY-$repo-P0 · S0.1 — The skeleton            (PR: $branch)"

        /** A seat's GitHub identity as the service makes it for [office]. */
        private fun identity(office: RecordedOffice) =
            GitHubIdentity("panda", { office.office.rules }, OfficeLog().also { it.office = office.office }, office.secrets.resolve("apps"), office.github.api) { office.journal += it }

        /** The records [project]'s log starts with — the stage `building`, its `worktree.create` as [made] says, the messages GitHub's facts read. */
        private fun records(project: Path, made: WorktreeMade, worktree: Path) {
            fun message(id: String, from: String, to: String, kind: String) =
                """{"v":1,"at":"$RECORDED_AT","id":"$id","thread":"$id","from":"$from","to":"$to","kind":"$kind","stage":"$TOY_STAGE","subject":"$kind","created":"$RECORDED_AT","by":"seat:$from"}"""
            fun delivered(id: String, to: String, observed: String = "") =
                """{"v":1,"at":"$RECORDED_AT","event":"delivered","id":"$id","to":["$to"],"class":"A"$observed}"""
            val head = ""","observed":{"pr.head":"$TOY_HEAD"}"""
            val lines = listOfNotNull(
                """{"v":1,"at":"$RECORDED_AT","event":"transition","machine":"stage","subject":"$TOY_STAGE","from":"planned","to":"building","cause":"fixture"}""",
                """{"v":1,"at":"$RECORDED_AT","event":"op","op":"worktree.create","phase":"requested","cause":"$APPROVAL","with":{"stage":"$TOY_STAGE"}}""".takeIf { made != WorktreeMade.NONE },
                when (made) {
                    WorktreeMade.OK -> """{"v":1,"at":"$RECORDED_AT","event":"op","op":"worktree.create","phase":"ok","cause":"$APPROVAL","result":{"repository":"kp-toy","branch":"$TOY_BRANCH","worktree":"$worktree"}}"""
                    WorktreeMade.FAILED -> """{"v":1,"at":"$RECORDED_AT","event":"op","op":"worktree.create","phase":"failed","cause":"$APPROVAL","reason":"a branch $TOY_BRANCH is there already: it is not taken"}"""
                    WorktreeMade.NONE -> null
                },
                message(REVIEW_REQUEST, "coder-1", "teamlead", "review-request"), delivered(REVIEW_REQUEST, "teamlead", head),
                message(MERGE, "teamlead", "daemon", "merge"), delivered(MERGE, "daemon", head),
                message(VERDICT, "reviewer-1", "coder-1", "verdict"), delivered(VERDICT, "coder-1"),
            )
            LogWriter.open(project, OfficeId("panda")).use { log ->
                val judgement = log.judgement()
                lines.forEach { judgement.add(Json.parseToJsonElement(it).jsonObject, StageId(TOY_STAGE)) }
                judgement.close(RECORDED_AT)
            }
        }
    }
}

/**
 * [stage]'s worktree made, as the office's performer records it (contracts.md §4.4, §7; Amendment 40): a `worktree.create`
 * requested for it and ended `ok`, naming [repository] — its key in `repos:` — [branch] and [worktree]. What every reader
 * of a stage's repository and branch takes them from (S3.11).
 */
fun placed(project: Path, stage: String, repository: String, branch: String, worktree: String = "/nowhere/${stage.lowercase().replace('/', '-')}") {
    LogWriter.open(project, OfficeId("panda")).use { log ->
        val judgement = log.judgement()
        listOf(
            """{"v":1,"at":"$RECORDED_AT","event":"op","op":"worktree.create","phase":"requested","cause":"m-panda-900000","with":{"stage":"$stage"}}""",
            """{"v":1,"at":"$RECORDED_AT","event":"op","op":"worktree.create","phase":"ok","cause":"m-panda-900000","result":{"repository":"$repository","branch":"$branch","worktree":"$worktree"}}""",
        ).forEach { judgement.add(Json.parseToJsonElement(it).jsonObject, StageId(stage)) }
        judgement.close(RECORDED_AT)
    }
}

/** Whether [path] — a worktree of a main checkout — is still listed by it. */
fun RecordedOffice.listsWorktree(path: Path): Boolean = gitOutput(toy, "worktree", "list", "--porcelain").lines().any { it == "worktree $path" }

/** The spec's own `git` in [dir], reading no user or system configuration: its output. */
fun gitOutput(dir: Path, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).apply {
        environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null"))
        }
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
    return output.trim()
}

/** Whether a file is at [path]. */
fun exists(path: Path): Boolean = path.isRegularFile() || Files.isDirectory(path)
