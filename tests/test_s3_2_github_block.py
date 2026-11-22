"""KP/P3/S3.2 — the block `github:` of pool/offices.yaml (contracts.md §9, Amendment 33), as check-rules.py validates it.

T1's fifth sentence, the validator's half: an App with no id, a role naming an App the block lacks, a permission that is
neither `read` nor `write` — each fails, naming the line. The daemon's load is TheDaemonReadsGitHubSpec's.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GitHubBlockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.pool = Path(self.temp.name) / "pool"
        shutil.copytree(ROOT / "pool", self.pool)

    def failures_with(self, old, new):
        path = self.pool / "offices.yaml"
        self.assertIn(old, path.read_text())
        path.write_text(path.read_text().replace(old, new))
        result = subprocess.run([sys.executable, "scripts/check-rules.py", "--pool", str(self.pool)], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.stderr, "")
        return [line.removeprefix("FAIL ") for line in result.stdout.splitlines() if line.startswith("FAIL ")]

    def test_an_app_with_no_id_fails_naming_its_line(self):
        self.assertEqual(self.failures_with("    kp-merger: {app: 5849229, installation: 184045472}", "    kp-merger: {installation: 184045472}"),
                         ["pool/offices.yaml: github: apps.kp-merger has no app id (line 31)"])

    def test_a_role_naming_an_app_the_block_lacks_fails_naming_its_line(self):
        self.assertEqual(self.failures_with("    coder: {app: kp-coder,", "    coder: {app: kp-coders,"),
                         ["pool/offices.yaml: github: seats.coder names the App 'kp-coders', which github.apps does not hold (line 36)"])

    def test_a_permission_neither_read_nor_write_fails_naming_its_line(self):
        self.assertEqual(self.failures_with("    coder: {app: kp-coder, permissions: {contents: write,", "    coder: {app: kp-coder, permissions: {contents: admin,"),
                         ["pool/offices.yaml: github: seats.coder gives contents 'admin', which is neither read nor write (line 36)"])


if __name__ == "__main__":
    unittest.main()
