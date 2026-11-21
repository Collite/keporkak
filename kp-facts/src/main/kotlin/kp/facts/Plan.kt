package kp.facts

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** A table of `plan.md`: its header's cells, and the line it is on (from 0). */
internal class Table(val header: List<String>, val index: Int)

/** A stage's row of the plan (Amendment 32): the row whose first cell begins with the bold id `**S<n.m>` and a space. */
internal class Row(val local: String, val n: String, val index: Int, val cells: List<String>, val table: Table)

/**
 * An effort's `plan.md` as Amendment 32 reads it: its stage rows, and each one's predecessors — the cell under the column
 * headed `after`: stage and phase ids of the same plan, separated by commas, or `—` for none. A phase id stands for every
 * stage of that phase. A row whose id carries another effort's name is not this plan's: it does not begin `**S`.
 */
internal class Plan(val effort: String, val text: Text) {
    val rows: List<Row> = rows(text)

    fun row(local: String): Row {
        val found = rows.filter { it.local == local }
        return when (found.size) {
            0 -> throw Missing("${text.path} has no row for $local")
            1 -> found.single()
            else -> throw Missing("${text.cite(found.map { it.index })}: ${text.path} has ${found.size} rows for $local")
        }
    }

    /** The rows of the phase `P<n>` — a number [held] already, as every row's is. */
    fun phase(n: String): List<Row> = rows.filter { it.n.toInt() == n.toInt() }.ifEmpty { throw Missing("${text.path} has no row of phase P$n") }

    /** The ids in [row]'s cell under `after`; none for `—`. */
    fun after(row: Row): List<String> {
        val column = row.table.header.indexOf(AFTER)
        if (column < 0) throw Missing("${text.cite(row.table.index)}: the table of ${row.local} has no column '$AFTER'")
        val cell = row.cells.getOrNull(column) ?: throw Missing("${text.cite(row.index)}: the row of ${row.local} has no cell under '$AFTER'")
        if (cell == NONE) return emptyList()
        val ids = cell.split(",").map { it.trim() }
        if (ids.any { !ID.matches(it) }) throw Missing("${text.cite(row.index)}: the cell '$AFTER' of ${row.local} holds '$cell', not stage and phase ids")
        ids.forEach { id -> NUMBER.findAll(id).forEach { held(it.value, id, text.cite(row.index)) } }
        return ids
    }

    /** [row]'s predecessors, each a row of this plan. */
    fun predecessors(row: Row): List<Row> = after(row).flatMap { id ->
        if (id.startsWith("P")) {
            rows.filter { it.n.toInt() == id.drop(1).toInt() }.ifEmpty { throw Missing("${text.cite(row.index)}: ${row.local}'s predecessor $id has no stage row") }
        } else {
            val found = rows.filter { it.local == id }
            if (found.isEmpty()) throw Missing("${text.cite(row.index)}: ${row.local}'s predecessor $id has no row")
            listOf(found.singleOrNull() ?: throw Missing("${text.cite(found.map { it.index })}: ${text.path} has ${found.size} rows for $id"))
        }
    }.distinct()

    /** The stage a row is, by its effort's name: `<EFFORT>/P<n>/S<n.m>`. */
    fun id(row: Row): String = "$effort/P${row.n}/${row.local}"

    private companion object {
        const val AFTER = "after"
        const val NONE = "—"
        val ID = Regex("^(S[0-9]+\\.[0-9]+|P[0-9]+)$")
        val NUMBER = Regex("[0-9]+")
        val STAGE_ROW = Regex("^\\*\\*S([0-9]+)\\.([0-9]+) ")
        val SEPARATOR = Regex("^:?-+:?$")

        fun rows(text: Text): List<Row> {
            val rows = ArrayList<Row>()
            var table: Table? = null
            text.lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                val current = table
                when {
                    !trimmed.startsWith("|") -> table = null
                    current == null -> table = Table(cells(trimmed), index)
                    index == current.index + 1 && cells(trimmed).all { SEPARATOR.matches(it) } -> Unit
                    else -> cells(trimmed).let { cells ->
                        STAGE_ROW.find(cells.first())?.destructured?.let { (n, m) ->
                            listOf(n, m).forEach { held(it, "S$n.$m", text.cite(index)) }
                            rows += Row("S$n.$m", n, index, cells, current)
                        }
                    }
                }
            }
            return rows
        }

