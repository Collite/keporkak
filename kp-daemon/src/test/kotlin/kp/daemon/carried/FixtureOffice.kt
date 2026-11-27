@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kp.daemon.carried

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kp.daemon.THE_APPS
import kp.daemon.checkout
import kp.ops.FakeApps
import kp.ops.Remote
import kp.ops.git
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The office of S3.5's fixture: the name `pool/offices.yaml` gives it in the copy of the rules the fixture reads. */
const val FIXTURE_OFFICE = "panda"
/** The stage the walk carries, and its list's branch, as the toy's list names it. */
const val CARRIED = "TOY/P0/S0.2"
const val CARRIED_BRANCH = "feat/toy-kp-toy-p0-s0.2-smoke"

/**
 * S3.5's fixture office (T1's seam): an office on disk as dsk is, with nothing of it on GitHub and no network but a local
 * server. Three bare remotes the spec builds with the real `git` — the project's, keporkak's and the toy's — and the
 * office's checkouts cloned from them: the project checkout, holding the toy's effort folder as the project holds it
 * (`src/test/resources/carried/toy/`, copies of `project/toy/`: the plan, the overview, S0.2's list as recut, STATUS), and
 * the two main checkouts. The office's four roots, of its own; its secrets the Apps' keys and a Claude token file that
 * holds none. The rules are the checkout's but `pool/offices.yaml`, which names all of these, a free port for the seat API,
 * and the watch at [watch].
 *
 * GitHub is one local server ([github]): the Apps' endpoints ([FakeApps]) and `Collite/kp-toy` as [ToyGitHub] keeps it —
 * its pull requests, reviews and merges made on the toy's bare remote, its check runs green on every commit that remote
 * holds, its default branch held by two rulesets.
 */
class FixtureOffice(watch: String = "30s") : AutoCloseable {
    val folder: Path = Files.createTempDirectory(checkout.resolve("build").createDirectories(), "carried-")
    val secrets: Path = folder.resolve("secrets").createDirectories().also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
    private val server = FakeApps.local()
    val apps = FakeApps(server, secrets.resolve("apps")).also { apps -> THE_APPS.forEach(apps::add) }
    val toyRemote = Remote("kp-toy.git", mapOf(
        "README.md" to "# kp-toy\n\nThe toy library of Keporkak's fixture office.\n",
        "settings.gradle.kts" to "rootProject.name = \"kp-toy\"\ninclude(\"lib\")\n",
        "lib/src/main/kotlin/LibraryInfo.kt" to "object LibraryInfo\n",
    ))
    val toy: Path = toyRemote.clone("kp-toy")
    val keporkakRemote = Remote("keporkak.git", mapOf("README.md" to "keporkak\n"))
    val keporkak: Path = keporkakRemote.clone("keporkak")
    val projectRemote = Remote("project.git", projectFiles())
    val project: Path = projectRemote.clone("project")
    val github = ToyGitHub(server, "Collite/kp-toy", toyRemote.dir, apps).also { server.start() }
    val worktrees: Path = folder.resolve("worktrees").createDirectories()
    val scratch: Path = folder.resolve("scratch").createDirectories()
    val seats: Path = folder.resolve("seats").createDirectories()
    val listen = "127.0.0.1:${ServerSocket(0).use { it.localPort }}"
    val root: Path = rules(watch)
    /** A Unix socket's path has at most 107 bytes: the daemon's is under the system's temporary folder. */
    val socket: Path = Files.createTempDirectory("kp-carried-").resolve("kp.sock")
    /** A `tmux` that does nothing: the panes are not this fixture's to show. */
    val tmux: Path = folder.resolve("tmux").apply { writeText("#!/bin/sh\nexit 0\n"); toFile().setExecutable(true) }

    init {
        Files.writeString(secrets.resolve("claude-token"), "no-token-the-fake-agent-needs-none\n")
    }

    /** The project checkout's list of the carried stage. */
    val list: Path get() = project.resolve("toy/tasks-p0-s0.2-smoke.md")

