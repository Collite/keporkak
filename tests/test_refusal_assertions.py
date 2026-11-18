"""S2.8 T3 — `just rules` fails a spec file that asserts a refusal without its rule (build-support/check_refusal_assertions.py),
on a fixture checkout of one module; and passes on this checkout."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "build-support/check_refusal_assertions.py"
BY_STATUS = 'class QueueSpec : StringSpec({ "x" { asked.status shouldBe Status.REFUSED } })\n'
BY_RULE = 'class QueueSpec : StringSpec({ "x" { asked should refusedBy("qa-request", "{fact: list.task_exists}") } })\n'


class Fixture:
    def __init__(self, test, spec, listed=None):
        self.root = Path(tempfile.mkdtemp(prefix="refusals-", dir=ROOT / "build"))
        test.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        (self.root / "kp-mailroom/src/test/kotlin/kp/mailroom").mkdir(parents=True)
        (self.root / "kp-mailroom/src/test/kotlin/kp/mailroom/QueueSpec.kt").write_text(spec)
        (self.root / "build-support").mkdir()
        if listed is not None:
            (self.root / "build-support/refusals-asserted-otherwise.yaml").write_text(listed)

    def check(self):
        return subprocess.run([sys.executable, str(CHECK), "--root", str(self.root)], capture_output=True, text=True)


SPEC = "kp-mailroom/src/test/kotlin/kp/mailroom/QueueSpec.kt"


class RefusalsByTheirRule(unittest.TestCase):
    def test_a_spec_that_asserts_a_refusal_by_its_status_alone_and_is_not_on_the_list_fails(self):
        result = Fixture(self, BY_STATUS).check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(f"FAIL {SPEC}: asserts a refusal without refusedBy(rule, failed), and the list does not name it", result.stdout)

    def test_the_same_spec_passes_when_it_asserts_the_rule_or_is_listed_with_its_reason(self):
        by_rule = Fixture(self, BY_RULE).check()
        listed = Fixture(self, BY_STATUS, f"{SPEC}: the queue's own words are the subject\n").check()

        self.assertEqual(by_rule.returncode, 0, by_rule.stdout)
        self.assertEqual(listed.returncode, 0, listed.stdout)
        self.assertIn(f"  {SPEC} — the queue's own words are the subject", listed.stdout)

    def test_an_entry_with_no_reason_or_that_is_no_longer_needed_fails(self):
        no_reason = Fixture(self, BY_STATUS, f"{SPEC}: ''\n").check()
        not_needed = Fixture(self, BY_RULE, f"{SPEC}: it used to assert the status\n").check()
        gone = Fixture(self, BY_RULE, "kp-mailroom/src/test/kotlin/kp/mailroom/GoneSpec.kt: removed\n").check()

        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} has no reason", no_reason.stdout)
        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} asserts its refusals by refusedBy — it needs no entry", not_needed.stdout)
        self.assertIn("FAIL refusals-asserted-otherwise.yaml: kp-mailroom/src/test/kotlin/kp/mailroom/GoneSpec.kt is no spec file", gone.stdout)
        self.assertEqual([no_reason.returncode, not_needed.returncode, gone.returncode], [1, 1, 1])

    def test_this_checkout_asserts_every_refusal_by_its_rule_or_lists_why_not(self):
        result = subprocess.run([sys.executable, str(CHECK)], capture_output=True, text=True)

        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
