package kp.driver.scan

/*
 * The source scan of S2.1's SeatFactsSpec (review-008, B1), shared from the driver's test fixtures so that the daemon's
 * specs scan its source the same way (S2.4 DONE: "as S2.1's does").
 */

/**
 * The lines of [source] that compare one value with another, trimmed, as written. A line compares when its code —
 * comments and the text of string and char literals left out, a template's `${…}` kept — still holds `<` or `>` once
 * its arrows and its type argument and parameter lists are set aside, or holds a range or a call that compares, bounds
 * or sorts. Neither side of a comparison is read, so no form of either can hide one (review-008, B1).
 */
fun comparisons(source: String): List<String> {
    val written = source.lines()
    return code(source).lines().withIndex().filter { (_, line) -> compares(line) }.map { (i, _) -> written[i].trim() }
}

private fun compares(code: String): Boolean {
    val line = withoutTypeArguments(code.replace(ARROW, "  "))
    return '<' in line || '>' in line || ORDERING.any { it.containsMatchIn(line) }
}

/** `->` of a lambda, a `when` branch or a function type — not the `>` of `x-->y`. */
private val ARROW = Regex("""(?<!-)->""")

/** What orders values without `<` or `>`: a range, an infix range word, a call that compares, bounds or sorts. */
private val ORDERING = listOf(
    Regex("""\.\."""),
    Regex("""(?<=[\w)\]!])\s+(?:until|downTo|rangeTo|rangeUntil)\s+(?=[\w(\[!+\-.'"])"""),
    Regex(
        """\b(?:compare|compareTo|compareValues|compareValuesBy|compareBy|compareByDescending|thenBy|thenByDescending|""" +
            """Comparator|Comparable|naturalOrder|reverseOrder|isAfter|isBefore|isNegative|isPositive|isZero|signum|""" +
            """coerceAtMost|coerceAtLeast|coerceIn|maxOf|minOf|maxOfOrNull|minOfOrNull|maxOrNull|minOrNull|maxBy|minBy|""" +
            """maxByOrNull|minByOrNull|maxWith|minWith|maxWithOrNull|minWithOrNull|sorted|sortedBy|sortedByDescending|""" +
            """sortedDescending|sortedWith|sortBy|sortByDescending|sortDescending|sortWith)\b""",
    ),
    Regex("""\.\s*(?:max|min|sign|sort)\b|(?<![\w.])(?:max|min)\s*\("""),
)

/** [line] with each type argument or type parameter list blanked. */
private fun withoutTypeArguments(line: String): String {
    val out = StringBuilder(line)
    var i = 0
    while (i < out.length) {
        val end = if (out[i] == '<') typeArgumentsEnd(out.toString(), i) else null
        if (end == null) i++ else { (i..end).forEach { out.setCharAt(it, ' ') }; i = end + 1 }
    }
    return out.toString()
}

/**
 * Where the type argument or parameter list that opens at [start] ends, or null when that `<` compares. A list holds
 * only names, `.`, `,`, `?`, `*`, `:`, `@`, brackets and nested lists, and opens right after `fun`, or right after a
 * name that stands in a type's place (after `:`, `as`, `is`, `fun`, `val`, `var`, `class`, `interface`, `typealias`)
 * or is called (`(`, `{`, `::`, `.` after the list) — as Kotlin reads `a<b, c>(d)` as a call and `a<b, c>d` as two
 * comparisons.
 */
private fun typeArgumentsEnd(line: String, start: Int): Int? {
    val before = line.substring(0, start)
    val afterFun = AFTER_FUN.containsMatchIn(before)
    val name = NAME_BEFORE.find(before)
    if (name == null && !afterFun) return null
    var depth = 0
    var brackets = 0
    for (i in start until line.length) {
        val c = line[i]
        when {
            c == '<' -> depth++
            c == '(' -> brackets++
            c == ')' -> if (--brackets < 0) return null
            c == '>' -> if (--depth == 0) {
                if (brackets != 0) return null
                val typePlace = afterFun || TYPE_PLACE.containsMatchIn(before.substring(0, name!!.range.first))
                return if (typePlace || CALLED.containsMatchIn(line.substring(i + 1))) i else null
            }
            !(c.isLetterOrDigit() || c in "_.,?*:@ \t") -> return null
        }
    }
    return null
}

private val AFTER_FUN = Regex("""\bfun\s+$""")
private val NAME_BEFORE = Regex("""[A-Za-z_][\w.]*$""")
private val TYPE_PLACE = Regex("""(?::|\b(?:as\??|is|fun|val|var|class|interface|typealias))\s*$""")
private val CALLED = Regex("""^\s*(?:\(|\{|::|\??\.)""")

/** [source] line for line, with its comments and the text of its string and char literals blanked; a template's `${…}` stays. */
private fun code(source: String): String {
    class Mode(val kind: Char) { var braces = 0 } // 'c' code, '"' a string, 'r' a raw string
    val out = StringBuilder(source.length)
    val modes = ArrayDeque(listOf(Mode('c')))
    var i = 0
    fun at(s: String) = source.startsWith(s, i)
    fun keep(n: Int) = repeat(n) { if (i < source.length) out.append(source[i++]) }
    fun blank(n: Int) = repeat(n) { if (i < source.length) out.append(if (source[i++] == '\n') '\n' else ' ') }
    while (i < source.length) {
        val mode = modes.last()
        when {
            mode.kind == 'c' -> when {
                at("//") -> while (i < source.length && !at("\n")) blank(1)
                at("/*") -> {
                    var depth = 0
                    do {
                        when {
                            at("/*") -> { depth++; blank(2) }
                            at("*/") -> { depth--; blank(2) }
                            else -> blank(1)
                        }
                    } while (depth > 0 && i < source.length)
                }
                at("\"\"\"") -> { keep(3); modes.addLast(Mode('r')) }
                at("\"") -> { keep(1); modes.addLast(Mode('"')) }
                at("'") -> { keep(1); while (i < source.length && !at("'")) blank(if (at("\\")) 2 else 1); keep(1) }
                at("{") -> { mode.braces++; keep(1) }
                at("}") && mode.braces == 0 && modes.size > 1 -> { modes.removeLast(); blank(1) }
                at("}") -> { mode.braces--; keep(1) }
                else -> keep(1)
            }
            at("\${") -> { blank(2); modes.addLast(Mode('c')) }
            mode.kind == 'r' && at("\"\"\"") && !at("\"\"\"\"") -> { keep(3); modes.removeLast() }
            mode.kind == '"' && at("\\") -> blank(2)
            mode.kind == '"' && at("\"") -> { keep(1); modes.removeLast() }
            else -> blank(1)
        }
    }
    check(modes.size == 1) { "the scan ended inside a string or a template" }
    return out.toString()
}
