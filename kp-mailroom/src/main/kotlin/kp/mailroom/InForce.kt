package kp.mailroom

import kp.rules.Loaded
import kp.rules.RuleSet
import kp.rules.Rules
import java.security.MessageDigest

/**
 * A rule set put in force from its texts (contracts.md §2.2, `rules`): each file's hash is the SHA-256 of its bytes,
 * in hex; the set's is the SHA-256 of the lines `<path> <file hash>`, sorted by path, each ended by a line feed.
 */
internal class InForce private constructor(val ruleSet: RuleSet, val files: Map<String, String>, val hash: String) {
    companion object {
        /** The texts through `kp-rules`: a set in force, or every problem found. */
        fun load(texts: Map<String, String>): Pair<InForce?, Loaded> = when (val loaded = Rules.load(texts)) {
            is Loaded.Valid -> {
                val files = texts.mapValues { (_, text) -> sha256(text.toByteArray(Charsets.UTF_8)) }.toSortedMap()
                val hash = sha256(files.entries.joinToString("") { (path, file) -> "$path $file\n" }.toByteArray(Charsets.UTF_8))
                InForce(loaded.ruleSet, files, hash) to loaded
            }
            is Loaded.Invalid -> null to loaded
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
