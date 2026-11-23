package kp.facts

/**
 * A stage's list, read line by line as Amendment 32 says: a section is the text from a line that begins `## ` to the
 * next, named by the heading's text up to the first ` (`; the header's three lines on the line that begins `rolls:`; the
 * tasks, the DONE items and the Found lines of their sections.
 */
internal class StageList(val text: Text) {
    /** By its name, each section's lines (from 0), its heading first: every section of that name, in their order. */
    private val sections: Map<String, List<IntRange>> = buildMap<String, MutableList<IntRange>> {
        val headings = text.lines.indices.filter { text.lines[it].startsWith("## ") }
        headings.forEachIndexed { at, index ->
            val name = text.lines[index].removePrefix("## ").substringBefore(" (").trim()
            val end = headings.getOrNull(at + 1)?.minus(1) ?: text.lines.lastIndex
            getOrPut(name) { ArrayList() } += index..end
        }
    }

    /** The one section named [name]: none is missing; two or more of that name are [Ambiguous], each heading cited. */
    fun section(name: String): IntRange {
        val named = sections[name] ?: throw Missing("${text.path} has no section '$name'")
        return named.singleOrNull() ?: throw Ambiguous("${text.cite(named.map { it.first })}: ${times(named.size)} sections '$name'")
    }

    /** The lines of [name]'s section after its heading, each with its index. */
    private fun body(name: String): List<IndexedValue<String>> = section(name).drop(1).map { IndexedValue(it, text.lines[it]) }

    /**
     * The value of [label] on the one line that begins `rolls:` — up to the next ` · ` or the line's end, trimmed; null
     * when it has none. Two or more such lines are [Ambiguous], each cited.
     */
    fun header(label: String): Pair<String?, Int> {
        val lines = text.lines.indices.filter { text.lines[it].startsWith("$ROLLS:") }
        if (lines.isEmpty()) throw Missing("${text.path} has no line that begins '$ROLLS:'")
        val index = lines.singleOrNull() ?: throw Ambiguous("${text.cite(lines)}: ${times(lines.size)} lines that begin '$ROLLS:'")
        val segment = text.lines[index].split(" · ").map { it.trim() }.firstOrNull { it.startsWith("$label:") }
            ?: throw Missing("${text.cite(index)}: no '$label:'")
        return segment.removePrefix("$label:").trim().ifEmpty { null } to index
    }

    /** The ids `SC-<n>` the `Scope fence` section names, in their order by number. */
    fun stopConditions(): List<String> = section(FENCE).flatMap { index ->
        SC.findAll(text.lines[index]).map { sc -> sc.value to held(sc.value.removePrefix("SC-"), sc.value, text.cite(index)) }
    }.distinct().sortedBy { it.second }.map { it.first }

    /** The tasks of the `Tasks` section: each line that begins `- [ ] T<n>` or `- [x] T<n>`, with the lines after it up to the next. */
    fun tasks(): List<Task> {
        val lines = body(TASKS)
        val starts = lines.filter { TASK.containsMatchIn(it.value) }
        return starts.mapIndexed { at, start ->
            val (tick, number) = TASK.find(start.value)!!.destructured
            held(number, "T$number", text.cite(start.index))
            val next = starts.getOrNull(at + 1)?.index ?: (section(TASKS).last + 1)
            val own = (start.index until next).map { text.lines[it] }
            Task("T$number", start.index, closed = tick == "x" && own.any { DONE_SHA.containsMatchIn(it) })
        }
    }

    /** The DONE items: the lines of the `DONE` section that begin `- `, the n-th being `D<n>`. */
    fun doneItems(): List<Int> = body(DONE).filter { it.value.startsWith("- ") }.map { it.index }

    /** The lines of `Found while doing` that begin `- ` and hold more than the dash. */
    fun found(): List<Int> = body(FOUND).filter { it.value.startsWith("- ") && it.value.substring(2).isNotBlank() }.map { it.index }

    /** A task: its id `T<n>`, its line, and whether it is closed — ticked, and one of its lines holds `done <sha>`. */
    data class Task(val id: String, val index: Int, val closed: Boolean)

    companion object {
        const val ROLLS = "rolls"
        const val FENCE = "Scope fence"
        const val TASKS = "Tasks"
        const val DONE = "DONE"
        const val FOUND = "Found while doing"
        val SC = Regex("\\bSC-[0-9]+\\b")
        val TASK = Regex("^- \\[([ x])\\] T([0-9]+)(?![0-9])")
        val DONE_SHA = Regex("(?<![A-Za-z0-9])done [0-9a-fA-F]{7,40}(?![0-9A-Za-z])")
    }

    /** The list's first line ([Heading]): the one reading of it, the project's facts' and GitHub's (S3.10 T3). */
    fun heading(stage: StageId): Heading = Heading(text.lines.firstOrNull().orEmpty(), text.cite(0), stage)
}

/** "two", or the number [n] in digits. */
private fun times(n: Int): String = if (n == 2) "two" else n.toString()

