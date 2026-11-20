package kp.rules

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.yamlList
import com.charleskorn.kaml.yamlMap
import com.charleskorn.kaml.yamlScalar
import io.github.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.KtNodeTypes
import java.io.File

/*
 * The check that the daemon decides nothing (S3.0 T3; the retro of P2, candidate 2; architecture.md §2): one check over
 * the main sources of kp-driver, kp-daemon, kp-seat-api and kp-engine, on their syntax tree — detekt 1.23.8's parser,
 * tests only (the probe: ~/Dev/_scratch/keporkak/S3.0/probe.md). It walks the tree itself and reports through its own
 * list, never through detekt's `Rule.report`, so no `@Suppress` in a source can hide a finding; a file whose tree holds a
 * parse error fails the check, so a syntax newer than its parser cannot hide one either.
 *
 * It holds what nine regex sentences held, per module as they did (DaemonSourceSpec, SeatFactsSpec, AnswererSpec,
 * WhoamiSpec, NoTokenSpec, DecisionRecordsSpec): the names it looks for are read from rules/ and from the seat's
 * permissions data; the words for a conclusion and every exception are in build-support/decides-nothing.yaml, each
 * exception with its reason.
 */

/** The six shapes of a decision the check looks for. */
enum class Shape(val id: String) {
    /** A comparison or ordering of a value: `<`, `>`, a range, a call that compares, bounds or sorts, `==` on arithmetic or a number. */
    COMPARISON("comparison"),
    /** A timer, a delay or a wait for a time: a sleep, a schedule, a wait with a timeout, a duration, a time unit, a constant named for one. */
    TIMER("timer"),
    /** A kind's name of rules/message-kinds.yaml in a literal — in kp-engine anywhere: its literals, comments and names. */
    KIND("kind"),
    /** A number but zero. */
    NUMBER("number"),
    /** A word for a conclusion about a seat (build-support/decides-nothing.yaml `conclusions`), in a name, a literal or a comment. */
    CONCLUSION("conclusion"),
    /** A tool, a path field or a default of the seat's permissions (agents/packaging/claude.yaml, rules/engagement.yaml) in a literal. */
    ANSWERER("answerer"),
    ;

    companion object {
        fun of(id: String): Shape? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which shapes each module's main source is held to. As the nine sentences held them; where a shape held only one
 * module, it now holds two (S3.0 T1: each shape is planted in two modules the check covers): the timer the driver as
 * well as the daemon, the answerer's names the daemon as well as the driver, a word for a conclusion the seat API as well
 * as the driver — the other module that faces a seat. Not the daemon: it names the rules' seat state `idle` and an
 * operation's `done` callback throughout, 69 places that conclude nothing (S3.0's Found lines).
 */
val COVERS: Map<String, Set<Shape>> = mapOf(
    "kp-driver" to setOf(Shape.COMPARISON, Shape.TIMER, Shape.CONCLUSION, Shape.ANSWERER),
    "kp-daemon" to setOf(Shape.COMPARISON, Shape.TIMER, Shape.KIND, Shape.ANSWERER),
    "kp-seat-api" to setOf(Shape.KIND, Shape.NUMBER, Shape.CONCLUSION),
    "kp-engine" to setOf(Shape.KIND, Shape.NUMBER),
)

/**
 * Where a kind's name is looked for in every word — literals, comments and names — as DecisionRecordsSpec did (S1.2): a
 * run of lower-case letters and hyphens, so `hold` and `holdIt` are read and `Trigger.Release`, a robot's trigger, is not.
 */
val KINDS_ANYWHERE = setOf("kp-engine")

/** Where an exception for a number that is the stop gate's cap is refused, as NoTokenSpec did (S2.7 T6). */
val CAP_REFUSED = setOf("kp-seat-api", "kp-engine")

const val ALLOW_LIST = "build-support/decides-nothing.yaml"

/** One finding: a shape at a file's line, in a declaration, as written (whitespace collapsed). */
data class Finding(val path: String, val line: Int, val shape: Shape, val inside: String, val code: String) {
    override fun toString() = "$path:$line: ${shape.id} in $inside: $code"
}

/** One exception of the allow-list. */
data class Excused(val path: String, val shape: String, val inside: String, val code: String, val reason: String) {
    override fun toString() = "$path: ${shape} in $inside: $code"
}

/** What the check reads besides the sources: the names from rules/ and the permissions data, and the allow-list. */
class Names(root: File) {
    private fun yaml(path: String): YamlMap = Yaml.default.parseToYamlNode(File(root, path).readText()).yamlMap
    private fun YamlMap.map(key: String): YamlMap = get<YamlMap>(key) ?: error("no $key")
    private fun YamlNode.scalars(): List<String> = (this as? YamlList)?.items?.map { it.yamlScalar.content }.orEmpty()

