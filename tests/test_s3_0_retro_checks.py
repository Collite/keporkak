"""S3.0 — the checks P2's retro asked for: T1's specs on the syntax-tree check, `just owed`, `just spec`, the default spec
timeout, `just build` past a failing module and `just mutant`'s clean exit (Bora reads these).

Seam: the recipes and the check themselves, run on a throwaway copy of the checkout under build/, as S2.8's are. The
check is `kp-rules`' `DecidesNothingSpec`, run through `just spec`; it reads the main sources of kp-driver, kp-daemon,
kp-seat-api and kp-engine, the rules and the seat's permissions data, and the allow-list
`build-support/decides-nothing.yaml`.
"""
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import copy_of_the_checkout, findings, just, plant, working_in  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[1]
ALLOW_LIST = "build-support/decides-nothing.yaml"

# Each of T3's shapes, planted in two of the modules the check covers, each in two forms: the one a regex scan listed,
# then one it did not — and, for a comparison and a timer, each form review-026 (B2) found the check missed.
SHAPES = {
    "comparison": (("kp-driver", "kp-daemon"), [
        "fun over(heard: Instant, clock: Clock): Boolean = Duration.between(heard, clock.instant()) > Duration.ofMinutes(20)",
        "fun overToo(heard: Instant, clock: Clock): Boolean = Duration.between(heard, clock.instant()).toMinutes() / 20 != 0L",
        "fun overWhen(used: Long, cap: Long) = when (used - cap) { in 1L.rangeTo(Long.MAX_VALUE) -> true; else -> false }",
        "fun overWhenVal(used: Long, cap: Long) = when (val d = used - cap) { 0L -> false; else -> true }",
        "fun overTakeIf(used: Long, cap: Long) = used.takeIf { it in cap.inc().rangeTo(Long.MAX_VALUE) }",
        "fun overUntil(used: Long, cap: Long) = used.until(cap).isEmpty()",
        "fun overWhenIn(used: Long, cap: Long, over: Set<Long>) = when (used - cap) { in over -> true; else -> false }",
    ]),
    "timer": (("kp-driver", "kp-daemon"), [
        "fun grace() = Thread.sleep(2_000)",
        "fun graceToo() = java.util.concurrent.locks.LockSupport.parkNanos(2_000_000_000)",
        "fun graceParsed() = java.time.Duration.parse(\"PT2S\")",
        "fun graceBetween(a: Instant, b: Instant) = java.time.Duration.between(a, b)",
        "fun graceUnit() = java.util.concurrent.TimeUnit.SECONDS",
    ]),
    "kind": (("kp-daemon", "kp-seat-api"), [
        "fun assigned(kind: String) = kind == \"assign\"",
        "fun begun(kind: String) = Regex(\"started|stop\").matches(kind)",
    ]),
    "number": (("kp-engine", "kp-seat-api"), [  # not the stop gate's cap, which no exception may excuse there
        "val most = 7",
        "val mostToo = 0x7",
    ]),
    "conclusion": (("kp-driver", "kp-seat-api"), [
        "val stalled = true",
        "val isStalled = true",
    ]),
    "answerer": (("kp-driver", "kp-daemon"), [
        "fun reads(tool: String) = tool == \"Read\"",
        "fun readsToo(tool: String) = Regex(\"Read|Glob\").matches(tool)",
    ]),
}
def grace_on_an_excused_line(copy, module):
    """review-026 B1: a grace added to the first line of [module] the list excuses for a timer that declares a value by a
    call on a line of its own, as S2.4's was; returns its path, line number, the entry, and the file as it was."""
    listed = yaml.safe_load((copy / ALLOW_LIST).read_text())["exceptions"]
    path, entry = next((path, entry) for path, entries in listed.items() if path.startswith(module + "/") for entry in entries
                       if entry["shape"] == "timer" and re.fullmatch(r"val \w+ = .*\)", entry["line"]))
    was = (copy / path).read_text()
    lines = was.split("\n")
    at = next(i for i, line in enumerate(lines) if " ".join(line.split()) == entry["line"])
    lines[at] += " + 2_000"
    (copy / path).write_text("\n".join(lines))
    return path, at + 1, entry, was