        /** A table line's cells, trimmed; `\|` is a bar inside a cell. */
        fun cells(line: String): List<String> {
            var body = line.removePrefix("|")
            if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
            return body.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
        }
    }
}

/** A value read with its evidence, inside one fact. */
internal data class Judged(val value: Boolean, val evidence: String)

/**
 * Exited, past and the wave, for the plans one observation reads (Amendment 32). A stage is exited when the log's state
 * for it is `exited`; one the log holds no record of — carried by hand — when its effort's `tasks-overview.md` ticks it:
 * a line that begins, after its indent, `- [x] S<n.m>` or `- [x] **S<n.m>**`. The log, where it knows a stage, is the
 * only word.
 */
internal class Waves(private val reading: Reading) {
    private val plans = HashMap<String, Plan>()
    private val overviews = HashMap<String, Text>()
    private val exits = HashMap<String, Judged>()

    fun plan(effort: String): Plan = plans.getOrPut(effort) { Plan(effort, Folder.of(reading.where, effort).read("plan.md")) }

    fun exited(plan: Plan, row: Row): Judged = exits.getOrPut(plan.id(row)) {
        val stage = plan.id(row)
        reading.log.state(stage)?.let { return@getOrPut Judged(it.state == EXITED, "log: $stage ${it.state}") }
        val overview = overviews.getOrPut(plan.effort) { Folder.of(reading.where, plan.effort).read("tasks-overview.md") }
        val local = Regex.escape(row.local)
        val tick = Regex("^- \\[x\\] (\\*\\*$local\\*\\*|$local(?![0-9]))")
        val at = overview.lines.indexOfFirst { tick.containsMatchIn(it.trimStart()) }
        if (at >= 0) Judged(true, "${overview.cite(at)} ticks ${row.local}") else Judged(false, "${overview.path} does not tick ${row.local}")
    }

    /** Past: exited, or `closed` in the log. */
    fun past(plan: Plan, row: Row): Judged {
        val stage = plan.id(row)
        if (reading.log.state(stage)?.state == CLOSED) return Judged(true, "log: $stage $CLOSED")
        return exited(plan, row)
    }

    /** Not past, and every predecessor has exited. */
    fun current(plan: Plan, row: Row): Boolean = !past(plan, row).value && plan.predecessors(row).all { exited(plan, it).value }

    /** [row]'s wave — past, current, next or later — and what it was read from. */
    fun wave(plan: Plan, row: Row): Pair<String, String> {
        val past = past(plan, row)
        if (past.value) return PAST to "${plan.text.cite(row.index)}; ${past.evidence}"
        val predecessors = plan.predecessors(row)
        val evidence = "${plan.text.cite(row.index)} (after ${predecessors.joinToString(", ") { it.local }.ifEmpty { "—" }}); ${past.evidence}" +
            predecessors.joinToString("") { "; ${it.local}: ${past(plan, it).evidence}" }
        return when {
            predecessors.all { exited(plan, it).value } -> CURRENT
            predecessors.all { past(plan, it).value || current(plan, it) } -> NEXT
            else -> LATER
        } to evidence
    }

    private companion object {
        const val EXITED = "exited"
        const val CLOSED = "closed"
        const val PAST = "past"
        const val CURRENT = "current"
        const val NEXT = "next"
        const val LATER = "later"
    }
}

internal fun Reading.waveOf(): Observation {
    val stage = stage()
    val plan = waves.plan(stage.effort)
    val (wave, evidence) = waves.wave(plan, plan.row(stage.local))
    return Observation.Value(wave, evidence)
}

internal fun Reading.predecessorsExited(): Observation {
    val stage = stage()
    val plan = waves.plan(stage.effort)
    val row = plan.row(stage.local)
    val exits = plan.predecessors(row).map { it to waves.exited(plan, it) }
    return Observation.Value(exits.all { it.second.value },
        "${plan.text.cite(row.index)} (after ${exits.joinToString(", ") { it.first.local }.ifEmpty { "—" }})" + exits.joinToString("") { "; ${it.first.local}: ${it.second.evidence}" })
}

