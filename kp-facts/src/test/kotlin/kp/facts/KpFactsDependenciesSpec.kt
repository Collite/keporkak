package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The build check of architecture.md §2 and Amendment 32: `kp-facts` depends on `kp-model` and the Kotlin standard
 * library alone. Its test task hands this spec what its compile classpath resolves to — every component, `module
 * :<path>` or `library <group>:<name>` — so a module added to its build file turns it red.
 */
class KpFactsDependenciesSpec : FreeSpec({
    "`kp-facts`' compile classpath holds `kp-model` and the Kotlin standard library, and no other module of this build" {
        val components = System.getProperty("kp.facts.compile-classpath").orEmpty().split(",").filter { it.isNotBlank() }

        components.filter { it.startsWith("module ") } shouldBe listOf("module :kp-model")
        components.filterNot { it.startsWith("module ") }.filterNot { it == "library org.jetbrains.kotlin:kotlin-stdlib" || it == "library org.jetbrains:annotations" }
            .shouldBeEmpty()
    }
})