class TheChecksP2sRetroAskedFor(unittest.TestCase):
    def test_the_check_fails_on_each_shape_planted_in_two_modules_in_two_forms_and_on_an_edit_to_an_excused_line_and_passes_once_each_line_is_listed_with_a_reason(self):
        copy = copy_of_the_checkout(self)
        planted = {}
        for shape, (modules, forms) in SHAPES.items():
            for module in modules:
                path, numbers = plant(copy, module, "Planted" + shape.capitalize(), forms)
                labels = ["listed", "unlisted"] + [f"review-026 B2, form {n}" for n in range(1, len(forms) - 1)]
                planted.update({(shape, module, form): (path, number) for form, number in zip(labels, numbers)})
        graced = [grace_on_an_excused_line(copy, module) for module in ("kp-daemon", "kp-driver")]

        red = just(copy, "spec", "DecidesNothingSpec")
        found = findings(red.stdout)
        reported = {(path, int(line), shape) for path, line, shape, *_ in found}

        self.assertNotEqual(red.returncode, 0, red.stdout[-3000:])
        self.assertEqual(sorted(key for key, (path, line) in planted.items() if (path, line, key[0]) not in reported), [], red.stdout[-6000:])
        # An edit to a line the list excuses, outside its finding's own code, is a finding no entry excuses, by file and line.
        for path, line, entry, _ in graced:
            self.assertIn((path, str(line), "timer", entry["in"], entry["code"]), {f[:5] for f in found}, red.stdout[-6000:])
            self.assertIn(f"an exception the sources no longer need: {path}: timer in {entry['in']}: {entry['code']}", red.stdout)
        for path, _, _, was in graced:
            (copy / path).write_text(was)

        # Every finding in a planted file listed with its reason: green — master's own findings are on the list already.
        listed = yaml.safe_load((copy / ALLOW_LIST).read_text())
        for path, _, shape, inside, code, line in {f for f in found if "/planted/" in f[0]}:
            listed["exceptions"].setdefault(path, []).append({"shape": shape, "in": inside, "code": code, "line": line, "reason": "planted by the spec, listed"})
        (copy / ALLOW_LIST).write_text(yaml.safe_dump(listed, allow_unicode=True, sort_keys=False, width=200))
        green = just(copy, "spec", "DecidesNothingSpec")

        self.assertEqual(green.returncode, 0, green.stdout[-6000:])

        # An entry without a reason fails, naming it.
        entry = listed["exceptions"][planted[("timer", "kp-daemon", "unlisted")][0]][-1]
        entry["reason"] = ""
        (copy / ALLOW_LIST).write_text(yaml.safe_dump(listed, allow_unicode=True, sort_keys=False, width=200))
        reasonless = just(copy, "spec", "DecidesNothingSpec")

        self.assertNotEqual(reasonless.returncode, 0)
        self.assertIn("no reason", reasonless.stdout)
        self.assertIn(entry["code"], reasonless.stdout)

    def test_just_owed_lists_a_decision_marked_for_the_rulebook_that_no_changelog_line_names_and_not_one_a_line_names(self):
        folder = pathlib.Path(tempfile.mkdtemp(prefix="s3.0-owed-", dir=ROOT / "build"))
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        log = folder / "00-control-room.md"
        log.write_text(
            "## 5. Decisions\n\n"
            "- 2026-11-20 · **D-7** · **A ruling a seat must read.** It changes how a coder stops. · Tier C. → rulebook: (1).\n"
            "- 2026-11-20 · **D-8** · **Another ruling a seat must read.** It changes how a reviewer reads Found lines.\n"
            "  Bora 2026-11-20: \"yes\". → rulebook\n"
            "- 2026-11-20 · **D-9** · **A ruling about the plan only.** No seat reads it. · Tier B.\n"
            "- 2026-11-20 · **D-70** · **A ruling whose number begins with another's.** → rulebook\n\n"
            "## 6. Next\n")
        changelog = folder / "CHANGELOG.md"
        changelog.write_text("# Rulebook changelog\n\n- 2026-11-20 · EX-2 · SC-2 narrowed. · KP D-7, D-70\n")

        owed = subprocess.run([sys.executable, str(ROOT / "build-support/owed.py"), str(log), "--changelog", str(changelog)], text=True, capture_output=True)

        self.assertEqual(owed.returncode, 1, owed.stdout + owed.stderr)
        self.assertEqual(re.findall(r"\bD-\d+\b", owed.stdout), ["D-8"], owed.stdout)

        changelog.write_text(changelog.read_text() + "- 2026-11-20 · RV-9 · A reviewer reads each choice: line. · KP D-8\n")
        paid = subprocess.run([sys.executable, str(ROOT / "build-support/owed.py"), str(log), "--changelog", str(changelog)], text=True, capture_output=True)

        self.assertEqual(paid.returncode, 0, paid.stdout + paid.stderr)
        self.assertEqual(re.findall(r"\bD-\d+\b", paid.stdout), [], paid.stdout)

    def test_a_spec_that_never_ends_fails_by_the_default_timeout_in_every_module_that_has_specs(self):
        copy = copy_of_the_checkout(self)
        modules = sorted(path.parent.parent.parent.name for path in copy.glob("kp-*/src/test/kotlin"))
        for module in modules:
            spec = copy / module / "src/test/kotlin/kp/planted/NeverEndsSpec.kt"
            spec.parent.mkdir(parents=True, exist_ok=True)
            spec.write_text(
                "package kp.planted\n\nimport io.kotest.core.spec.style.StringSpec\n\n"
                "class NeverEndsSpec : StringSpec({\n"
                "    \"a spec that never ends, blocking its thread\" { Thread.sleep(Long.MAX_VALUE) }\n"
                "    \"a spec that never ends, suspended\" { kotlinx.coroutines.awaitCancellation() }\n"
                "})\n")

        run = just(copy, "spec", "NeverEndsSpec", environment={"KP_SPEC_TIMEOUT": "10s"})

        self.assertGreaterEqual(len(modules), 8)
        self.assertNotEqual(run.returncode, 0)
        ran = re.findall(r"^(kp-[\w-]+) · NeverEndsSpec — (.+) — (\w+)", run.stdout, re.MULTILINE)
        self.assertEqual(sorted({(module, case, verdict) for module, case, verdict in ran}),
                         sorted({(module, case, "failed") for module in modules for case in
                                 ("a spec that never ends, blocking its thread", "a spec that never ends, suspended")}), run.stdout[-6000:])
        # The suspended spec ends by Kotest's timeout; the blocked one by the interrupt that timeout sends its thread.
        for module in modules:
            self.assertRegex(run.stdout, rf"{module} · NeverEndsSpec — a spec that never ends, suspended — failed\n\s+\S*TestTimeoutException: Test '[^']+' did not complete within 10s")
            self.assertRegex(run.stdout, rf"{module} · NeverEndsSpec — a spec that never ends, blocking its thread — failed\n\s+java.lang.InterruptedException")

    def test_just_spec_runs_the_specs_its_filter_names_and_no_other_and_fails_when_it_names_none(self):
        copy = copy_of_the_checkout(self)

        # A leading wildcard: Gradle's `--tests` keeps Kotest to a class it names in full, but not to one a pattern names.
        one = just(copy, "spec", "*SkeletonSpec")
        none = just(copy, "spec", "NoSuchSpecAnywhere")

        self.assertEqual(one.returncode, 0, one.stdout[-4000:])
        ran = re.findall(r"^(kp-[\w-]+) · (\w+) — (.+) — (\w+)$", one.stdout, re.MULTILINE)
        self.assertGreater(len(ran), 1, one.stdout[-4000:])
        self.assertEqual({(module, spec) for module, spec, _, _ in ran}, {("kp-model", "SkeletonSpec")})
        self.assertEqual({verdict for *_, verdict in ran}, {"passed"})
        self.assertNotEqual(none.returncode, 0)
        self.assertIn("names no spec", none.stdout)

    def test_just_build_goes_on_past_the_first_module_whose_specs_fail_and_with_two_failing_names_both(self):
        copy = copy_of_the_checkout(self)
        for module, package in (("kp-model", "kp.model"), ("kp-log", "kp.log")):
            spec = copy / module / "src/test/kotlin" / package.replace(".", "/") / "PlantedFailingSpec.kt"
            spec.write_text(
                f"package {package}\n\nimport io.kotest.core.spec.style.StringSpec\nimport io.kotest.matchers.shouldBe\n\n"
                "class PlantedFailingSpec : StringSpec({\n    \"a planted spec that fails\" { 1 shouldBe 2 }\n})\n")

        run = just(copy, "build")

        self.assertNotEqual(run.returncode, 0)
        self.assertIn("> Task :kp-model:test FAILED", run.stdout, run.stdout[-4000:])
        self.assertIn("> Task :kp-log:test FAILED", run.stdout, run.stdout[-4000:])

    def test_just_mutant_killed_while_a_spec_of_its_run_is_running_leaves_nothing_under_build_mutant(self):
        copy = copy_of_the_checkout(self)
        subprocess.run(["git", "init", "--quiet"], cwd=copy, check=True)  # mutant.py copies what git lists of its checkout
        patch = copy / "build/a-comment.patch"
        patch.write_text("--- a/kp-model/src/main/kotlin/kp/model/Ids.kt\n+++ b/kp-model/src/main/kotlin/kp/model/Ids.kt\n"
                         "@@ -1,3 +1,4 @@\n package kp.model\n \n+// a comment\n import kotlinx.serialization.Serializable\n")
        # review-026 B4: a spec of the run says it has begun, then runs on until the run is killed.
        began = copy / "build/a-spec-began"
        (copy / "kp-model/src/test/kotlin/kp/model/StillRunningSpec.kt").write_text(
            "package kp.model\n\nimport io.kotest.core.spec.style.StringSpec\nimport java.io.File\n\n"
            "class StillRunningSpec : StringSpec({\n"
            f"    \"a spec still running when its run is killed\" {{ File({json.dumps(str(began))}).writeText(\"began\"); Thread.sleep(600_000) }}\n"
            "})\n")
        for kill in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            with self.subTest(kill=kill.name):
                began.unlink(missing_ok=True)
                mutant = subprocess.Popen([sys.executable, str(copy / "build-support/mutant.py"), str(patch), "StillRunningSpec"], cwd=copy,
                                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                started = time.monotonic()
                while not began.exists() and mutant.poll() is None:
                    self.assertLess(time.monotonic() - started, 600, "no spec of the run began")
                    time.sleep(0.2)
                if mutant.poll() is not None:
                    self.fail("mutant ended before a spec of its run began:\n" + mutant.communicate()[0][-4000:])
                running = working_in(copy / "build/mutant")
                mutant.send_signal(kill)
                output, _ = mutant.communicate(timeout=120)

                self.assertGreater(len(running), 1, running)  # the Gradle build and its test worker were at work in the copy
                self.assertEqual(mutant.returncode, 128 + kill, output)
                time.sleep(2)  # anything still writing into the copy would show now
                self.assertEqual([p.name for p in (copy / "build/mutant").iterdir()] if (copy / "build/mutant").exists() else [], [], output)
                self.assertEqual(working_in(copy / "build/mutant"), [], output)

if __name__ == "__main__":
    unittest.main()
