"""The rules data reads clean (S1.0 T5, issues #5 and #6): the file-level checks, for the next developer.

The rule-level checks (a negated negation, a counter nothing reads) are shared fixtures under rules/fixtures/.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RulesHygieneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name)
        for folder in ("scripts", "rules", "robots", "pool", "agents", "docs", "skills"):
            shutil.copytree(ROOT / folder, self.copy / folder)
        (self.copy / "gradle").mkdir()
        shutil.copy(ROOT / "gradle/libs.versions.toml", self.copy / "gradle")

    def edit(self, name, old, new):
        path = self.copy / name
        self.assertEqual(path.read_text().count(old), 1, old)
        path.write_text(path.read_text().replace(old, new))

    def failures(self, script="check-rules.py"):
        result = subprocess.run([sys.executable, str(self.copy / "scripts" / script)], text=True, capture_output=True)
        self.assertEqual(result.stderr, "")
        return [line.removeprefix("FAIL ") for line in result.stdout.splitlines() if line.startswith("FAIL ")]

    def test_the_repository_rules_read_clean(self):
        self.assertEqual(self.failures(), [])
        self.assertEqual(self.failures("render-agents.py"), [])

    def test_an_anchor_fails(self):
        self.edit("robots/retro.yaml", "  'on':\n    op: project.commit_push\n    eq: ok\n  if:\n  - fact: stage.state\n    eq: exited\n  - any:",
                  "  'on': &done\n    op: project.commit_push\n    eq: ok\n  if:\n  - fact: stage.state\n    eq: exited\n  - any:")
        self.edit("robots/retro.yaml", "- id: RET-3\n  when: the last stage of a phase exits\n  note: start a phase-retro (always; the phase cannot exit without its retro-report)\n  'on':\n    op: project.commit_push\n    eq: ok\n",
                  "- id: RET-3\n  when: the last stage of a phase exits\n  note: start a phase-retro (always; the phase cannot exit without its retro-report)\n  'on': *done\n")
        self.assertEqual(self.failures(), ["robots/retro.yaml: a YAML anchor or alias — every rule is written out"])

    def test_a_cap_whose_source_comment_is_gone_fails(self):
        self.edit("rules/engagement.yaml", "  resumes_per_stage: 2  # 3rd needs Bora (RV-15)\n", "  resumes_per_stage: 2\n")
        self.assertEqual(self.failures(), ["rules/engagement.yaml: the comment on 'resumes_per_stage:' is gone — edit the rules as text, never by a YAML round trip"])

    def test_a_legend_that_is_gone_fails(self):
        self.edit("rules/stage-lifecycle.yaml", "# Guards: `observed` = the daemon checks the world", "# Guards checked by the daemon")
        self.assertEqual(self.failures(), ["rules/stage-lifecycle.yaml: the comment on '# Guards: `observed` = the daemon checks the world' is gone — edit the rules as text, never by a YAML round trip"])

    def test_a_quoted_scalar_that_reads_as_a_number_or_a_boolean_fails(self):
        note = "    note: D-27 a, n; master protected by GitHub branch protection\n"
        self.edit("rules/message-kinds.yaml", note, "    note: '2'\n")
        text = (self.copy / "rules/message-kinds.yaml").read_text()
        line = text[:text.index("    note: '2'\n")].count("\n") + 1
        self.assertEqual(self.failures(), [f"rules/message-kinds.yaml: the quoted scalar '2' (line {line}) would read as a number or a boolean — the loader cannot see quotes"])
        self.edit("rules/message-kinds.yaml", "    note: '2'\n", "    note: 'True'\n")
        self.assertEqual(len(self.failures()), 1)
        self.edit("rules/message-kinds.yaml", "    note: 'True'\n", "    note: 'off'\n")
        self.assertEqual(self.failures(), [])

    def test_two_robot_rules_that_render_as_one_line_fail(self):
        self.edit("robots/deputy.yaml", "  'on':\n    arrives: ruling-request\n", "  'on':\n    arrives: classify-request\n")
        self.assertIn("deputy: robot rules DEP-9 and DEP-10 render as the same line", self.failures("render-agents.py"))


if __name__ == "__main__":
    unittest.main()
