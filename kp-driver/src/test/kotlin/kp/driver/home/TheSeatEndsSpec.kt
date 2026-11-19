package kp.driver.home

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kp.driver.Outcome
import kp.model.SeatId
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * KP/P2/S2.7 — the seat ends: the builder's sentence of T1, at the seam S2.2 gives it — the builder on master's personas,
 * in a copy of the checkout a spec may change ([Sources]), and a seats' root of its own. The engine's and the
 * mailroom's sentences are `kp-mailroom`'s and `kp-daemon`'s.
 */
class TheSeatEndsSpec : FreeSpec({
    "no home is built for a seat whose hook scripts are missing or cannot be run" - {
        for (hook in Packaging.HOOKS) {
            "hooks/$hook not executable: the builder names the file, and builds nothing" {
                Sources().use { sources ->
                    val file = sources.root.resolve("hooks/$hook")
                    Files.setPosixFilePermissions(file, Files.getPosixFilePermissions(file) - setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))

                    val built = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS)

                    built.shouldBeInstanceOf<Outcome.Failed>().reason shouldBe "hook $hook: hooks/$hook is not executable"
                    sources.seats.listDirectoryEntries().shouldBeEmpty()
                }
            }
            "hooks/$hook missing: the builder names the file, and builds nothing" {
                Sources().use { sources ->
                    Files.delete(sources.root.resolve("hooks/$hook"))

                    val built = ConfigHomes(sources.root, sources.seats).build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS)

                    built.shouldBeInstanceOf<Outcome.Failed>().reason shouldBe "hook $hook: hooks/$hook does not exist"
                    sources.seats.listDirectoryEntries().shouldBeEmpty()
                }
            }
        }
        "an earlier home stays as it was when a hook can no longer be run" {
            Sources().use { sources ->
                val homes = ConfigHomes(sources.root, sources.seats)
                val home = homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS).let { (it as Outcome.Ok).value.path }
                val before = home.resolve("settings.json").readText()
                val deny = sources.root.resolve("hooks/deny")
                Files.setPosixFilePermissions(deny, Files.getPosixFilePermissions(deny) - PosixFilePermission.OWNER_EXECUTE)

                homes.build(SeatId("coder-1"), "coder", "keporkak", sources.places, MASTER_DEFAULTS).shouldBeInstanceOf<Outcome.Failed>()
                home.resolve("settings.json").readText() shouldBe before
            }
        }
    }
})