    val kinds: Set<String> = yaml("rules/message-kinds.yaml").map("kinds").entries.keys.map { it.content }.toSet()
    val stopGateCap: Long = yaml("rules/engagement.yaml").map("caps").get<YamlScalar>("stop_gate_blocks")!!.content.toLong()

    /** The answerer's names: the request's path fields and read tools, every tool a deny rule names, each default and the option its lifetime makes. */
    val answerer: Set<String> = run {
        val packaging = yaml("agents/packaging/claude.yaml")
        val requests = packaging.map("requests")
        val tools = packaging.map("deny").entries.values.flatMap { word ->
            word.yamlMap.get<YamlList>("rules")?.items.orEmpty().flatMap { rule -> rule.yamlMap.get<YamlNode>("tools")?.scalars().orEmpty() }
        }.filterNot { it.endsWith("__*") }
        val defaults = yaml("rules/engagement.yaml").map("permissions")
        val lifetime = defaults.get<YamlScalar>("lifetime")?.content
        val answers = defaults.entries.filterKeys { it.content != "lifetime" }.values.map { it.yamlScalar.content }.toSet()
        (requests.get<YamlNode>("paths")!!.scalars() + requests.get<YamlNode>("read")!!.scalars() + tools +
            defaults.entries.keys.map { it.content } + answers.map { "${it}_$lifetime" }).toSet()
    }

    private val allowList = yaml(ALLOW_LIST)
    val conclusions: Set<String> = allowList.get<YamlNode>("conclusions")!!.scalars().map { it.lowercase() }.toSet()

