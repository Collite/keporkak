"""check-rules.py over pool/: the cases behind the S0.3 specs, for the next developer."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PoolValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.pool = Path(self.temp.name) / "pool"
        shutil.copytree(ROOT / "pool", self.pool)

    def edit(self, name, old, new):
        path = self.pool / name
        self.assertIn(old, path.read_text())
        path.write_text(path.read_text().replace(old, new))

    def failures(self):
        result = subprocess.run([sys.executable, "scripts/check-rules.py", "--pool", str(self.pool)], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.stderr, "")
        return [line.removeprefix("FAIL ") for line in result.stdout.splitlines() if line.startswith("FAIL ")]

    def test_the_repository_pool_passes(self):
        self.assertEqual(self.failures(), [])

    def test_an_sdk_line_is_no_field_of_a_vendor(self):
        self.edit("roster.yaml", '    adapter_version: "0.106.0"\n', '    adapter_version: "0.106.0"\n    sdk: "com.agentclientprotocol:acp:0.34.0"\n')
        self.assertEqual(self.failures(), ["pool/roster.yaml: unknown field 'sdk'"])

    def test_a_level_that_points_at_a_reserved_model_fails(self):
        self.edit("roster.yaml", "  claude-fable-5-2: [principal]", "  claude-opus-5-5: [principal]")
        self.assertIn("pool/roster.yaml: level senior XL points at claude-opus-5-5, which is reserved", self.failures())

    def test_a_role_put_on_a_model_reserved_for_another_role_fails(self):
        self.edit("roster.yaml", "role_models: {}", "role_models: {coder: {model: claude-fable-5-2, effort: high}}")
        self.assertEqual(self.failures(), ["pool/roster.yaml: role_models coder runs claude-fable-5-2, which is reserved for principal"])

    def test_the_principal_may_be_put_on_its_reserved_model(self):
        self.edit("roster.yaml", "role_models: {}", "role_models: {principal: {model: claude-fable-5-2, effort: high}}")
        self.assertEqual(self.failures(), [])

    def test_a_level_that_is_not_a_line_of_the_order_fails(self):
        self.edit("roster.yaml", "XL: {vendor: claude, model: claude-opus-5-5, effort: max}", "XL: {vendor: claude, model: claude-opus-5-5, effort: ultra}")
        self.assertEqual(self.failures(), ["pool/roster.yaml: level senior XL is not a line of the order"])

    def test_a_level_without_one_of_the_four_sizes_fails(self):
        self.edit("roster.yaml", "    M: {vendor: claude, model: claude-haiku-5-5, effort: medium}\n", "")
        self.assertEqual(self.failures(), ["pool/roster.yaml: level junior must map exactly S, M, L, XL"])

    def test_a_models_pairs_must_be_adjacent_model_before_effort(self):
        self.edit("roster.yaml", "  - {vendor: claude, model: claude-haiku-5-5, effort: max}\n", "")
        self.edit("roster.yaml", "  - {vendor: claude, model: claude-sonnet-5-5, effort: max}\n", "  - {vendor: claude, model: claude-sonnet-5-5, effort: max}\n  - {vendor: claude, model: claude-haiku-5-5, effort: max}\n")
        self.assertEqual(self.failures(), ["pool/roster.yaml: order line 9: model before effort — claude-haiku-5-5's pairs must be adjacent"])

    def test_a_repeated_pair_fails(self):
        self.edit("roster.yaml", "  - {vendor: claude, model: claude-opus-5-5, effort: low}\n", "  - {vendor: claude, model: claude-opus-5-5, effort: low}\n  - {vendor: claude, model: claude-opus-5-5, effort: low}\n")
        self.assertEqual(self.failures(), ["pool/roster.yaml: order line 11 repeats (claude-opus-5-5, low)"])

    def test_the_reserved_office_name_hand_fails(self):
        self.edit("offices.yaml", "  panda:\n", "  hand:\n")
        self.assertIn("pool/offices.yaml: 'hand' is not an office name (lower-case; 'hand' is reserved)", self.failures())

    def test_a_listener_off_the_loopback_fails(self):
        self.edit("offices.yaml", 'board: "127.0.0.1:7712"', 'board: "0.0.0.0:7712"')
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: listen.board must be 127.0.0.1:<port>"])

    def test_the_watch_period_is_a_positive_duration_with_no_other_field(self):
        # S2.4 (contracts.md §9, Amendment 22): how often the daemon reports its seats' facts.
        self.edit("offices.yaml", "watch: {every: 30s}", "watch: {every: 30}")
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: watch.every must be a duration such as 30s"])
        self.edit("offices.yaml", "watch: {every: 30}", "watch: {every: 0s, limit: 5m}")
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: watch.every must be a duration such as 30s", "pool/offices.yaml: panda: unknown field 'watch.limit'"])

    def test_an_unquoted_off_is_the_word_not_a_boolean(self):
        self.edit("offices.yaml", 'computer_use: "off"', "computer_use: off")
        self.assertEqual(self.failures(), [])

    def test_a_qa_vendor_outside_the_roster_fails(self):
        self.edit("offices.yaml", "qa: {vendor: claude}", "qa: {vendor: codex}")
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: qa vendor 'codex' is not a roster vendor"])

    def test_warnings_must_ascend(self):
        self.edit("budgets.yaml", "warn: [0.7, 0.9]", "warn: [0.9, 0.7]")
        self.assertEqual(self.failures(), ["pool/budgets.yaml: warn must be ascending fractions between 0 and 1"])

    def test_a_pool_value_the_rules_use_must_resolve(self):
        self.edit("budgets.yaml", "warn: [0.7, 0.9]", "warn: [0.7]")
        self.assertEqual(self.failures(), ["pool: {budget: warn.1} used by the rules does not resolve"])

    def test_the_project_is_one_checkout_with_a_folder_per_effort_inside_it(self):
        self.edit("offices.yaml", 'efforts: {KP: ".", TOY: toy}', 'efforts: {KP: ".", TOY: ../toy}')
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: project effort 'TOY' must be an effort id with a folder inside the checkout"])
        self.edit("offices.yaml", "project: {checkout: ~/Dev/keporkak/project, efforts: {KP: \".\", TOY: ../toy}}", "project: {KP: ~/Dev/keporkak/project}")
        self.assertEqual(self.failures(), ["pool/offices.yaml: panda: project must name a checkout and a folder per effort", "pool/offices.yaml: panda: unknown field 'project.KP'"])

    def test_a_missing_pool_file_fails_with_one_line(self):
        for name in ("roster.yaml", "offices.yaml", "budgets.yaml"):
            with self.subTest(name):
                moved = (self.pool / name).rename(self.pool.parent / name)
                self.assertEqual(self.failures(), [f"pool/{name}: missing"])
                moved.rename(self.pool / name)


if __name__ == "__main__":
    unittest.main()
