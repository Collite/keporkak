package kp.driver.home

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kp.driver.Outcome
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * S3.7 T4 — the builder's part for a seat's two programs (contracts.md §4.4, Amendment 35), at the seam S2.2 gives it: the
 * builder on master's personas, in a copy of the checkout a spec may change ([Sources]).
 */
class SeatProgramsSpec : FreeSpec({
    "every home's bin/ holds the seat's two programs as seat/ has them, each 0700, and nothing else" {
        Sources().use { sources ->
            val homes = ConfigHomes(sources.root, sources.seats)
            val first = homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS).let { (it as Outcome.Ok).value }
            first.programs.resolve("stray").writeText("left by someone\n")

            val home = homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS).let { (it as Outcome.Ok).value }

            home.programs shouldBe home.path.resolve("bin")
            home.programs.listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("gh", "git-credential-keporkak")
            for (program in home.programs.listDirectoryEntries()) {
                program.readText() shouldBe sources.root.resolve("seat/${program.name}").readText()
                PosixFilePermissions.toString(Files.getPosixFilePermissions(program)) shouldBe "rwx------"
            }
        }
    }

    "no home is built for a seat whose programs are missing or cannot be run" - {
        for (program in ConfigHomes.SEAT_PROGRAMS) {
            "seat/$program not executable: the builder names the file, and builds nothing" {
                Sources().use { sources ->
                    val file = sources.root.resolve("seat/$program")
                    Files.setPosixFilePermissions(file, Files.getPosixFilePermissions(file) - setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))

                    val built = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS)

                    built.shouldBeInstanceOf<Outcome.Failed>().reason shouldBe "program $program: seat/$program is not executable"
                    sources.seats.listDirectoryEntries().shouldBeEmpty()
                }
            }
            "seat/$program missing: the builder names the file, and builds nothing" {
                Sources().use { sources ->
                    Files.delete(sources.root.resolve("seat/$program"))

                    val built = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS)

                    built.shouldBeInstanceOf<Outcome.Failed>().reason shouldBe "program $program: seat/$program does not exist"
                    sources.seats.listDirectoryEntries().shouldBeEmpty()
                }
            }
        }
    }
})