internal fun Reading.inCurrentWave(): Observation {
    val named = asked.texts("stage").ifEmpty { throw Missing("the parameter 'stage' names no stage") }
    val waves = named.map { text ->
        val stage = StageId.of(text)
        val plan = waves.plan(stage.effort)
        text to waves.wave(plan, plan.row(stage.local))
    }
    return Observation.Value(waves.all { it.second.first == "current" }, waves.joinToString("; ") { (stage, wave) -> "$stage ${wave.first}: ${wave.second}" })
}

internal fun Reading.lastStageExited(): Observation {
    val phase = phase()
    val plan = waves.plan(phase.effort)
    val exits = plan.phase(phase.n).map { it to waves.exited(plan, it) }
    return Observation.Value(exits.all { it.second.value }, exits.joinToString("; ") { "${it.first.local}: ${it.second.evidence}" })
}

/** The list files of [stage] in its effort's folder: `tasks-p<n>-s<n.m>-*.md`, sorted, each by its path in the checkout. */
internal fun Reading.listFiles(stage: StageId): Pair<String, List<String>> {
    val folder = Folder.of(where, stage.effort)
    val start = "tasks-p${stage.n}-s${stage.n}.${stage.m}-"
    return "${folder.prefix}$start*.md" to folder.files(start, ".md").map { folder.prefix + it }
}

internal fun Reading.stageHasList(): Observation {
    val stage = stage()
    val (pattern, files) = listFiles(stage)
    return when (files.size) {
        0 -> Observation.Value(false, "no file $pattern")
        1 -> Observation.Value(true, files.single())
        else -> throw Missing("${if (files.size == 2) "two" else files.size.toString()} list files for ${stage.local}: ${files.joinToString(", ")}")
    }
}

/**
 * `plan.chart_fresh`: the first fenced `mermaid` block under the heading that begins `## 3` in the working tree, against
 * the one in the newest commit not later than the exit of the phase's last stage to exit, as the log dates it.
 */
internal fun Reading.chartFresh(): Observation {
    val phase = phase()
    val plan = waves.plan(phase.effort)
    val exits = plan.phase(phase.n).mapNotNull { row -> plan.id(row).let { stage -> log.exitedAt(stage)?.let { stage to it } } }
    if (exits.isEmpty()) throw Missing("the log dates no exit of a stage of ${phase.id}")
    val (last, at) = exits.maxBy { (stage, at) -> seconds(at, stage) }
    val now = chart(plan.text) ?: throw Missing("${plan.text.path} has no fenced mermaid block under a heading that begins '## 3'")
    val project = git.root(where.project)
    val bound = seconds(at, last)
    val commit = git.commits(project).filter { it.time <= bound }.maxByOrNull { it.time }
        ?: throw Missing("no commit of the project checkout is dated at or before $last's exit, $at")
    val shown = git.run(project, "show", "${commit.sha}:./${plan.text.path}", ok = setOf(0, 128))
    if (shown.code != 0) throw Missing("${plan.text.path} is not in commit ${commit.short}")
    val committed = Text(plan.text.path, shown.output)
    val then = chart(committed) ?: throw Missing("${plan.text.path} in commit ${commit.short} has no fenced mermaid block under a heading that begins '## 3'")
    val differs = plan.text.lines.slice(now) != committed.lines.slice(then)
    return Observation.Value(differs, "${plan.text.cite(now)} against commit ${commit.short} (${commit.at}), the newest not later than $last's exit at $at")
}

/** The lines of the first fenced `mermaid` block under the heading that begins `## 3`, its fences included; null when none. */
private fun chart(text: Text): IntRange? {
    val lines = text.lines
    val heading = lines.indexOfFirst { it.startsWith("## 3") }
    if (heading < 0) return null
    for (index in heading + 1 until lines.size) {
        if (lines[index].startsWith("## ") || lines[index].startsWith("# ")) return null
        if (lines[index].trim().startsWith("```mermaid")) {
            val end = (index + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("```") } ?: return null
            return index..end
        }
    }
    return null
}

/** A time the log writes, in seconds since the epoch. */
private fun seconds(at: String, stage: String): Long = try {
    OffsetDateTime.parse(at).toEpochSecond()
} catch (_: DateTimeParseException) {
    throw Missing("the log dates the exit of $stage '$at', which is no time")
}
