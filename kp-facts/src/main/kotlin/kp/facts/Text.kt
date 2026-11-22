package kp.facts

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * An effort's folder in the project checkout (contracts.md §4.4, Amendment 32): `project.efforts` under the checkout.
 * [prefix] is its path relative to the checkout, as evidence names a file in it. A folder the daemon may not read is
 * named as such, never as a file that does not exist (review-025's follow-up, Collite/keporkak#52).
 */
internal class Folder(val dir: Path, val prefix: String, val effort: String) {
    /** The file [name] of this folder as it stands in the working tree; what was missing when it is not there or cannot be read. */
    fun read(name: String): Text {
        val file = dir.resolve(name)
        val regular = try {
            Files.readAttributes(file, BasicFileAttributes::class.java).isRegularFile
        } catch (_: NoSuchFileException) {
            false
        } catch (_: AccessDeniedException) {
            throw unreadable()
        }
        if (!regular) throw Missing("$prefix$name does not exist")
        return try {
            Text(prefix + name, file.readText())
        } catch (_: AccessDeniedException) {
            throw Missing("$prefix$name cannot be read")
        }
    }

    /** The files of this folder — not below it — whose name begins with [start] and ends with [end], sorted. */
    fun files(start: String, end: String): List<String> {
        if (!Files.isDirectory(dir)) throw Missing("${prefix.ifEmpty { "the effort's folder" }} does not exist")
        return try {
            dir.listDirectoryEntries().filter { it.isRegularFile() && it.name.startsWith(start) && it.name.endsWith(end) }.map { it.name }.sorted()
        } catch (_: AccessDeniedException) {
            throw unreadable()
        }
    }

    /** The folder's permission denies the daemon: what was missing, naming the effort and the folder. */
    private fun unreadable() = Missing("the effort $effort's folder ${prefix.ifEmpty { "(the project checkout)" }} cannot be read")

    companion object {
        /** [effort]'s folder in [where]'s project checkout. */
        fun of(where: Checkouts, effort: String): Folder {
            val folder = where.efforts[effort] ?: throw Missing("the office's project.efforts names no folder for the effort $effort")
            val dir = where.project.resolve(folder).normalize()
            val prefix = where.project.normalize().relativize(dir).toString().let { if (it.isEmpty()) "" else "$it/" }
            return Folder(dir, prefix, effort)
        }
    }
}

/** A text file read line by line: [path] relative to the project checkout, and its lines without their line feeds. */
internal class Text(val path: String, content: String) {
    val lines: List<String> = when {
        content.isEmpty() -> emptyList()
        else -> content.removeSuffix("\n").split("\n").map { it.removeSuffix("\r") }
    }

    /** The evidence of the lines [indexes] (from 0): `path#L3,L5-L7`; the path alone for none. */
    fun cite(indexes: Collection<Int>): String = cite(path, indexes)

    fun cite(vararg indexes: Int): String = cite(indexes.toList())

    /** The lines [range] (from 0), cited. */
    fun cite(range: IntRange): String = cite(range.toList())
}

/** `path#L3,L5-L7` for the lines [indexes], counted from 0; the path alone for none. */
internal fun cite(path: String, indexes: Collection<Int>): String {
    val numbers = indexes.map { it + 1 }.toSortedSet()
    if (numbers.isEmpty()) return path
    val runs = ArrayList<String>()
    var first = numbers.first()
    var last = first
    for (number in numbers.drop(1)) {
        if (number == last + 1) {
            last = number
            continue
        }
        runs += if (first == last) "L$first" else "L$first-L$last"
        first = number
        last = number
    }
    runs += if (first == last) "L$first" else "L$first-L$last"
    return "$path#" + runs.joinToString(",")
}
