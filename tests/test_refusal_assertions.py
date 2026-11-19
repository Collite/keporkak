"""S2.8 T3 — `just rules` fails a spec that asserts a refusal without its rule (build-support/check_refusal_assertions.py),
line by line (review B1), on a fixture checkout of one module; and passes on this checkout."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHECK = ROOT / "build-support/check_refusal_assertions.py"
SPEC = "kp-mailroom/src/test/kotlin/kp/mailroom/QueueSpec.kt"
BY_STATUS_LINE = "asked.status shouldBe Status.REFUSED"
BY_STATUS = f'class QueueSpec : StringSpec({{\n    "x" {{\n        {BY_STATUS_LINE}\n    }}\n}})\n'
BY_RULE = 'class QueueSpec : StringSpec({\n    "x" {\n        asked should refusedBy("qa-request", "{fact: list.task_exists}")\n    }\n})\n'
BY_RULE_AND_BY_STATUS = """import kp.mailroom.Status.REFUSED
class QueueSpec : StringSpec({
    "x" {
        asked should refusedBy("qa-request", "{fact: list.task_exists}")
        other.status shouldBe Status.REFUSED
    }
})
"""


def listing(line, reason, spec=SPEC, times=1):
    quoted = lambda text: "'" + text.replace("'", "''") + "'"
    return f"{spec}:\n" + f"  - line: {quoted(line)}\n    reason: {quoted(reason)}\n" * times


class Fixture:
    def __init__(self, test, spec, listed=None):
        self.root = Path(tempfile.mkdtemp(prefix="refusals-", dir=ROOT / "build"))
        test.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        (self.root / "kp-mailroom/src/test/kotlin/kp/mailroom").mkdir(parents=True)
        (self.root / SPEC).write_text(spec)
        (self.root / "build-support").mkdir()
        if listed is not None:
            (self.root / "build-support/refusals-asserted-otherwise.yaml").write_text(listed)

    def check(self):
        return subprocess.run([sys.executable, str(CHECK), "--root", str(self.root)], capture_output=True, text=True)


def unlisted(number, line):
    return f"FAIL {SPEC}:{number}: asserts a refusal without refusedBy(rule, failed), and the list does not name the line — {line}"


class RefusalsByTheirRule(unittest.TestCase):
    def test_a_spec_that_asserts_a_refusal_by_its_status_alone_and_is_not_on_the_list_fails(self):
        result = Fixture(self, BY_STATUS).check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn(unlisted(3, BY_STATUS_LINE), result.stdout)

    def test_a_refusal_asserted_by_its_status_alone_fails_though_the_same_file_asserts_another_by_its_rule(self):
        result = Fixture(self, BY_RULE_AND_BY_STATUS).check()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertEqual([line for line in result.stdout.splitlines() if line.startswith("FAIL")],
                         [unlisted(5, "other.status shouldBe Status.REFUSED")])

    def test_the_same_spec_passes_when_it_asserts_the_rule_or_its_line_is_listed_with_its_reason(self):
        by_rule = Fixture(self, BY_RULE).check()
        listed = Fixture(self, BY_STATUS, listing(BY_STATUS_LINE, "the queue's own words are the subject")).check()

        self.assertEqual(by_rule.returncode, 0, by_rule.stdout)
        self.assertEqual(listed.returncode, 0, listed.stdout)
        self.assertIn(f"  {SPEC} — {BY_STATUS_LINE}\n      the queue's own words are the subject", listed.stdout)

    def test_a_line_the_file_has_twice_needs_two_entries(self):
        twice = BY_STATUS.replace(f"        {BY_STATUS_LINE}\n", f"        {BY_STATUS_LINE}\n        {BY_STATUS_LINE}\n")

        once = Fixture(self, twice, listing(BY_STATUS_LINE, "the queue's own words")).check()
        both = Fixture(self, twice, listing(BY_STATUS_LINE, "the queue's own words", times=2)).check()
        thrice = Fixture(self, twice, listing(BY_STATUS_LINE, "the queue's own words", times=3)).check()

        self.assertIn(unlisted(4, BY_STATUS_LINE), once.stdout)
        self.assertNotIn(unlisted(3, BY_STATUS_LINE), once.stdout)
        self.assertEqual(both.returncode, 0, both.stdout)
        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} — {BY_STATUS_LINE} — named 3 times, the file has it 2", thrice.stdout)
        self.assertEqual([once.returncode, thrice.returncode], [1, 1])

    def test_an_entry_with_no_reason_or_that_is_no_longer_needed_fails(self):
        no_reason = Fixture(self, BY_STATUS, listing(BY_STATUS_LINE, "")).check()
        by_rule = 'asked should refusedBy("qa-request", "{fact: list.task_exists}")'
        not_needed = Fixture(self, BY_RULE, listing(by_rule, "it used to assert the status")).check()
        no_line = Fixture(self, BY_RULE, listing(BY_STATUS_LINE, "it used to assert the status")).check()
        gone = Fixture(self, BY_RULE, listing(BY_STATUS_LINE, "removed", spec="kp-mailroom/src/test/kotlin/kp/mailroom/GoneSpec.kt")).check()

        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} — {BY_STATUS_LINE} — has no reason", no_reason.stdout)
        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} — {by_rule} — asserts by refusedBy; it needs no entry", not_needed.stdout)
        self.assertIn(f"FAIL refusals-asserted-otherwise.yaml: {SPEC} — {BY_STATUS_LINE} — the file has no such line naming a refusal", no_line.stdout)
        self.assertIn("FAIL refusals-asserted-otherwise.yaml: kp-mailroom/src/test/kotlin/kp/mailroom/GoneSpec.kt is no spec file", gone.stdout)
        self.assertEqual([no_reason.returncode, not_needed.returncode, no_line.returncode, gone.returncode], [1, 1, 1, 1])

    def test_this_checkout_asserts_every_refusal_by_its_rule_or_lists_why_not(self):
        result = subprocess.run([sys.executable, str(CHECK)], capture_output=True, text=True)

        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
