package kp.daemon

import kp.mailroom.RuleTexts
import kp.rules.Loaded
import kp.rules.RuleSet
import kp.rules.Rules
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The rule set's files in a keporkak checkout, by their path in it (architecture.md §2: reading files is the daemon's):
 * the four rules files, every robot, the three pool files and every persona. [lastRead] is what the last [read] gave —
 * what the mailroom put in force when its start or a reload accepted them.
 */
class RuleFiles(private val root: Path) : RuleTexts {
    @Volatile var lastRead: Map<String, String> = emptyMap()
        private set

    override fun read(): Map<String, String> {
        val rules = RULES.map { root.resolve("rules/$it.yaml") }
        val robots = root.resolve("robots").children { it.name.endsWith(".yaml") }
        val pool = POOL.map { root.resolve("pool/$it.yaml") }
        val personas = root.resolve("agents").children { it.isDirectory() }.map { it.resolve("AGENT.md") }.filter { it.isRegularFile() }
        return (rules + robots + pool + personas).associate { root.relativize(it).toString() to it.readText() }.also { lastRead = it }
    }

    private fun Path.children(keep: (Path) -> Boolean): List<Path> = if (!isDirectory()) emptyList() else listDirectoryEntries().filter(keep).sortedBy { it.name }

    private companion object {
        val RULES = listOf("stage-lifecycle", "message-kinds", "engagement", "vocabulary")
        val POOL = listOf("roster", "offices", "budgets")
    }
}

/** A rule set in force, with its texts and its hash. */
class InForceRules private constructor(val texts: Map<String, String>, val ruleSet: RuleSet, val hash: String) {
    companion object {
        /** The texts through `kp-rules`: the set, or null with its problems. */
        fun of(texts: Map<String, String>): Pair<InForceRules?, Loaded> = when (val loaded = Rules.load(texts)) {
            is Loaded.Valid -> InForceRules(texts, loaded.ruleSet, rulesHash(texts)) to loaded
            is Loaded.Invalid -> null to loaded
        }
    }
}

/**
 * The hash of a rule set (contracts.md §2.2, `rules`): each file's is the SHA-256 of its bytes, in hex; the set's is the
 * SHA-256 of the lines `<path> <file hash>`, sorted by path, each ended by a line feed.
 */
fun rulesHash(texts: Map<String, String>): String =
    sha256(texts.mapValues { (_, text) -> sha256(text.toByteArray(Charsets.UTF_8)) }.toSortedMap().entries.joinToString("") { (path, file) -> "$path $file\n" }.toByteArray(Charsets.UTF_8))

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
