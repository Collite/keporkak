package kp.engine

import java.math.BigDecimal

/**
 * A value the engine reads or decides: a message's `args`, a fact's value, an effect's `with:`. JSON's shapes, in the
 * Kotlin standard library only — the engine's allow-list (architecture.md §2) has no JSON library, so a line reaches
 * it as kp-model's plain view ([of]) and leaves it as text ([json]).
 */
sealed interface Datum {
    data class Text(val value: String) : Datum
    /** Kept without trailing zeros, so that `1` and `1.0` are the same number. */
    class Number(value: BigDecimal) : Datum {
        val value: BigDecimal = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
        override fun equals(other: Any?) = other is Number && other.value.compareTo(value) == 0
        override fun hashCode() = value.hashCode()
        override fun toString() = "Number(${value.toPlainString()})"
    }
    data class Bool(val value: Boolean) : Datum
    data object Null : Datum
    data class Items(val items: List<Datum>) : Datum
    data class Fields(val fields: Map<String, Datum>) : Datum

    /** Compact JSON. */
    fun json(): String = when (this) {
        is Text -> quote(value)
        is Number -> value.toPlainString()
        is Bool -> value.toString()
        Null -> "null"
        is Items -> items.joinToString(",", "[", "]") { it.json() }
        is Fields -> fields.entries.joinToString(",", "{", "}") { (key, item) -> quote(key) + ":" + item.json() }
    }

    companion object {
        /** A Kotlin value as a datum: text, a whole or decimal number, a boolean, null, a list or a map with text keys. */
        fun of(value: Any?): Datum = when (value) {
            null -> Null
            is Datum -> value
            is String -> Text(value)
            is Boolean -> Bool(value)
            is Int -> Number(BigDecimal.valueOf(value.toLong()))
            is Long -> Number(BigDecimal.valueOf(value))
            is BigDecimal -> Number(value)
            is java.math.BigInteger -> Number(BigDecimal(value))
            is Double -> Number(BigDecimal.valueOf(value))
            is List<*> -> Items(value.map(::of))
            is Map<*, *> -> Fields(value.entries.associate { (key, item) -> key as String to of(item) })
            else -> throw IllegalArgumentException("not a datum: $value")
        }
    }
}

private fun quote(text: String): String = buildString {
    append('"')
    text.forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}