    /**
     * A copy of the checkout as the office's keporkak — every folder the daemon reads, linked — but `pool/`, copied, its
     * `offices.yaml` naming this fixture's checkouts, roots, secrets, listener and watch for [FIXTURE_OFFICE].
     */
    private fun rules(watch: String): Path {
        val root = folder.resolve("keporkak-root").createDirectories()
        listOf("agents", "renders", "skills", "hooks", "docs", "scripts", "seat", "office", "rules", "robots", "schemas").forEach { name ->
            root.resolve(name).createSymbolicLinkPointingTo(checkout.resolve(name))
        }
        val pool = root.resolve("pool").createDirectories()
        checkout.resolve("pool").listDirectoryEntries("*.yaml").forEach { Files.copy(it, pool.resolve(it.name)) }
        val offices = pool.resolve("offices.yaml")
        val entry = Regex("(?ms)^  $FIXTURE_OFFICE:\n.*?(?=^  [a-z]+:\n|^[a-z]|\\z)")
        val text = offices.readText()
        val panda = entry.find(text)?.value ?: error("pool/offices.yaml has no entry $FIXTURE_OFFICE")
        val fixture = panda
            .replace(Regex("(?m)^      worktrees: .*$"), "      worktrees: \"$worktrees\"")
            .replace(Regex("(?m)^      scratch: .*$"), "      scratch: \"$scratch\"")
            .replace(Regex("(?m)^      seats: .*$"), "      seats: \"$seats\"")
            .replace(Regex("(?m)^      secrets: .*$"), "      secrets: \"$secrets\"")
            .replace(Regex("(?m)^    project: .*$"), "    project: {checkout: \"$project\", efforts: {KP: \".\", TOY: toy}}")
            .replace(Regex("(?m)^    repos: .*$"), "    repos: {keporkak: \"$keporkak\", kp-toy: \"$toy\"}")
            .replace(Regex("(?m)^    listen: .*$"), "    listen: {mcp: \"$listen\", board: \"127.0.0.1:${ServerSocket(0).use { it.localPort }}\"}")
            .replace(Regex("(?m)^    watch: .*$"), "    watch: {every: $watch}")
        offices.writeText(text.replace(panda, fixture))
        return root
    }

    /** The project checkout as the project holds it for the toy: the toy's effort folder, and KP's files at its root. */
    private fun projectFiles(): Map<String, String> {
        val toy = Path.of(checkNotNull(javaClass.getResource("/carried/toy/plan.md")).toURI()).parent
        return toy.listDirectoryEntries("*.md").associate { "toy/${it.name}" to it.readText() } + mapOf(
            ".gitignore" to "mail/.writer.lock\n*.log\n",
            "plan.md" to "## 2 · Stages\n\n| stage | after | goal |\n|---|---|---|\n| **S3.5 The first carried stage** | — | the toy's S0.2 carried |\n",
            "tasks-overview.md" to "- [ ] **P3 · Eyes and hands**\n  - [ ] **S3.5** The first carried stage\n",
            "STATUS.md" to "---\neffort: KP — Keporkak\nrepo_home: project\ncode_home: [keporkak]\nstate: \"in flight\"\nphase: \"P3\"\nnext: \"nobody\"\n" +
                "blocked_on: []\ngates: []\nupdated: 2026-11-27\nstream: dev\nlane: coding\n---\n\n- **2026-11-27 — S3.4 merged.** #76\n",
        )
    }

    /** The project checkout's `git status`, as `--porcelain` gives it. */
    fun status(checkout: Path): String = git(checkout, "status", "--porcelain=v1", "--untracked-files=all")

    override fun close() {
        server.stop(0)
    }
}

/**
 * `Collite/kp-toy` on the fixture's local GitHub (S3.5 T1): what the walk's way asks of GitHub, kept as GitHub keeps it,
 * on the toy's bare remote [remote]. A pull request is opened from a branch the remote holds — its author the App whose
 * token opened it — and its head is that branch's commit as the remote holds it now; a review is the App's that sent it,
 * on the commit it names; a merge, on the head it names and by `kp-merger` alone, is a merge commit on the remote's
 * `master` made as `kp-merger[bot]`. Every commit the remote holds has one check run, completed, success: the fixture's CI.
 * The default branch is held by two rulesets — the coder's App to `update`, `kp-merger` to green checks — neither of
 * which an App may bypass. Every request is kept ([requests]).
 */
class ToyGitHub(server: com.sun.net.httpserver.HttpServer, private val repository: String, private val remote: Path, private val apps: FakeApps) {
    private class Pull(val number: Int, val author: String, val head: String, val base: String, val title: String, val body: String, val created: Instant) {
        @Volatile var state = "open"
        @Volatile var mergedAt: Instant? = null
        @Volatile var mergeCommit: String? = null
        @Volatile var mergedBy: String? = null
    }

    private class Review(val pull: Int, val login: String, val commit: String, val state: String, val at: Instant)

    data class Seen(val method: String, val target: String, val app: String?)

