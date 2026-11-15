package kp.rules

import com.charleskorn.kaml.ForbiddenAnchorOrAliasException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import java.math.BigDecimal

/** A parsed YAML document, before typing: maps keep their order, scalars are resolved as YAML 1.2 does. */
internal sealed interface Node {
    val line: Int

    class Mapping(val entries: Map<String, Node>, override val line: Int) : Node
    class Sequence(val items: List<Node>, override val line: Int) : Node
    class Scalar(val literal: Literal, override val line: Int) : Node {
        val text: String? get() = (literal as? Literal.Text)?.value
    }
}

internal object YamlText {
    // kaml's default configuration refuses anchors and aliases and duplicate keys.
    private val yaml = Yaml.default
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val decimal = Regex("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][-+]?[0-9]+)?")
    private val nulls = setOf("~", "null", "Null", "NULL")

    /** The document, or `null` with one problem naming why it cannot be read. */
    fun parse(file: String, text: String, report: Report): Node? = try {
        Converter(file, text.lines(), report).convert(yaml.parseToYamlNode(text))
    } catch (problem: ForbiddenAnchorOrAliasException) {
        report.add(file, null, "a YAML anchor or alias (line ${problem.line}) — every rule is written out")
        null
    } catch (problem: YamlException) {
        report.add(file, null, "not readable YAML (line ${problem.line}): ${problem.message}")
        null
    }

    /** The frontmatter between the first two `---` lines of a persona, or `null` if it has none. */
    fun frontmatter(file: String, text: String, report: Report): Node? {
        val match = Regex("\\A---\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text)
        if (match == null) {
            report.add(file, null, "no frontmatter")
            return null
        }
        return parse(file, match.groupValues[1], report)
    }

    /**
     * Turns kaml's tree into [Node]s. A scalar is resolved by its text, quoted or not; a quoted one whose text reads
     * as a number, a boolean or null is reported, since its writer meant text (S1.0's Found lines; S1.2 T8).
     */
    private class Converter(private val file: String, private val lines: List<String>, private val report: Report) {
        fun convert(node: YamlNode): Node = when (node) {
            is YamlMap -> Node.Mapping(node.entries.entries.associate { (key, value) -> key.content to convert(value) }, node.path.endLocation.line)
            is YamlList -> Node.Sequence(node.items.map(::convert), node.path.endLocation.line)
            is YamlScalar -> Node.Scalar(resolve(node.content).also { literal -> if (literal !is Literal.Text && quoted(node)) quotedProblem(node) }, node.path.endLocation.line)
            is YamlNull -> Node.Scalar(Literal.Null, node.path.endLocation.line)
            is YamlTaggedNode -> throw YamlException("an explicit tag '${node.tag}' is not part of the rules' form", node.path)
        }

        /** Whether the scalar's text starts with a quote where kaml says the scalar starts. */
        private fun quoted(node: YamlScalar): Boolean = node.path.endLocation.let { lines.getOrNull(it.line - 1)?.getOrNull(it.column - 1) } in setOf('\'', '"')

        private fun quotedProblem(node: YamlScalar) = report.add(file, null,
            "the quoted scalar '${node.content}' (line ${node.path.endLocation.line}) would read as a number, a boolean or null — the loader resolves a scalar by its text, not its quotes")
    }

    fun resolve(content: String): Literal = when {
        content in nulls -> Literal.Null
        content.equals("true", ignoreCase = true) -> Literal.Bool(true)
        content.equals("false", ignoreCase = true) -> Literal.Bool(false)
        integer.matches(content) || decimal.matches(content) -> Literal.Number(BigDecimal(content))
        else -> Literal.Text(content)
    }
}

/** Every problem found while loading, in the order found. */
internal class Report {
    private val found = LinkedHashSet<Problem>()
    val problems: List<Problem> get() = found.toList()
    fun add(file: String, id: String?, message: String) {
        found += Problem(file, id, message)
    }
}

/** Flattens a document's scalars by dotted path (`presence.idle_hours_to_absent`, `warn.0`). */
internal fun flatten(node: Node, prefix: String = ""): Map<String, Literal> = when (node) {
    is Node.Scalar -> if (prefix.isEmpty()) emptyMap() else mapOf(prefix to node.literal)
    is Node.Mapping -> node.entries.flatMap { (key, value) -> flatten(value, if (prefix.isEmpty()) key else "$prefix.$key").toList() }.toMap()
    is Node.Sequence -> node.items.withIndex().flatMap { (index, value) -> flatten(value, if (prefix.isEmpty()) "$index" else "$prefix.$index").toList() }.toMap()
}
