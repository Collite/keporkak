package kp.rules.decidesnothing

import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * S3.0 T3 — the daemon decides nothing, held on the syntax tree of the main sources of kp-driver, kp-daemon, kp-seat-api,
 * kp-engine and, from S3.8 (D-98), kp-ops ([Scan], DecidesNothing.kt): the daemon reports and performs, the rules compare (contracts.md §4.4;
 * architecture.md §2). Each finding is listed in build-support/decides-nothing.yaml with its reason, or the check fails
 * with its file and line — an entry excuses its finding on the line it stands on, as written, and no edit of that line;
 * the list holds no entry without a reason, and none the sources no longer need. It replaces the nine regex sentences of
 * S1.2 to S2.9.
 */
class DecidesNothingSpec : StringSpec({
    val root = File(System.getProperty("kp.root"))
    val scan = Scan(root)
    val names = scan.names

    "the main sources of kp-driver, kp-daemon, kp-seat-api, kp-engine and kp-ops decide nothing: every comparison, timer, kind's name, number, word for a conclusion and answerer's name in them is on the allow-list with its reason" {
        // Every module the check covers that has a main source is read — kp-ops has none until S3.2's performers land.
        coveredSources(root).map { it.relativeTo(root).invariantSeparatorsPath.substringBefore('/') }.toSet() shouldBe
            COVERS.keys.filter { module -> File(root, "$module/src/main/kotlin").walk().any { it.isFile && it.extension == "kt" } }.toSet()
        val problems = scan.problems()
        if (problems.isNotEmpty()) fail("the check that the daemon decides nothing (build-support/decides-nothing.yaml):\n" + problems.joinToString("\n"))
    }

    "the names the check looks for are read from rules/ and the seat's permissions data" {
        names.kinds shouldContainAll setOf("assign", "started", "stop", "review-request")
        names.answerer shouldContainAll setOf("file_path", "notebook_path", "Read", "Glob", "Grep", "LS", "NotebookRead",
            "Write", "Edit", "MultiEdit", "NotebookEdit", "Bash", "allow_once", "write_inside", "write_outside")
        names.stopGateCap shouldBe Regex("""(?m)^\s*stop_gate_blocks:\s*(\d+)""").find(File(root, "rules/engagement.yaml").readText())!!.groupValues[1].toLong()
    }

    "each shape is found in two modules the check holds to it, in the form a regex scan listed, in one it did not, and in each form review-026 found it missed" {
        // The forms of S3.0 T1's planted lines (tests/test_s3_0_retro_checks.py), each as a main source of its module.
        val planted = mapOf(
            Shape.COMPARISON to listOf("kp-driver", "kp-daemon") to listOf(
                "fun over(heard: Instant, clock: Clock): Boolean = Duration.between(heard, clock.instant()) > Duration.ofMinutes(20)",
                "fun overToo(heard: Instant, clock: Clock): Boolean = Duration.between(heard, clock.instant()).toMinutes() / 20 != 0L",
                // review-026 B2: a range made by a call, in a `when`'s branch, a `takeIf` and on its own; a `when` over a `val`.
                "fun overWhen(used: Long, cap: Long) = when (used - cap) { in 1L.rangeTo(Long.MAX_VALUE) -> true; else -> false }",
                "fun overWhenVal(used: Long, cap: Long) = when (val d = used - cap) { 0L -> false; else -> true }",
                "fun overTakeIf(used: Long, cap: Long) = used.takeIf { it in cap.inc().rangeTo(Long.MAX_VALUE) }",
                "fun overUntil(used: Long, cap: Long) = used.until(cap).isEmpty()",
                // and where the same `when` puts the difference by `in`, whatever the range is.
                "fun overWhenIn(used: Long, cap: Long, over: Set<Long>) = when (used - cap) { in over -> true; else -> false }"),
            Shape.TIMER to listOf("kp-driver", "kp-daemon") to listOf(
                "fun grace() = Thread.sleep(2_000)", "fun graceToo() = java.util.concurrent.locks.LockSupport.parkNanos(2_000_000_000)",
                // review-026 B2: a duration and a time unit by their qualified names.
                "fun graceParsed() = java.time.Duration.parse(\"PT2S\")", "fun graceBetween(a: Instant, b: Instant) = java.time.Duration.between(a, b)",
                "fun graceUnit() = java.util.concurrent.TimeUnit.SECONDS"),
            Shape.KIND to listOf("kp-daemon", "kp-seat-api") to listOf("fun assigned(kind: String) = kind == \"assign\"", "fun begun(kind: String) = Regex(\"started|stop\").matches(kind)"),
            Shape.NUMBER to listOf("kp-engine", "kp-seat-api") to listOf("val most = 7", "val mostToo = 0x7"),
            Shape.CONCLUSION to listOf("kp-driver", "kp-seat-api") to listOf("val stalled = true", "val isStalled = true"),
            Shape.ANSWERER to listOf("kp-driver", "kp-daemon") to listOf("fun reads(tool: String) = tool == \"Read\"", "fun readsToo(tool: String) = Regex(\"Read|Glob\").matches(tool)"),
        )
        val missed = planted.flatMap { (at, forms) ->
            val (shape, modules) = at
            modules.flatMap { module -> forms.filter { form -> scanText("$module/src/main/kotlin/Planted.kt", "package planted\n\n$form\n", names).none { it.shape == shape && it.line == 3 } }.map { "$shape in $module: $it" } }
        }
        missed shouldBe emptyList()
    }

    "the check lists each timed wait of issue #53 — a condition's two, a selector's, a socket's — and an observation equal to a value of the rules' caps or engagement, written as `==`, as `!=` and as a `when` over a difference; each planted in two modules it covers, kp-ops among them" {
        // S3.8 T1 (review-026's follow-ups, Collite/keporkak#53; D-98): the forms tests/test_s3_8_after_the_first_wave.py
        // plants in a copy of the checkout, each as a main source of its module.
        val planted = mapOf(
            Shape.TIMER to listOf(
                "fun graceNanos(lock: java.util.concurrent.locks.Lock) = lock.newCondition().awaitNanos(2_000_000_000)",
                "fun graceUntil(condition: java.util.concurrent.locks.Condition, until: java.util.Date) = condition.awaitUntil(until)",
                "fun graceSelect(selector: java.nio.channels.Selector, millis: Long) = selector.select(millis)",
                "fun graceSocket(socket: java.net.Socket, millis: Int) { socket.soTimeout = millis }",
            ),
            Shape.COMPARISON to listOf(
                "fun atTheCap(blocks: Long, cap: Long) = blocks == cap",
                "fun belowTheGate(blocks: Long, rules: RuleSet) = blocks != rules.engagement.caps[\"stop_gate_blocks\"]",
                "fun atTheGate(used: Long, stopGateBlocks: Long) = when (used.minus(stopGateBlocks)) { 0L -> true; else -> false }",
            ),
        )
        val missed = listOf("kp-daemon", "kp-ops").flatMap { module ->
            planted.flatMap { (shape, forms) ->
                forms.filter { form -> scanText("$module/src/main/kotlin/Planted.kt", "package planted\n\n$form\n", names).none { it.shape == shape && it.line == 3 } }
                    .map { "$shape in $module: $it" }
            }
        }

        missed shouldBe emptyList()
    }

    "an equality is a comparison only with a value of the caps or engagement on a side — not with another name, a word that only begins like one, or a cap inside a lambda beside it; a `select` with no timeout is no timed wait (S3.8 T2)" {
        fun found(body: String) = scanText("kp-daemon/src/main/kotlin/kp/daemon/Probe.kt", "fun probe() {\n$body\n}\n", names)
            .filter { it.shape == Shape.COMPARISON || it.shape == Shape.TIMER }.map { it.code }

        found(
            listOf(
                "val same = blocks == seen",
                "val other = name != expected",
                "val full = capacity == used",
                "val inside = run { cap } == seen",
                "val key = selector.select()",
            ).joinToString("\n"),
        ) shouldBe emptyList()
        found("val at = blocks.equals(stopGateCap)\nval over = when (blocks) { cap -> true; else -> false }") shouldBe
            listOf("equals(stopGateCap)", "when (blocks) cap")
    }

    "the list's own faults fail the check: a finding no entry excuses, an entry with no reason, one for the stop gate's cap, one the sources no longer need" {
        val listed = names.exceptions
        val one = listed.first { it.shape == Shape.TIMER.id }
        val faults = listOf(
            listed - one,
            listed.map { if (it === one) it.copy(reason = "") else it },
            listed + Excused("kp-seat-api/src/main/kotlin/kp/seatapi/StopGate.kt", Shape.NUMBER.id, "StopGate.route", "${names.stopGateCap}", "${names.stopGateCap}", "a reason"),
            listed + one.copy(code = one.code + " // gone"),
        ).map { scan.problems(it) }
        // Each fault beside what the list as written yields — nothing on master — so that the spec reads the faults alone.
        val baseline = scan.problems(listed).toSet()

        faults.map { problems -> (problems - baseline).map { it.substringBefore(':') } } shouldBe listOf(
            listOf("not on the allow-list"),
            listOf("an exception with no reason"),
            listOf("an exception for the stop gate's cap, ${names.stopGateCap}, which no number in kp-seat-api may be", "an exception the sources no longer need"),
            listOf("an exception the sources no longer need"),
        )
    }

    "an edit to a line the list excuses, beside its finding's own code, fails the check with its file and line — in the daemon and in the driver" {
        // review-026 B1: a grace added to a timer's line, as S2.4's was — on the first line of each module that the list
        // excuses for a timer and that declares a value by a call on a line of its own.
        val baseline = scan.problems().toSet()
        val edits = listOf("kp-daemon", "kp-driver").map { module ->
            val entry = names.exceptions.first { it.path.startsWith("$module/") && it.shape == Shape.TIMER.id && Regex("""val \w+ = .*\)""").matches(it.line) }
            val lines = File(root, entry.path).readLines()
            val at = lines.indexOfFirst { collapse(it) == entry.line }
            val edited = lines.mapIndexed { i, line -> if (i == at) "$line + 2_000" else line }.joinToString("\n", postfix = "\n")
            entry to (at + 1) to (Scan(root, names, mapOf(entry.path to edited)).problems() - baseline).map { it.lines().first() }
        }

        edits.forEach { (at, problems) ->
            val (entry, line) = at
            problems shouldContain "not on the allow-list: ${entry.path}:$line: ${entry.shape} in ${entry.inside}: ${entry.code}"
            problems shouldContain "an exception the sources no longer need: $entry"
        }
    }

    "the check finds a comparison in every form review-008 listed, and takes no type argument, arrow, comment or string for one" {
        // review-008, B1 and its re-review: forms the regex scans missed, then the ones a line-based scan read wrongly.
        val compares = listOf(
            "if (silent > Duration.ofMinutes(20)) return fact(SeatFacts.SILENT_FOR, duration(silent), \"over the watchdog's limit\")",
            "if (silent >= limit) return null",
            "if (Duration.between(heard, clock.instant()) > Duration.ofMinutes(20)) return fact(SeatFacts.SILENT_FOR, duration(Duration.ZERO), \"over\")",
            "Duration.between(heard, clock.instant()) >= limit", "f(g()) > 5", "(now - heard) > limit", "samples[0] > limit",
            "lastHeard!! < since", "silent > (limit)", "if (silent<limit || used>=cap) w", "x = listOf(silent<limit, used>cap)",
            "if (silent-->limit) w", "val over = used.compareTo(limit) > 0", "when (Long.compare(used, cap)) { 1 -> w }",
            "if (Duration.between(heard, now).isAfter(limit)) x", "if ((limit - silent).isNegative) x", "val capped = silent.coerceAtMost(limit)",
            "val most = maxOf(used, limit)", "val most = max(used, limit)", "val most = listOf(used, limit).max()",
            "val first = listOf(used, limit).sorted().first()", "if (silent in Duration.ZERO..limit) y", "for (i in 0 until n) z",
            "val c = '\"'; if (a > b) x", "val s = \"\\\"}\"; if (a > b) x", "val s = \"\${if (silent > limit) \"over\" else \"\"}\"",
            "if (silent.toMinutes() / 20 != 0L) x", "if (LongRange(20, Long.MAX_VALUE).contains(silent)) x",
        )
        fun comparisons(body: String) = scanText("kp-driver/src/main/kotlin/kp/driver/Probe.kt", "fun probe() {\n$body\n}\n", names)
            .filter { it.shape == Shape.COMPARISON }
        // A form the parser cannot read fails the check by its parse error: `listOf(silent<limit, used>cap)` is no Kotlin.
        compares.filter { form -> runCatching { comparisons(form).isEmpty() }.getOrDefault(false) } shouldBe emptyList()
        runCatching { comparisons("x = listOf(silent<limit, used>cap)") }.exceptionOrNull()?.message.orEmpty() shouldContain "a parse error"

        comparisons(
            listOf(
                "val open: MutableMap<String, Pair<Instant, List<Long>>> = mutableMapOf()",
                "val x = when (fact) { else -> Outcome.Failed(\"x\") }",
                "open.forEach { (id, at) -> note(id, at) }",
                "val f = { value: T, serializer: KSerializer<T> -> x }",
                "val asking = ConcurrentHashMap.newKeySet<Deferred<Answer>>()",
                "val onNote: (String) -> Unit = { }",
                "val line = \"silent > limit, 0..9, x.max()\" // used >= limit, in a comment",
                "val c = '>'",
                "/** silent > limit, in a comment */",
                "/* a block comment /* nested */ silent > limit */",
            ).joinToString("\n"),
        ) shouldBe emptyList()
    }
})
