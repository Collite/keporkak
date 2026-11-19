"""S2.8 T6 — the changelog check (build-support/check_changelog.py), on throwaway repositories: what it reads as a change
to the rules, what it exempts, and what counts as a line naming a decision."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import ThrowawayRepository  # noqa: E402

MASTER = {"rules/message-kinds.yaml": "kinds: {}\n", "rules/walks/h1.yaml": "steps: []\n", "rules/fixtures/a.yaml": "scenarios: []\n",
          "robots/pool.yaml": "rules: []\n", "docs/wow/30-execution.md": "# 30\n", "docs/wow/CHANGELOG.md": "# Rulebook changelog\n"}


def on_a_branch(test):
    repository = ThrowawayRepository(test, MASTER)
    repository.branch("feat/a")
    return repository


class TheChangelogCheck(unittest.TestCase):
    def test_walks_fixtures_and_the_changelog_itself_need_no_line(self):
        repository = on_a_branch(self)
        repository.commit({"rules/walks/h1.yaml": "steps: [1]\n", "rules/fixtures/a.yaml": "scenarios: [1]\n",
                           "docs/wow/CHANGELOG.md": "# Rulebook changelog\n\nA note.\n"})

        result = repository.check()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("no change to the rules", result.stdout)

    def test_an_edit_not_yet_committed_and_a_new_robot_not_yet_added_are_changes_too(self):
        repository = on_a_branch(self)
        (repository.path / "rules/message-kinds.yaml").write_text("kinds: {started: {}}\n")
        (repository.path / "robots/qa.yaml").write_text("rules: []\n")

        result = repository.check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("robots/qa.yaml, rules/message-kinds.yaml", result.stdout)

    def test_a_line_that_names_no_decision_does_not_count(self):
        repository = on_a_branch(self)
        repository.commit({"docs/wow/30-execution.md": "# 30\nEX-13 reworded.\n",
                           "docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-18 · EX-13 · Reworded. · KP\n"})

        result = repository.check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("docs/wow/30-execution.md", result.stdout)

    def test_an_old_line_reworded_is_no_new_line(self):
        master = dict(MASTER, **{"docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-14 · DEP-4 · Old words. · KP D-52\n"})
        repository = ThrowawayRepository(self, master)
        repository.branch("feat/a")
        repository.commit({"robots/pool.yaml": "rules: [POOL-1]\n",
                           "docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-14 · DEP-4 · New words. · KP D-52\n"})

        result = repository.check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("a reworded line is no new one", result.stdout)
        self.assertIn("robots/pool.yaml", result.stdout)

    def test_a_line_added_beside_a_reworded_one_counts(self):
        master = dict(MASTER, **{"docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-14 · DEP-4 · Old words. · KP D-52\n"})
        repository = ThrowawayRepository(self, master)
        repository.branch("feat/a")
        repository.commit({"robots/pool.yaml": "rules: [POOL-1]\n",
                           "docs/wow/CHANGELOG.md": "# Rulebook changelog\n\n- 2026-11-19 · POOL-1 · New. · KP D-81\n- 2026-11-14 · DEP-4 · New words. · KP D-52\n"})

        result = repository.check()

        self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_branch_with_no_master_to_compare_with_fails_and_says_what_to_fetch(self):
        repository = on_a_branch(self)
        repository.git("branch", "--quiet", "-D", "master")

        result = repository.check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("no master to compare with (origin/master or master)", result.stdout)
        self.assertIn("fetch-depth: 0", result.stdout)


if __name__ == "__main__":
    unittest.main()
