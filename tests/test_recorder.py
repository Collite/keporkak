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
    def test_just_record_refuses_a_trailing_newline_in_a_message_id(self):
        result = self.record({**MESSAGE, "id": "m-panda-000001\n"}, via_just=True)
        self.assertNotEqual(result.returncode, 0, result.stdout)
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

class RecorderCommandLineTests(unittest.TestCase):
    """contracts.md §12's command line; the recorder assigns id, thread and seq itself (§3, D-52)."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name) / "project"
        self.project.mkdir()
        self.log = self.project / "mail/KP/_hand/p0-s0.3.jsonl"
    def record(self, *arguments, via_just=False, project=True):
        command = (["just", "record"] if via_just else [sys.executable, "scripts/kp-record.py"]) + list(arguments)
        if project: command += ["--project", str(self.project)]
        return subprocess.run(command, text=True, capture_output=True, cwd=ROOT, stdin=subprocess.DEVNULL)
    def lines(self, path=None):
        return [json.loads(line) for line in (path or self.log).read_text().splitlines()]
    def test_just_record_review_request_appends_one_valid_line_with_a_hand_id_and_the_next_sequence_number(self):
        first = self.record("review-request", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3", via_just=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.record("review-request", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3", via_just=True)
        self.assertEqual(second.returncode, 0, second.stderr)
        one, two = self.lines()
        schema = json.loads((ROOT / "schemas/message.schema.json").read_text())
        for line in (one, two):
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(line)
        self.assertEqual((one["id"], one["seq"], one["thread"]), ("m-hand-000001", 1, "m-hand-000001"))
        self.assertEqual((two["id"], two["seq"], two["thread"]), ("m-hand-000002", 2, "m-hand-000002"))
        self.assertEqual({key: one[key] for key in ("kind", "from", "to", "stage", "by", "v")}, {"kind": "review-request", "from": "coder", "to": "teamlead", "stage": "KP/P0/S0.3", "by": "hand:coder", "v": 1})
        self.assertEqual(one["at"], one["created"])
    def test_an_unscoped_record_goes_to_the_hand_log_of_its_own_month_and_continues_its_sequence(self):
        first = self.record("away", "--from", "bora", "--to", "mailroom", "--effort", "KP")
        self.assertEqual(first.returncode, 0, first.stderr)
        second = self.record("away", "--from", "bora", "--to", "mailroom", "--effort", "KP")
        self.assertEqual(second.returncode, 0, second.stderr)
        hand = self.project / "mail/KP/_hand"
        logs = sorted(path.name for path in hand.iterdir())
        one, two = self.lines(hand / logs[0])
        self.assertEqual(logs, [f"office-hand-{one['at'][:7]}.jsonl"])
        self.assertEqual((one["seq"], two["seq"]), (1, 2))
    def test_a_reply_takes_the_thread_of_the_message_it_answers(self):
        self.record("review-request", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        result = self.record("review-bind", "--from", "teamlead", "--to", "coder", "--stage", "KP/P0/S0.3", "--re", "m-hand-000001")
        self.assertEqual(result.returncode, 0, result.stderr)
        reply = self.lines()[2]
        self.assertEqual((reply["id"], reply["seq"], reply["thread"], reply["re"]), ("m-hand-000003", 3, "m-hand-000001", "m-hand-000001"))
    def test_a_reply_to_a_message_not_in_the_hand_log_is_refused_and_changes_nothing(self):
        self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        before = self.log.read_bytes()
        result = self.record("review-bind", "--from", "teamlead", "--to", "coder", "--stage", "KP/P0/S0.3", "--re", "m-hand-000009")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("m-hand-000009, is not in p0-s0.3.jsonl", result.stderr)
        self.assertEqual(self.log.read_bytes(), before)
    def test_an_unknown_kind_is_refused_before_any_path_is_created(self):
        result = self.record("review-requets", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown kind 'review-requets'", result.stderr)
        self.assertFalse((self.project / "mail").exists())
    def test_hand_ids_run_across_the_projects_hand_logs_and_sequence_numbers_per_log(self):
        self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "TOY/P0/S0.2")
        toy = self.lines(self.project / "mail/TOY/_hand/p0-s0.2.jsonl")[0]
        self.assertEqual((toy["id"], toy["seq"]), ("m-hand-000002", 1))
    def test_args_are_json_when_they_parse_and_strings_otherwise(self):
        result = self.record("verdict", "--from", "reviewer", "--to", "coder", "--stage", "KP/P0/S0.3", "--args", "verdict=FIX FIRST", "blockers=[\"B1\", \"B2\"]", "--args", "sha=0123abc")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.lines()[0]["args"], {"verdict": "FIX FIRST", "blockers": ["B1", "B2"], "sha": "0123abc"})
    def test_a_seat_name_is_recorded_by_hand_of_its_role(self):
        self.record("started", "--from", "coder-1", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.assertEqual((self.lines()[0]["from"], self.lines()[0]["by"]), ("coder-1", "hand:coder"))
    def test_a_sender_that_is_no_role_is_refused(self):
        result = self.record("started", "--from", "intern", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.project / "mail").exists())
    def test_the_project_defaults_to_the_offices_checkout_in_the_pool(self):
        pool = Path(self.temp.name) / "pool"
        pool.mkdir()
        (pool / "offices.yaml").write_text(f"version: 1\noffices:\n  panda:\n    project: {{KP: {self.project}}}\n")
        result = self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3", "--pool", str(pool), "--office", "panda", project=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.lines()), 1)
    def test_the_sequence_continues_after_a_line_recorded_from_stdin(self):
        carried = {**MESSAGE, "stage": "KP/P0/S0.3", "seq": 5}
        stdin = subprocess.run([sys.executable, "scripts/kp-record.py", "--project", str(self.project)], input=json.dumps(carried), text=True, capture_output=True, cwd=ROOT)
        self.assertEqual(stdin.returncode, 0, stdin.stderr)
        self.record("started", "--from", "coder", "--to", "teamlead", "--stage", "KP/P0/S0.3")
        self.assertEqual((self.lines()[1]["seq"], self.lines()[1]["id"]), (6, "m-hand-000001"))

if __name__ == "__main__": unittest.main()
