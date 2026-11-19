"""S2.8 T5 — `just mutant <patch> <spec filter>`: what is an error and never red or green. Its red and its green run
Gradle on a copy and are shown in the stage's evidence; these need no Gradle run."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MUTANT = ROOT / "build-support/mutant.py"


def status():
    return subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def mutant(patch_text, spec_filter):
    with tempfile.NamedTemporaryFile("w", suffix=".patch", dir=ROOT / "build", delete=False) as patch:
        patch.write(patch_text)
    try:
        return subprocess.run([sys.executable, str(MUTANT), patch.name, spec_filter], cwd=ROOT, capture_output=True, text=True)
    finally:
        Path(patch.name).unlink()


A_COMMENT = """--- a/kp-engine/src/main/kotlin/kp/engine/Decision.kt
+++ b/kp-engine/src/main/kotlin/kp/engine/Decision.kt
@@ -1,3 +1,4 @@
 package kp.engine

+// a comment
 import kp.rules.MessageClass
"""

NOT_THERE = """--- a/kp-engine/src/main/kotlin/kp/engine/Decision.kt
+++ b/kp-engine/src/main/kotlin/kp/engine/Decision.kt
@@ -1,3 +1,4 @@
 package kp.engine.nowhere

+// a comment
 import kp.rules.MessageClass
"""


class JustMutant(unittest.TestCase):
    def test_a_patch_that_does_not_apply_is_an_error_of_its_own_and_the_working_tree_stays_as_it_was(self):
        before = status()

        result = mutant(NOT_THERE, "FoldDeterminismSpec")

        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("the patch does not apply", result.stdout)
        self.assertNotIn("red", result.stdout.split())
        self.assertNotIn("green", result.stdout.split())
        self.assertEqual(status(), before)
        self.assertEqual(list((ROOT / "build/mutant").glob("copy-*")), [])

    def test_a_filter_that_names_no_spec_is_an_error_of_its_own_and_nothing_runs(self):
        result = mutant(A_COMMENT, "NoSuchSpec*")

        self.assertEqual(result.returncode, 3, result.stdout)
        self.assertIn("names no spec of any module", result.stdout)
        self.assertEqual(list((ROOT / "build/mutant").glob("copy-*")), [])

    def test_a_missing_patch_is_the_error_of_a_patch_that_does_not_apply(self):
        result = subprocess.run([sys.executable, str(MUTANT), "build/no-such.patch", "FoldDeterminismSpec"], cwd=ROOT, capture_output=True, text=True)

        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("no patch at", result.stdout)


if __name__ == "__main__":
    unittest.main()