    /** Every exception, with the problems of the list's own form: an entry with no reason, a shape it does not know. */
    val exceptions: List<Excused> = allowList.get<YamlMap>("exceptions")?.entries.orEmpty().flatMap { (path, entries) ->
        entries.yamlList.items.map { entry ->
            val fields = entry.yamlMap
            fun field(name: String) = fields.get<YamlScalar>(name)?.content.orEmpty()
            Excused(path.content, field("shape"), field("in"), field("code"), field("reason").trim())
        }
    }
}

/** The main sources of the modules the check covers, by path relative to [root]. */
fun coveredSources(root: File): List<File> = COVERS.keys.sorted().flatMap { module ->
    File(root, "$module/src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
}

/** What the check found in [root]: every finding, and the files whose tree holds a parse error. */
class Scan(val root: File, val names: Names = Names(root)) {
    val parseErrors = mutableListOf<String>()
    val findings: List<Finding> = coveredSources(root).flatMap { file ->
        val path = file.relativeTo(root).invariantSeparatorsPath
        val module = path.substringBefore('/')
        val tree = compileContentForTest(file.readText(), file.name)
        FileScan(path, tree, COVERS.getValue(module), module in KINDS_ANYWHERE, names).also { parseErrors += it.errors }.findings
    }

    /** The findings no exception excuses, and the exceptions that are wrong: no reason, a shape unknown, nothing left to excuse, a cap. */
    fun problems(exceptions: List<Excused> = names.exceptions): List<String> {
        val open = exceptions.toMutableList()
        val unexcused = findings.filter { finding ->
            val at = open.indexOfFirst { it.path == finding.path && it.shape == finding.shape.id && it.inside == finding.inside && it.code == finding.code }
            if (at >= 0) open.removeAt(at)
            at < 0
        }
        val module = { e: Excused -> e.path.substringBefore('/') }
        return parseErrors.map { "a parse error: $it" } +
            unexcused.map { "not on the allow-list: $it" } +
            exceptions.filter { it.reason.isEmpty() }.map { "an exception with no reason: $it" } +
            exceptions.filter { Shape.of(it.shape) == null }.map { "an exception of no shape the check knows: $it" } +
            exceptions.filter { it.shape == Shape.NUMBER.id && module(it) in CAP_REFUSED && number(it.code) == names.stopGateCap }
                .map { "an exception for the stop gate's cap, ${names.stopGateCap}, which no number in ${module(it)} may be: $it" } +
            open.map { "an exception the sources no longer need: $it" }
    }
}

/** The findings in [text], as if it were the main source [path] of its module: a snippet's, for the check's own specs. */
fun scanText(path: String, text: String, names: Names): List<Finding> {
    val module = path.substringBefore('/')
    val scan = FileScan(path, compileContentForTest(text, File(path).name), COVERS.getValue(module), module in KINDS_ANYWHERE, names)
    return scan.findings.also { check(scan.errors.isEmpty()) { "a parse error: ${scan.errors}" } }
}

/** One file's findings, each shape its module is held to; a finding inside another of its shape is that one's. */
private class FileScan(val path: String, val file: KtFile, val shapes: Set<Shape>, val kindsAnywhere: Boolean, val names: Names) {
    val errors = mutableListOf<String>()
    private val text = file.text
    private val raw = mutableListOf<Pair<PsiElement, Finding>>()

    val findings: List<Finding> by lazy {
        walk(file)
        raw.filter { (element, finding) ->
            raw.none { (other, of) -> other !== element && of.shape == finding.shape && other.textRange.contains(element.textRange) && other.textRange != element.textRange }
        }.map { it.second }.distinct()
    }

    private fun walk(element: PsiElement) {
        if (element is PsiErrorElement) errors += "$path:${line(element)}: ${element.errorDescription}"
        // An import or the package names what the code uses; it is no code itself.
        if (element is KtImportList || element is KtPackageDirective) return
        look(element)
        if (element is PsiComment) return
        var child = element.firstChild
        while (child != null) {
            walk(child)
            child = child.nextSibling
        }
    }

    private fun found(shape: Shape, element: PsiElement, code: String = element.text) {
        if (shape in shapes) raw += element to Finding(path, line(element), shape, inside(element), collapse(code))
    }

    private fun look(element: PsiElement) {
        when (element) {
            is KtBinaryExpression -> binary(element)
            is KtWhenConditionWithExpression -> whenCondition(element)
            is KtNameReferenceExpression -> reference(element)
            is KtProperty -> if (LIMIT_NAME.matches(element.name.orEmpty())) found(Shape.TIMER, element, undocumented(element))
            is KtConstantExpression -> if (element.node.elementType in NUMBERS && number(element.text)?.let { it != 0L } != false) found(Shape.NUMBER, element)
            is KtStringTemplateExpression -> template(element)
            is PsiComment -> comment(element)
        }
        if (element is KtNamedDeclaration) element.name?.let { name -> words(name, element.nameIdentifier ?: element) }
        if (element is KtNameReferenceExpression) words(element.getReferencedName(), element)
    }

    private fun binary(e: KtBinaryExpression) {
        val token = e.operationToken
        val name = e.operationReference.getReferencedName()
        val compares = token in ORDER || (token == KtTokens.IDENTIFIER && (name in RANGE_WORDS || name in ORDERING)) ||
            (token in EQUALITY && (sum(e.left) || sum(e.right) || nonZero(e.left) || nonZero(e.right)))
        if (compares) found(Shape.COMPARISON, e)
        if (token == KtTokens.IDENTIFIER && name in TIMER_CALLS) found(Shape.TIMER, e)
    }

    private fun whenCondition(e: KtWhenConditionWithExpression) {
        val subject = (e.parent?.parent as? KtWhenExpression)?.subjectExpression ?: return
        if (sum(subject) || nonZero(e.expression)) found(Shape.COMPARISON, e, "when (${subject.text}) ${e.text}")
    }

    private fun reference(e: KtNameReferenceExpression) {
        val name = e.getReferencedName()
        val parent = e.parent
        val call = (parent as? KtCallExpression)?.takeIf { it.calleeExpression === e }
        val selected = (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === e }
        // What is shown and excused: a call as written, its receiver left out; a member with its receiver; a reference; a type.
        val owner: PsiElement? = call ?: selected ?: (parent as? KtCallableReferenceExpression) ?: (parent as? KtUserType)
        if (owner != null) {
            if (name in ORDERING) found(Shape.COMPARISON, owner)
            val arguments = call?.valueArguments?.count { it !is KtLambdaArgument } ?: 0
            val timer = name in TIMER_CALLS || name in TIME_NAMES || (name in TIMEOUT_CALLS && arguments >= 1) ||
                (name == "get" && arguments == 2) || (name == "offer" && arguments == 3)
            if (timer) found(Shape.TIMER, owner)
        }
        // A time unit or a duration by its type: `Duration.ofSeconds(2)`, `TimeUnit.SECONDS`, `ChronoUnit.MINUTES`.
        val receiverOf = (parent as? KtQualifiedExpression)?.takeIf { it.receiverExpression === e }
        // `Duration.ZERO` waits for nothing, as zero is no number.
        if (receiverOf != null && name in TIME_TYPES && receiverOf.selectorExpression?.text != "ZERO") found(Shape.TIMER, receiverOf)
        if (LIMIT_NAME.matches(name)) found(Shape.TIMER, e)
    }

    private fun template(e: KtStringTemplateExpression) {
        val literal = e.entries.all { it is KtLiteralStringTemplateEntry }
        val whole = e.entries.joinToString("") { it.text }
        val pieces = e.entries.filterIsInstance<KtLiteralStringTemplateEntry>().map { it.text }
        val tokens = pieces.filter { piece -> piece.none(Char::isWhitespace) }.flatMap { TOKEN.findAll(it).map { m -> m.value } }.toSet()
        fun named(names: Set<String>) = (literal && whole in names) || tokens.any { it in names }
        if (named(names.kinds)) found(Shape.KIND, e)
        if (named(names.answerer)) found(Shape.ANSWERER, e)
        if (literal && NUMBER_TEXT.matches(whole) && number(whole) != 0L) found(Shape.NUMBER, e)
        val prose = pieces.flatMap(::wordsOf)
        if (prose.any { it in names.conclusions }) found(Shape.CONCLUSION, e)
        if (kindsAnywhere && pieces.any { piece -> LOWER_RUN.findAll(piece).any { it.value in names.kinds } }) found(Shape.KIND, e)
    }

    private fun comment(e: PsiComment) {
        e.text.lines().forEachIndexed { i, line ->
            val words = wordsOf(line)
            val shown = line.trim().trimStart('/', '*').trim()
            val at = line(e) + i
            if (words.any { it in names.conclusions } && Shape.CONCLUSION in shapes) raw += e to Finding(path, at, Shape.CONCLUSION, inside(e), collapse(shown))
            if (kindsAnywhere && LOWER_RUN.findAll(line).any { it.value in names.kinds } && Shape.KIND in shapes) raw += e to Finding(path, at, Shape.KIND, inside(e), collapse(shown))
        }
    }

    /** A name's words — `isStalled` is `is`, `stalled` — against the conclusions; in kp-engine its lower-case runs against the kinds. */
    private fun words(name: String, at: PsiElement) {
        val parts = name.split('_').flatMap { CAMEL.findAll(it).map { m -> m.value.lowercase() } }
        if (parts.any { it in names.conclusions }) found(Shape.CONCLUSION, at, name)
        if (kindsAnywhere && LOWER_RUN.findAll(name).any { it.value in names.kinds }) found(Shape.KIND, at, name)
    }

    private fun inside(element: PsiElement): String {
        val names = mutableListOf<String>()
        var at: PsiElement? = element
        while (at != null && at !is KtFile) {
            val member = at.parent is KtFile || at.parent is KtClassBody || at.parent?.parent is KtPrimaryConstructor
            when {
                at is KtObjectDeclaration && at.isCompanion() -> names += at.name ?: "Companion"
                at is KtObjectDeclaration && at.isObjectLiteral() -> names += "object"
                at is KtClassOrObject -> names += at.name ?: "class"
                at is KtNamedFunction && member -> names += at.name ?: "fun"
                at is KtProperty && member -> names += at.name ?: "val"
                at is KtParameter && member -> names += at.name ?: "parameter"
                at is KtSecondaryConstructor -> names += "constructor"
                at is KtAnonymousInitializer -> names += "init"
            }
            at = at.parent
        }
        return names.reversed().joinToString(".").ifEmpty { File(path).nameWithoutExtension }
    }

    private fun line(element: PsiElement): Int = text.substring(0, element.textOffset).count { it == '\n' } + 1

    /** A declaration's text without its doc comment: what it declares. */
    private fun undocumented(element: PsiElement): String {
        var child = element.firstChild
        while (child != null && (child is PsiComment || child.text.isBlank())) child = child.nextSibling
        return child?.let { element.text.substring(it.startOffsetInParent) } ?: element.text
    }

    private fun sum(e: KtExpression?): Boolean = when (val x = unwrap(e)) {
        is KtBinaryExpression -> x.operationToken in ARITHMETIC || x.operationReference.getReferencedName() in ARITHMETIC_WORDS
        else -> false
    }

    private fun nonZero(e: KtExpression?): Boolean = (unwrap(e) as? KtConstantExpression)?.let { it.node.elementType in NUMBERS && number(it.text) != 0L } == true

    private fun unwrap(e: KtExpression?): KtExpression? = when (e) {
        is KtParenthesizedExpression -> unwrap(e.expression)
        is KtPrefixExpression -> if (e.operationToken == KtTokens.MINUS) unwrap(e.baseExpression) else e
        else -> e
    }
}

/** A number's value as Kotlin writes it — `2_000L`, `0x3`, `0b11`, `1.5f` — or null; a fraction counts as its whole part unless it is zero. */
fun number(text: String): Long? {
    val t = text.replace("_", "").lowercase().trimEnd('l', 'u', 'f').removePrefix("-")
    return when {
        t.startsWith("0x") -> t.drop(2).toLongOrNull(16)
        t.startsWith("0b") -> t.drop(2).toLongOrNull(2)
        else -> t.toDoubleOrNull()?.let { d -> if (d == 0.0) 0L else maxOf(1L, kotlin.math.abs(d).toLong()) }
    }
}

private fun collapse(text: String) = text.replace(Regex("\\s+"), " ").trim()

private val NUMBERS = setOf(KtNodeTypes.INTEGER_CONSTANT, KtNodeTypes.FLOAT_CONSTANT)
private val NUMBER_TEXT = Regex("-?(0[xX][0-9a-fA-F_]+|0[bB][01_]+|[0-9][0-9_]*(\\.[0-9]+)?)[lLuUfF]?")
private val ORDER = setOf(KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ, KtTokens.RANGE, KtTokens.RANGE_UNTIL)
private val EQUALITY = setOf(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)
private val ARITHMETIC = setOf(KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC)
private val ARITHMETIC_WORDS = setOf("rem", "div", "mod", "shr", "ushr", "shl", "and", "or", "xor", "floorDiv")
private val RANGE_WORDS = setOf("until", "downTo", "rangeTo", "rangeUntil", "step")

/** What compares, bounds or sorts without `<` or `>`, as a call, a member, a reference or a type. */
private val ORDERING = setOf(
    "compare", "compareTo", "compareValues", "compareValuesBy", "compareBy", "compareByDescending", "thenBy", "thenByDescending",
    "thenComparing", "comparing", "comparingInt", "comparingLong", "Comparator", "Comparable", "naturalOrder", "reverseOrder",
    "isAfter", "isBefore", "isNegative", "isPositive", "isZero", "signum", "sign", "coerceAtMost", "coerceAtLeast", "coerceIn",
    "max", "min", "maxOf", "minOf", "maxOfOrNull", "minOfOrNull", "maxOrNull", "minOrNull", "maxBy", "minBy", "maxByOrNull",
    "minByOrNull", "maxWith", "minWith", "maxWithOrNull", "minWithOrNull", "sorted", "sortedBy", "sortedByDescending",
    "sortedDescending", "sortedWith", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith",
    "IntRange", "LongRange", "CharRange", "UIntRange", "ULongRange", "ClosedRange",
)

/** Calls that sleep, schedule, wait for a time or read the clock's ticks, whatever their arguments. */
private val TIMER_CALLS = setOf(
    "sleep", "delay", "parkNanos", "parkUntil", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "awaitTermination",
    "withTimeout", "withTimeoutOrNull", "orTimeout", "completeOnTimeout", "delayedExecutor", "timer", "fixedRateTimer", "ticker",
    "nanoTime", "currentTimeMillis", "Timer", "newScheduledThreadPool", "newSingleThreadScheduledExecutor", "onTimeout",
)

/** Calls that wait with a timeout when they are given an argument: `join(5_000)`, `await(version, millis)`, `lock.wait(2000)`. */
private val TIMEOUT_CALLS = setOf("wait", "join", "await", "waitFor", "poll", "tryAcquire", "tryLock")

/** What makes or reads a duration, or moves a time by one. */
private val TIME_NAMES = setOf(
    "ofDays", "ofHours", "ofMinutes", "ofSeconds", "ofMillis", "ofNanos", "plusDays", "plusHours", "plusMinutes", "plusSeconds",
    "plusMillis", "plusNanos", "minusDays", "minusHours", "minusMinutes", "minusSeconds", "minusMillis", "minusNanos", "toDays",
    "toHours", "toMinutes", "toSeconds", "toMillis", "toNanos", "toJavaDuration", "toKotlinDuration", "toDuration", "inWholeDays",
    "inWholeHours", "inWholeMinutes", "inWholeSeconds", "inWholeMilliseconds", "inWholeMicroseconds", "inWholeNanoseconds",
    "multipliedBy", "dividedBy", "truncatedTo", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds",
)

/** The types whose members are a duration or a time unit. */
private val TIME_TYPES = setOf("Duration", "TimeUnit", "ChronoUnit", "DurationUnit")

/** A constant named for a time or a limit on one, as the timer scan read it (S2.9 T3). */
private val LIMIT_NAME = Regex("[A-Z][A-Z0-9_]*(SECONDS|MILLIS|NANOS|MINUTES|HOURS|GRACE|TIMEOUT|EVERY|WAIT)[A-Z0-9_]*")

/** A literal's tokens when it holds no space: `started|stop` is two; `seat.kill`, an operation's name, is one. */
private val TOKEN = Regex("[A-Za-z0-9_.-]+")
private val LOWER_RUN = Regex("[a-z][a-z-]*")
private val WORD = Regex("[A-Za-z]+(?:-[A-Za-z]+)*")

/** The words of a text, lower-cased: each hyphenated word whole and in its parts — `review-request`, `review`, `request`. */
private fun wordsOf(text: String): Set<String> = WORD.findAll(text).flatMap { m -> m.value.lowercase().let { w -> listOf(w) + w.split('-') } }.toSet()
private val CAMEL = Regex("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])")
