import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
class SchemaComparisonTests(unittest.TestCase):
    def compare(self, transform=lambda x: x):
        text = "\n".join("```json\n" + p.read_text() + "```\n" for p in sorted((ROOT / "schemas").glob("*.json")))
        with tempfile.TemporaryDirectory(dir=ROOT / "build") as folder:
            path = Path(folder) / "contracts.md"
            path.write_text(transform(text))
            return subprocess.run([sys.executable, "build-support/check_schemas.py", "--contracts", str(path)], cwd=ROOT, text=True, capture_output=True)
    def test_verbatim_contract_schemas_match(self):
        result = self.compare()
        self.assertEqual(result.returncode, 0, result.stderr)
    def test_whitespace_drift_is_refused(self):
        result = self.compare(lambda x: x.replace("{", "{ ", 1))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs", result.stderr)
    def test_crlf_drift_is_refused(self):
        result = self.compare(lambda x: x.replace("\n", "\r\n"))
        self.assertNotEqual(result.returncode, 0)
    def test_a_missing_contract_schema_is_refused(self):
        result = self.compare(lambda x: x.replace('"kp:message/1"', '"kp:other/1"'))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing", result.stderr)
if __name__ == "__main__": unittest.main()
