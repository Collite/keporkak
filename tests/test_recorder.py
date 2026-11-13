import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
MESSAGE = {"v": 1, "seq": 1, "at": "2026-11-13T12:00:00Z", "id": "m-panda-000001", "thread": "m-panda-000001", "from": "coder-1", "to": "teamlead", "kind": "started", "stage": "KP/P0/S0.2", "subject": "Skeleton", "created": "2026-11-13T12:00:00Z", "by": "hand:coder"}

class RecorderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name) / "project"
        self.project.mkdir()
        self.log = self.project / "mail/KP/_hand/p0-s0.2.jsonl"
    def record(self, message=None, raw=None, effort=None, via_just=False):
        command = ["just", "record"] if via_just else [sys.executable, "scripts/kp-record.py"]
        command += ["--project", str(self.project)]
        if effort: command += ["--effort", effort]
        return subprocess.run(command, input=raw if raw is not None else json.dumps(message or MESSAGE), text=True, capture_output=True, cwd=ROOT)
    def test_just_record_writes_one_schema_valid_hand_carried_message(self):
        result = self.record(via_just=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = self.log.read_text().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(json.loads(lines[0]), MESSAGE)
        schema = json.loads((ROOT / "schemas/message.schema.json").read_text())
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(json.loads(lines[0]))
    def test_unknown_field_is_refused_before_any_log_is_created(self):
        result = self.record({**MESSAGE, "unknown": True})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_malformed_id_is_refused(self):
        result = self.record({**MESSAGE, "id": "m-panda-1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_non_hand_producer_is_refused(self):
        self.assertNotEqual(self.record({**MESSAGE, "by": "seat:coder-1"}).returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_invalid_json_is_refused(self):
        self.assertNotEqual(self.record(raw="{broken").returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_second_message_appends_without_changing_the_first(self):
        self.assertEqual(self.record().returncode, 0)
        before = self.log.read_bytes()
        second = {**MESSAGE, "seq": 2, "id": "m-panda-000002", "body": "🐋"}
        self.assertEqual(self.record(second).returncode, 0)
        self.assertTrue(self.log.read_bytes().startswith(before))
        self.assertEqual(json.loads(self.log.read_text().splitlines()[1]), second)
    def test_refusal_leaves_an_existing_log_byte_for_byte_unchanged(self):
        self.assertEqual(self.record().returncode, 0)
        before = self.log.read_bytes()
        self.assertNotEqual(self.record({**MESSAGE, "subject": ""}).returncode, 0)
        self.assertEqual(self.log.read_bytes(), before)
    def test_bad_timestamp_is_refused(self):
        self.assertNotEqual(self.record({**MESSAGE, "at": "2026-02-30T12:00:00Z"}).returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_unscoped_message_uses_explicit_effort_hand_directory(self):
        message = {key: value for key, value in MESSAGE.items() if key != "stage"}
        result = self.record(message, effort="KP")
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.project / "mail/KP/_hand/office-panda-2026-11.jsonl"
        self.assertEqual(json.loads(path.read_text()), message)
    def test_effort_mismatch_is_refused(self):
        self.assertNotEqual(self.record(effort="TOY").returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_fractional_sequence_is_not_rounded_into_a_valid_integer(self):
        raw = json.dumps(MESSAGE).replace('"seq": 1', '"seq": 1.00000000000000000001')
        self.assertNotEqual(self.record(raw=raw).returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_two_input_objects_are_refused(self):
        self.assertNotEqual(self.record(raw=json.dumps(MESSAGE) + "\n" + json.dumps(MESSAGE)).returncode, 0)
        self.assertFalse((self.project / "mail").exists())
if __name__ == "__main__": unittest.main()
