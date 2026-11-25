"""KP/P3/S3.11 — after the third wave's reviews, T8's slices of the Python suite (for the next developer).

A standing check that no spec and no Python test writes out a line number of a `pool/` file (Collite/keporkak#69, 1: S3.12's
one-time search, kept); and `scripts/check-rules.py` on a persona without frontmatter (Collite/keporkak#67, 6).
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import ROOT, copy_of_the_checkout  # noqa: E402

# How a problem names the line of a pool/ file — `pool/offices.yaml: … (line 60)`, or `offices.yaml:60` — written out.
POOL_FILE = r"(?:offices|budgets|roster)\.yaml"
POOL_LINE = re.compile(POOL_FILE + r"""[^"\n]*\(line [0-9]+\)|""" + POOL_FILE + r":[0-9]+")


def written_out(root):
    """Each line of a spec (`*.kt`) or a Python test (`tests/*.py`) in [root]'s working tree that writes out a line number of
    a pool/ file, as `<path>:<line>: <text>` — what git lists, tracked or not, and does not ignore."""
    listed = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.kt", "tests/*.py"],
                            cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()
    found = []
    # This file writes its examples out: it is the check, and no spec of the pool.
    for path in (each for each in listed if each != f"tests/{pathlib.Path(__file__).name}"):
        file = pathlib.Path(root) / path
        if not file.is_file():
            continue
        for number, line in enumerate(file.read_text(errors="replace").splitlines(), 1):
            if POOL_LINE.search(line):
                found.append(f"{path}:{number}: {line.strip()}")
    return found


class TheStandingChecks(unittest.TestCase):
    def test_no_spec_and_no_python_test_writes_out_a_line_number_of_a_pool_file_they_read_it_from_the_file(self):
        self.assertEqual(written_out(ROOT), [], "read the line from the file — kp-daemon's PoolText.lineOf — never write it out")

    def test_the_check_finds_a_line_number_written_out_in_a_spec_and_in_a_python_test_and_nothing_where_it_is_read(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            subprocess.run(["git", "init", "--quiet"], cwd=root, check=True)
            (root / "kp-x/src/test/kotlin").mkdir(parents=True)
            (root / "tests").mkdir()
            (root / "kp-x/src/test/kotlin/PlantedSpec.kt").write_text(
                'val said = "pool/offices.yaml: github: seats.codr: no persona declares the role \'codr\' (line 60)"\n'
                'val read = "pool/offices.yaml: github: seats.codr (line ${lineOf(pool, CODER)})"\n')
            (root / "tests/test_planted.py").write_text('SAID = "pool/budgets.yaml:12: slots"\n')

            self.assertEqual(written_out(root), [
                "kp-x/src/test/kotlin/PlantedSpec.kt:1: " + 'val said = "pool/offices.yaml: github: seats.codr: no persona declares the role \'codr\' (line 60)"',
                'tests/test_planted.py:1: SAID = "pool/budgets.yaml:12: slots"',
            ])


class ThePersonasCheck(unittest.TestCase):
    def test_a_persona_without_frontmatter_is_a_fail_line_that_names_its_file_and_every_other_check_still_runs(self):
        copy = copy_of_the_checkout(self)
        (copy / "agents/planted").mkdir()
        (copy / "agents/planted/AGENT.md").write_text("# A persona with no frontmatter\n\nIt says nothing of its role.\n")

        run = subprocess.run([str(copy / "build/python/bin/python"), "scripts/check-rules.py"], cwd=copy, capture_output=True, text=True, timeout=600)

        self.assertNotIn("Traceback", run.stdout + run.stderr)
        self.assertIn("FAIL agents/planted/AGENT.md: no frontmatter — the persona's first line is not '---', or no '---' line closes it", run.stdout)
        self.assertIn("RESULT: FAIL (1 failures)", run.stdout)
        self.assertEqual(run.returncode, 1)


if __name__ == "__main__":
    unittest.main()
