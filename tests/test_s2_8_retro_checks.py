"""S2.8 — the checks P1's retro asked for: T1's specs on the build, the filter and the changelog (Bora reads these).

Seam: the recipes and the Gradle tasks themselves, run on the throwaway copy of the checkout under build/ that the specs
of S2.8, S3.0 and S3.8 share (S3.8 T6); the changelog check on a throwaway repository. The refusals by their rule and the projections of the state are kp-engine's
`RetroChecksSpec`.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import gradle, shared_copy, test_task_inputs, ThrowawayRepository  # noqa: E402


class TheChecksP1sRetroAskedFor(unittest.TestCase):
    def test_a_modules_tests_run_again_after_a_file_under_rules_robots_pool_or_agents_changed(self):
        inputs = test_task_inputs(shared_copy())
        one_of_each = {"rules/stage-lifecycle.yaml", "robots/pool.yaml", "pool/roster.yaml", "agents/coder/AGENT.md"}

        self.assertEqual(sorted(inputs), [
            ":kp-board:test", ":kp-daemon:hero", ":kp-daemon:test", ":kp-driver:test", ":kp-engine:test", ":kp-facts:test",
            ":kp-log:test", ":kp-mailroom:test", ":kp-model:test", ":kp-ops:test", ":kp-rules:test", ":kp-seat-api:test"])
        self.assertEqual({task: one_of_each - files for task, files in inputs.items()}, {task: set() for task in inputs})

    def test_a_test_run_that_its_filter_left_with_no_test_fails(self):
        copy = shared_copy()

        by_gradle = gradle(copy, ":kp-model:test", "--tests", "NoSuchSpec*")
        by_kotest = gradle(copy, ":kp-model:test", environment={"kotest_filter_tests": "no such test"})

        self.assertNotEqual(by_gradle.returncode, 0)
        self.assertIn("No tests found for given includes: [NoSuchSpec*]", by_gradle.stdout)
        self.assertNotEqual(by_kotest.returncode, 0)
        self.assertIn(":kp-model:test executed no test", by_kotest.stdout)

    def test_a_change_to_the_rules_with_no_changelog_line_that_names_its_decision_fails_the_check(self):
        repository = ThrowawayRepository(self, {"robots/pool.yaml": "rules: []\n", "docs/wow/CHANGELOG.md": "# Rulebook changelog\n"})
        repository.branch("feat/a-robot-edit")
        repository.commit({"robots/pool.yaml": "rules: [POOL-9]\n"})

        unnamed = repository.check()
        repository.commit({"docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-18 · EN-9 · The pool robot gains POOL-9. · KP D-78\n"})
        named = repository.check()

        self.assertEqual(unnamed.returncode, 1)
        self.assertIn("gains no line naming a decision", unnamed.stdout)
        self.assertIn("robots/pool.yaml", unnamed.stdout)
        self.assertEqual(named.returncode, 0, named.stdout)


if __name__ == "__main__":
    unittest.main()
