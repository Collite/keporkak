package kp.facts

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The build check of architecture.md §2 and Amendments 32, 33: `kp-facts` depends on `kp-model`, the Kotlin standard
 * library and the catalog's JSON library — GitHub answers in JSON (S3.2). Its test task hands this spec what its compile
 * classpath resolves to — every component, `module :<path>` or `library <group>:<name>` — so a module added to its build
 * file turns it red.
 */
class KpFactsDependenciesSpec : FreeSpec({
    "`kp-facts`' compile classpath holds `kp-model`, the Kotlin standard library and the catalog's JSON library, and no other module of this build" {
        val components = System.getProperty("kp.facts.compile-classpath").orEmpty().split(",").filter { it.isNotBlank() }

        components.filter { it.startsWith("module ") } shouldBe listOf("module :kp-model")
        components.filterNot { it.startsWith("module ") }.filterNot { it in STANDARD_AND_JSON }.shouldBeEmpty()
    }
})

/** The Kotlin standard library and the catalog's `serialization-json`, with what each brings: no module of this build. */
val STANDARD_AND_JSON = setOf(
    "library org.jetbrains.kotlin:kotlin-stdlib", "library org.jetbrains:annotations",
    "library org.jetbrains.kotlinx:kotlinx-serialization-json", "library org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    "library org.jetbrains.kotlinx:kotlinx-serialization-core", "library org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "library org.jetbrains.kotlinx:kotlinx-serialization-bom",
)