/** [stage]'s one list; what was missing when it has none, or more than one. */
internal fun Reading.stageList(stage: StageId = stage()): StageList {
    val (pattern, files) = listFiles(stage)
    return when (files.size) {
        0 -> throw Missing("no file $pattern")
        1 -> Folder.of(where, stage.effort).let { folder -> StageList(folder.read(files.single().removePrefix(folder.prefix))) }
        else -> throw Missing("${times(files.size)} list files for ${stage.local}: ${files.joinToString(", ")}")
    }
}

/** `stage.rolls`, `stage.live`, `stage.touches`: the header's value, as written. */
internal fun Reading.headerValue(label: String): Observation {
    val list = stageList()
    val (value, index) = list.header(label)
    return Observation.Value(value ?: throw Missing("${list.text.cite(index)}: '$label:' has no value"), list.text.cite(index))
}

internal fun Reading.fenceComplete(): Observation {
    val list = stageList()
    val labels = try {
        listOf("rolls", "live", "touches").map { label -> label to list.header(label) }
    } catch (missing: Missing) {
        return Observation.Value(false, missing.message)
    }
    val empty = labels.filter { it.second.first == null }.map { it.first }
    if (empty.isNotEmpty()) return Observation.Value(false, "${list.text.cite(labels.first().second.second)}: ${empty.joinToString(", ") { "'$it:'" }} without a value")
    val stops = try {
        list.stopConditions()
    } catch (missing: Missing) {
        return Observation.Value(false, missing.message)
    }
    val fence = list.text.cite(list.section(StageList.FENCE))
    if (stops.isEmpty()) return Observation.Value(false, "$fence names no stop condition")
    return Observation.Value(true, "${list.text.cite(labels.first().second.second)}; $fence: ${stops.joinToString(", ")}")
}

internal fun Reading.stopConditions(): Observation {
    val list = stageList()
    return Observation.Value(list.stopConditions(), list.text.cite(list.section(StageList.FENCE)))
}

internal fun Reading.tasksTotal(): Observation {
    val list = stageList()
    val tasks = list.tasks()
    return Observation.Value(tasks.size, if (tasks.isEmpty()) list.text.cite(list.section(StageList.TASKS)) else list.text.cite(tasks.map { it.index }))
}

internal fun Reading.tasksOpen(): Observation {
    val list = stageList()
    val open = list.tasks().filterNot { it.closed }
    return Observation.Value(open.size, if (open.isEmpty()) list.text.cite(list.section(StageList.TASKS)) else list.text.cite(open.map { it.index }))
}

internal fun Reading.taskExists(): Observation {
    val list = stageList()
    val id = asked.text("id")
    if (!Regex("^T[0-9]+$").matches(id)) return Observation.Value(false, "'$id' is no task id T<n>")
    held(id.drop(1), id, "the parameter 'id'")
    val task = list.tasks().firstOrNull { it.id == id }
    return if (task != null) Observation.Value(true, list.text.cite(task.index)) else Observation.Value(false, "${list.text.cite(list.section(StageList.TASKS))} holds no task $id")
}

internal fun Reading.doneItemExists(): Observation {
    val list = stageList()
    val id = asked.text("id")
    val number = Regex("^D([0-9]+)$").matchEntire(id)?.groupValues?.get(1)?.let { held(it, id, "the parameter 'id'") }
        ?: return Observation.Value(false, "'$id' is no DONE item id D<n>")
    val items = list.doneItems()
    val item = items.getOrNull(number - 1)
    return if (number >= 1 && item != null) Observation.Value(true, list.text.cite(item))
    else Observation.Value(false, "${list.text.cite(list.section(StageList.DONE))} has ${items.size} DONE items")
}

internal fun Reading.foundUntriaged(): Observation {
    val list = stageList()
    val found = list.found()
    return Observation.Value(found.size, if (found.isEmpty()) list.text.cite(list.section(StageList.FOUND)) else list.text.cite(found))
}

internal fun Reading.listLines(): Observation {
    val list = stageList()
    return Observation.Value(list.text.lines.size, list.text.cite(list.text.lines.indices))
}

/** `stage.cut_at`: the committer time of the oldest commit that holds a file at the list's path; none when it is in no commit. */
internal fun Reading.cutAt(): Observation {
    val list = stageList()
    val project = git.root(where.project)
    val oldest = git.commits(project, "--diff-filter=A", "--", list.text.path).minByOrNull { it.time } ?: throw Missing("${list.text.path} is in no commit")
    return Observation.Value(oldest.at, "project: commit ${oldest.short}")
}

/**
 * A stage list's first line, as Amendment 32 reads it: the branch it names — `(PR: <branch>)` — and the repository of its
 * title `# <EFFORT>-<repo>-P<n>`; each is what was missing when the line names none. [line] cites it. `git.branch_exists`
 * and GitHub's facts read it here, and nowhere else (S3.10 T3).
 */
internal class Heading(private val first: String, val line: String, private val stage: StageId) {
    fun branch(): String = Regex("\\(PR: ([^)\\s]+)\\)").find(first)?.groupValues?.get(1) ?: throw Missing("$line names no branch '(PR: <branch>)'")

    fun repo(): String = Regex("^# ${Regex.escape(stage.effort)}-(\\S+)-P${stage.n}(?=\\s|$)").find(first)?.groupValues?.get(1)
        ?: throw Missing("$line names no repository '# ${stage.effort}-<repo>-P${stage.n}'")
}
