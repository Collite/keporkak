package kp.daemon

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kp.engine.Datum
import kp.engine.FactRef
import kp.mailroom.FactSource
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText

/**
 * A walk of `rules/walks/` (contracts.md §4.8): a stage, its seats, a table of facts and the steps — who sends which
 * kind with which args, which operation ends how, which facts change. Scalars are read as YAML 1.2 reads them.
 */
class Walk(val name: String, val stage: String, val seats: Map<String, String>, val facts: Map<String, Datum>, val steps: List<Step>) {
    /** One step, as the walk writes it; [fields] holds every key. */
    class Step(val fields: Map<String, Datum>) {
        fun text(key: String): String? = (fields[key] as? Datum.Text)?.value
        fun fields(key: String): Map<String, Datum> = (fields[key] as? Datum.Fields)?.fields.orEmpty()
        val id: String? get() = text("id")
        val who: String? get() = text("who")
        val kind: String? get() = text("kind")
        val operation: String? get() = text("operation")
        val facts: Map<String, Datum> get() = fields("facts")
    }

    companion object {
        fun read(path: Path): Walk {
            val walk = (datum(Yaml.default.parseToYamlNode(path.readText())) as Datum.Fields).fields
            fun text(key: String) = (walk[key] as? Datum.Text)?.value
            return Walk(
                text("name") ?: path.fileName.toString(),
                text("stage") ?: error("$path names no stage"),
                (walk["seats"] as? Datum.Fields)?.fields.orEmpty().mapValues { (it.value as Datum.Text).value },
                (walk["facts"] as? Datum.Fields)?.fields.orEmpty(),
                (walk["steps"] as? Datum.Items)?.items.orEmpty().map { Step((it as Datum.Fields).fields) },
            )
        }

        private val integer = Regex("-?(0|[1-9][0-9]*)")
        private val decimal = Regex("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][-+]?[0-9]+)?")

        private fun datum(node: YamlNode): Datum = when (node) {
            is YamlMap -> Datum.Fields(node.entries.entries.associate { (key, value) -> key.content to datum(value) })
            is YamlList -> Datum.Items(node.items.map(::datum))
            is YamlNull -> Datum.Null
            is YamlTaggedNode -> datum(node.innerNode)
            is YamlScalar -> scalar(node.content)
        }

        private fun scalar(content: String): Datum = when {
            content in setOf("~", "null", "Null", "NULL") -> Datum.Null
            content.equals("true", ignoreCase = true) -> Datum.Bool(true)
            content.equals("false", ignoreCase = true) -> Datum.Bool(false)
            integer.matches(content) || decimal.matches(content) -> Datum.Number(BigDecimal(content))
            else -> Datum.Text(content)
        }
    }
}

/**
 * Facts from a walk's table, looked up the walker's way (`check-rules.py`): a plain value, or a map keyed by the first
 * parameter the fact is asked with of `of`, `seat`, `id`, `dimension`, `subscription`, `pointer`. A fact not in the
 * table is unknown.
 */
class TableFacts(initial: Map<String, Datum> = emptyMap()) : FactSource {
    private val table = ConcurrentHashMap(initial)

    fun set(values: Map<String, Datum>) = table.putAll(values)

    override fun observe(fact: FactRef, stage: String?): Datum? {
        val value = table[fact.name] ?: return null
        if (value !is Datum.Fields) return value
        val key = KEYS.firstNotNullOfOrNull { fact.params[it] } ?: return null
        return value.fields[(key as? Datum.Text)?.value ?: key.json()]
    }

    private companion object {
        val KEYS = listOf("of", "seat", "id", "dimension", "subscription", "pointer")
    }
}
