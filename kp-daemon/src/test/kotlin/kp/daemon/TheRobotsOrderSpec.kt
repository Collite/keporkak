package kp.daemon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * KP/P3/S3.4 T1 — a robot file states its `order:`, whole and its own (contracts.md §8, Amendment 45; D-118). The seam:
 * both validators on a copy of the checkout's rule files with one robot file edited — `check-rules.py`, the copy's own,
 * and the daemon's load — each naming the file.
 */
class TheRobotsOrderSpec : StringSpec({
    fun copy(edit: (Path) -> Unit): Path = TestOffice.copyOfTheRules().also { edit(it.resolve("robots")) }

    /** What each validator says of the copy: the walker's `FAIL` lines, then the daemon's problems. */
    fun said(rules: Path): Pair<List<String>, List<String>> = walkerOn(rules) to daemonLoad(rules)

    "master's four files load with 1, 2, 3, 4" {
        said(copy { }) shouldBe (emptyList<String>() to emptyList())
    }

    "a robot file with no order: is refused by both, naming the file" {
        said(copy { it.resolve("pool.yaml").rewrite { text -> text.replace("\norder: 2\n", "\n") } }) shouldBe
            (listOf("pool.yaml: a robot file states its order: a whole number") to listOf("robots/pool.yaml: pool.yaml: a robot file states its order: a whole number"))
    }

    "a robot file whose order is not whole is refused by both, naming the file" {
        said(copy { it.resolve("qa.yaml").rewrite { text -> text.replace("\norder: 3\n", "\norder: 2.5\n") } }) shouldBe
            (listOf("qa.yaml: order must be a whole number") to listOf("robots/qa.yaml: qa.yaml: order must be a whole number"))
    }

    "two files with one number are refused by both, each naming the other" {
        said(copy { it.resolve("retro.yaml").rewrite { text -> text.replace("\norder: 4\n", "\norder: 3\n") } }) shouldBe (
            listOf("qa.yaml: order 3 is also retro.yaml's: each robot's order is its own", "retro.yaml: order 3 is also qa.yaml's: each robot's order is its own") to
                listOf("robots/qa.yaml: order 3 is also robots/retro.yaml's: each robot's order is its own",
                    "robots/retro.yaml: order 3 is also robots/qa.yaml's: each robot's order is its own"))
    }
})
