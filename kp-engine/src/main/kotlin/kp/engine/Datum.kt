package kp.engine

import java.math.BigDecimal

/**
 * A value the engine reads or decides: a message's `args`, a fact's value, an effect's `with:`. JSON's shapes, in the
 * Kotlin standard library only — the engine's allow-list (architecture.md §2) has no JSON library, so a line reaches
 * it as text ([parse]) and leaves it as text ([json]).
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

        /** One JSON value (RFC 8259); anything else is refused. */
        fun parse(json: String): Datum = JsonReader(json).document()
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

/** The four hex digits of a `\\u` escape. */
private val unicodeEscape = Regex("[0-9a-fA-F]{4}")

private class JsonReader(private val text: String) {
    private var at = 0

    fun document(): Datum = value().also { blank(); require(at == text.length) { "trailing text at $at" } }

    private fun value(): Datum {
        blank()
        require(at < text.length) { "a value is missing at $at" }
        return when (text[at]) {
            '{' -> fields()
            '[' -> items()
            '"' -> Datum.Text(string())
            't' -> word("true", Datum.Bool(true))
            'f' -> word("false", Datum.Bool(false))
            'n' -> word("null", Datum.Null)
            else -> number()
        }
    }

    private fun fields(): Datum {
        at++
        val fields = LinkedHashMap<String, Datum>()
        blank()
        if (peek('}')) return Datum.Fields(fields).also { at++ }
        do {
            blank()
            require(peek('"')) { "a key is missing at $at" }
            val key = string()
            blank()
            expect(':')
            fields[key] = value()
            blank()
        } while (next(','))
        expect('}')
        return Datum.Fields(fields)
    }

    private fun items(): Datum {
        at++
        val items = ArrayList<Datum>()
        blank()
        if (peek(']')) return Datum.Items(items).also { at++ }
        do {
            items += value()
            blank()
        } while (next(','))
        expect(']')
        return Datum.Items(items)
    }

    private fun string(): String = buildString {
        expect('"')
        while (true) {
            require(at < text.length) { "an unterminated string" }
            val char = text[at++]
            when {
                char == '"' -> return@buildString
                char == '\\' -> {
                    val escaped = text[at++]
                    when (escaped) {
                        '"', '\\', '/' -> append(escaped)
                        'b' -> append('\b')
                        'f' -> append('\u000C')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            val digits = requireNotNull(unicodeEscape.matchAt(text, at)) { "a \\u escape needs four hex digits at $at" }
                            append(java.util.HexFormat.fromHexDigits(digits.value).toChar())
                            at = digits.range.last.inc()
                        }
                        else -> throw IllegalArgumentException("an unknown escape at $at")
                    }
                }
                else -> append(char)
            }
        }
    }

    private fun number(): Datum {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
        val token = text.substring(start, at)
        require(token.isNotEmpty()) { "not a value at $start" }
        return Datum.Number(BigDecimal(token))
    }

    private fun word(word: String, value: Datum): Datum {
        require(text.startsWith(word, at)) { "not a value at $at" }
        at += word.length
        return value
    }

    private fun blank() { while (at < text.length && text[at].isWhitespace()) at++ }
    private fun peek(char: Char) = at < text.length && text[at] == char
    private fun next(char: Char) = peek(char).also { if (it) at++ }
    private fun expect(char: Char) { require(next(char)) { "'$char' expected at $at" } }
}
