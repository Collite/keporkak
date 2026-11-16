"""The packaging check of render-agents.py (S2.2 T2, contracts.md §10), for the next developer.

The shared fixture under agents/packaging/fixture/ is read by this script and by kp-driver's reader
(PackagingDataSpec); both must report exactly its expected.txt.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "agents/packaging/fixture"


class PackagingCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.copy = Path(self.temp.name)
        for folder in ("scripts", "rules", "robots", "agents", "docs", "skills"):
            shutil.copytree(ROOT / folder, self.copy / folder)

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(self.copy / "scripts/render-agents.py"), *args], text=True, capture_output=True)

    def failures(self):
        result = self.run_script()
        self.assertEqual(result.stderr, "")
        return [line.removeprefix("FAIL ") for line in result.stdout.splitlines() if line.startswith("FAIL ")]

    def edit(self, name, old, new):
        path = self.copy / name
        self.assertEqual(path.read_text().count(old), 1, old)
        path.write_text(path.read_text().replace(old, new))

    def test_the_shared_fixture_reports_exactly_its_expected_lines(self):
        result = self.run_script("--packaging-fixture", str(FIXTURE))
        self.assertEqual(result.stdout.splitlines(), (FIXTURE / "expected.txt").read_text().splitlines())
        self.assertEqual(result.returncode, 1)

    def test_master_has_no_packaging_problem(self):
        self.assertEqual(self.failures(), [])

    def test_a_deny_word_the_data_does_not_map_fails(self):
        self.edit("agents/coder/AGENT.md", "deny: [side-channels,", "deny: [teleport, side-channels,")
        self.assertEqual(self.failures(), ["persona coder: deny word 'teleport' is not mapped by the packaging data"])

    def test_an_mcp_server_the_data_does_not_map_fails(self):
        self.edit("agents/coder/AGENT.md", 'mcp: ["messaging", "context7"]', 'mcp: ["messaging", "context7", "slack"]')
        self.assertEqual(self.failures(), ["persona coder: MCP server 'slack' is not mapped by the packaging data"])

    def test_a_profile_that_fills_no_placeholder_fails(self):
        self.edit("docs/profiles/toy.md", "demo_context: null\n", "")
        self.assertEqual(self.failures(), ["packaging: placeholder '{demo_context}' of deny word 'demo-cluster' is filled by nothing in profile toy"])

    def test_a_placeholder_nothing_fills_fails_for_every_profile(self):
        self.edit("agents/packaging/claude.yaml", 'path: ["{project}/**"]\n        early: ["Edit(/{project}/**)"]', 'path: ["{projekt}/**"]\n        early: ["Edit(/{project}/**)"]')
        self.assertEqual(self.failures(), [f"packaging: placeholder '{{projekt}}' of deny word 'write-project' is filled by nothing in profile {name}"
                                           for name in ("keporkak", "tatrman", "toy")])

    def test_the_packaging_data_without_a_hook_fails(self):
        self.edit("agents/packaging/claude.yaml", "  stop-gate: {event: Stop, timeout: 30, limit: 10}\n", "")
        self.assertEqual(self.failures(), ["packaging: hook 'stop-gate' is not in the packaging data"])


if __name__ == "__main__":
    unittest.main()