    private val pulls = CopyOnWriteArrayList<Pull>()
    private val reviews = CopyOnWriteArrayList<Review>()
    val requests = CopyOnWriteArrayList<Seen>()
    private val work: Path = Files.createTempDirectory(remote.parent, "github-")

    init {
        server.createContext("/repos/$repository") { exchange -> exchange.use { answer(it) } }
    }

    /** The merge commit of pull request [number] and the App that merged it, once merged. */
    fun merged(number: Int = 1): Pair<String, String>? = pulls.firstOrNull { it.number == number }?.let { pull -> pull.mergeCommit?.let { it to pull.mergedBy!! } }

    /** The pull requests opened, each `#<number> by <login>, <state>`. */
    fun pulls(): List<String> = pulls.map { "#${it.number} by ${it.author}, ${if (it.mergedAt != null) "merged" else it.state}" }

    /** The reviews submitted, each `<login> <state> on <commit>`. */
    fun reviews(): List<String> = reviews.map { "${it.login} ${it.state} on ${it.commit}" }

    private fun answer(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path.removePrefix("/repos/$repository")
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val app = appOf(exchange.requestHeaders.getFirst("Authorization"))
        requests += Seen(method, exchange.requestURI.toString(), app?.identity)
        val (status, json) = when {
            app == null -> 401 to message("Bad credentials")
            method == "GET" && path.isEmpty() -> 200 to buildJsonObject { put("full_name", repository); put("default_branch", MASTER) }
            method == "GET" && path == "/pulls" -> 200 to JsonArray(pulls.filter { pull ->
                (query["head"]?.let { it.substringAfter(':') == pull.head } ?: true) && (query["state"].let { it == null || it == "all" || it == pull.stateNow() })
            }.map(::json))
            method == "POST" && path == "/pulls" -> open(app, body)
            method == "GET" && PULL.matches(path) -> pull(path)?.let { 200 to json(it) } ?: (404 to message("Not Found"))
            method == "GET" && path.matches(Regex("/pulls/[0-9]+/reviews")) -> 200 to JsonArray(reviews.filter { it.pull == number(path) }.mapIndexed { at, review ->
                buildJsonObject {
                    put("id", at + 1); put("user", buildJsonObject { put("login", review.login) }); put("commit_id", review.commit)
                    put("state", review.state); put("submitted_at", review.at.toString())
                }
            })
            method == "POST" && path.matches(Regex("/pulls/[0-9]+/reviews")) -> review(app, number(path), body)
            method == "PUT" && path.matches(Regex("/pulls/[0-9]+/merge")) -> merge(app, number(path), body)
            method == "GET" && path.matches(Regex("/commits/[0-9a-f]{40}/check-runs")) -> {
                val sha = path.split('/')[2]
                val runs = if (holds(sha)) listOf(buildJsonObject { put("name", "check"); put("status", "completed"); put("conclusion", "success") }) else emptyList()
                200 to buildJsonObject { put("total_count", runs.size); put("check_runs", JsonArray(runs)) }
            }
            method == "GET" && path.matches(Regex("/issues/[0-9]+/comments")) -> 200 to JsonArray(emptyList())
            method == "GET" && path == "/rules/branches/$MASTER" -> 200 to buildJsonArray {
                add(buildJsonObject { put("type", "update"); put("ruleset_id", 1) })
                add(buildJsonObject { put("type", "required_status_checks"); put("ruleset_id", 2) })
            }
            method == "GET" && path.matches(Regex("/rulesets/[12]")) -> 200 to buildJsonObject {
                put("id", path.substringAfterLast('/').toInt()); put("name", if (path.endsWith("1")) "master" else "master: green checks"); put("current_user_can_bypass", "never")
            }
            else -> 404 to message("Not Found")
        }
        val bytes = json.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun appOf(authorization: String?): kp.ops.FakeApp? {
        val token = authorization?.substringAfter(' ')?.trim() ?: return null
        return apps.issued.lastOrNull { it.token == token }?.app
    }

    private fun Pull.stateNow(): String = if (mergedAt != null) "closed" else state

    private fun pull(path: String): Pull? = pulls.firstOrNull { it.number == number(path) }

    private fun number(path: String): Int = path.split('/')[2].toInt()

    private fun json(pull: Pull): JsonObject = buildJsonObject {
        put("number", pull.number)
        put("html_url", "https://github.com/$repository/pull/${pull.number}")
        put("state", pull.stateNow())
        put("draft", false)
        put("title", pull.title)
        put("body", pull.body)
        put("user", buildJsonObject { put("login", pull.author) })
        put("created_at", pull.created.toString())
        put("merged_at", pull.mergedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("merged", pull.mergedAt != null)
        put("merge_commit_sha", pull.mergeCommit?.let(::JsonPrimitive) ?: JsonNull)
        put("head", buildJsonObject { put("ref", pull.head); put("sha", head(pull)) })
        put("base", buildJsonObject { put("ref", pull.base) })
    }

    /** The head of [pull]: its branch's commit as the remote holds it now — or, merged, as it was merged. */
    private fun head(pull: Pull): String = runCatching { git(remote, "rev-parse", "refs/heads/${pull.head}") }.getOrDefault("0".repeat(40))

    private fun holds(sha: String): Boolean = runCatching { git(remote, "cat-file", "-e", "$sha^{commit}") }.isSuccess

    @Synchronized private fun open(app: kp.ops.FakeApp, body: String): Pair<Int, JsonElement> {
        val asked = Json.parseToJsonElement(body) as JsonObject
        val head = asked.text("head")?.substringAfter(':') ?: return 422 to message("head is missing")
        if (runCatching { git(remote, "rev-parse", "--verify", "refs/heads/$head") }.isFailure) return 422 to message("Validation Failed: head $head does not exist")
        if (pulls.any { it.head == head && it.stateNow() == "open" }) return 422 to message("A pull request already exists for Collite:$head.")
        val pull = Pull(pulls.size + 1, "${app.slug}[bot]", head, asked.text("base") ?: MASTER, asked.text("title") ?: head, asked.text("body").orEmpty(), Instant.now())
        pulls += pull
        return 201 to json(pull)
    }

    @Synchronized private fun review(app: kp.ops.FakeApp, number: Int, body: String): Pair<Int, JsonElement> {
        val pull = pulls.firstOrNull { it.number == number } ?: return 404 to message("Not Found")
        val asked = Json.parseToJsonElement(body) as JsonObject
        val state = when (asked.text("event")) {
            "APPROVE" -> "APPROVED"
            "REQUEST_CHANGES" -> "CHANGES_REQUESTED"
            else -> "COMMENTED"
        }
        if ("${app.slug}[bot]" == pull.author && state == "APPROVED") return 422 to message("Can not approve your own pull request")
        reviews += Review(number, "${app.slug}[bot]", asked.text("commit_id") ?: head(pull), state, Instant.now())
        return 200 to buildJsonObject { put("id", reviews.size); put("state", state) }
    }

    @Synchronized private fun merge(app: kp.ops.FakeApp, number: Int, body: String): Pair<Int, JsonElement> {
        val pull = pulls.firstOrNull { it.number == number } ?: return 404 to message("Not Found")
        if (pull.stateNow() != "open") return 405 to message("Pull Request is not mergeable")
        if (app.identity != "kp-merger") return 405 to message("Repository rule violations found\n\nChanges must be made through a pull request.")
        val asked = Json.parseToJsonElement(body) as JsonObject
        val head = head(pull)
        if (asked.text("sha") != head) return 409 to message("Head branch was modified. Review and try the merge again.")
        val clone = Files.createTempDirectory(work, "merge-")
        git(clone, "clone", "--quiet", remote.toString(), "toy")
        val toy = clone.resolve("toy")
        val bot = "${app.slug}[bot]"
        // GitHub makes the merge commit as the App that merged: its author and committer here, as on GitHub.
        asBot(toy, bot, "${app.id}+$bot@users.noreply.github.com", "merge", "--no-ff", "--quiet", "-m", "Merge pull request #$number from Collite/${pull.head}\n\n${pull.title}", "origin/${pull.head}")
        git(toy, "push", "--quiet", "origin", "$MASTER:$MASTER")
        val sha = git(toy, "rev-parse", "HEAD")
        pull.mergedAt = Instant.now()
        pull.mergeCommit = sha
        pull.mergedBy = bot
        return 200 to buildJsonObject { put("sha", sha); put("merged", true); put("message", "Pull Request successfully merged") }
    }

    private fun message(text: String) = buildJsonObject { put("message", text) }

    /** `git` in [dir] as [name] <[email]>, author and committer, reading no user or system configuration. */
    private fun asBot(dir: Path, name: String, email: String, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).apply {
            environment().keys.removeAll { it.startsWith("GIT_") }
            environment().putAll(mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_AUTHOR_NAME" to name, "GIT_AUTHOR_EMAIL" to email,
                "GIT_COMMITTER_NAME" to name, "GIT_COMMITTER_EMAIL" to email))
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} in $dir failed:\n$output" }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val MASTER = "master"
        val PULL = Regex("/pulls/[0-9]+")
    }
}
