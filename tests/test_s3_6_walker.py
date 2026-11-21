"""S3.6 — the walker: events a walk marks as unordered, and a seat per seat a walk binds (T1's specs; Bora reads these).

Seam: `scripts/check-rules.py --trace <walk file>` run in a copy of the rules under build/ — master's, or master's with one
edit — on a walk written here. Its lines: `ORDER` when an order of a step's unordered events begins, `TRACE` after each
step or event, `END` with each order's end, `FAIL walk …` and `ok   walk …`. An end is every stage's state, every seat's,
the counts — counters and deliveries — and the operations requested.
"""
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PYTHON = ROOT / "build/python/bin/python"
D85 = pathlib.Path(__file__).resolve().parent / "fixtures/d85-rule-edit.patch"

# A seat retired, then its two end reports and the retire's result in an order the world does not fix (D-85).
RETIRED = """
name: a-retired-seats-ends
stage: S3.6
initial: building
final: building
seats:
  coder-1: coder
stages:
  S3.6:
    machines:
      seat: idle
facts:
  seat.process_gone: false
  seat.protocol_closed: false
  seat.tool_call_open_for: 0
  budget.slots_over:
    office: 1
steps:
- id: retire
  who: pool
  kind: retire
  to: coder-1
  args:
    seat: coder-1
- unordered:
  - id: process-gone
    who: daemon
    event:
      fact: seat.process_gone
    trigger:
      seat: coder-1
    facts:
      seat.process_gone: true
  - id: protocol-closed
    who: daemon
    event:
      fact: seat.protocol_closed
    trigger:
      seat: coder-1
    facts:
      seat.protocol_closed: true
  - id: retired
    operation: seat.retire
    request: retire
    result: ok
"""

# Two seats of one stage die, the reviewer's while the stage is paused for the coder's; the robot hears both, and both
# are re-seated (D-81).
TWO_SEATS = """
name: two-seats-of-a-stage
stage: S3.6
initial: building
final: building
seats:
  coder-1: coder
  reviewer-1: reviewer
facts:
  seat.process_gone: false
  seat.protocol_closed: false
  seat.tool_call_open_for: 0
  seat.inbox_replayed: true
steps:
- who: daemon
  event:
    fact: seat.process_gone
  trigger:
    seat: coder-1
  facts:
    seat.process_gone: true
  expect:
    rules:
    - POOL-1
- id: coder-gone
  who: pool
  by: robot:POOL-1
  kind: seat-gone
  args:
    seat: coder-1
- who: daemon
  event:
    fact: seat.process_gone
  trigger:
    seat: reviewer-1
  expect:
    rules:
    - POOL-1
- id: reviewer-gone
  who: pool
  by: robot:POOL-1
  kind: seat-gone
  args:
    seat: reviewer-1
- operation: seat.reseat
  request: coder-gone
  result: ok
- who: pool
  by: robot:POOL-14
  kind: re-seated
  args:
    seat: coder-1
- operation: seat.reseat
  request: reviewer-gone
  result: ok
- who: pool
  by: robot:POOL-14
  kind: re-seated
  args:
    seat: reviewer-1
"""


class TheWalker(unittest.TestCase):
    def test_events_a_walk_marks_as_unordered_are_played_in_every_order_two_of_them_and_three_of_them(self):
        rules = copy_of_the_rules(self)
        two = walk(rules, RETIRED.replace("""  - id: protocol-closed
    who: daemon
    event:
      fact: seat.protocol_closed
    trigger:
      seat: coder-1
    facts:
      seat.protocol_closed: true
""", ""))
        three = walk(rules, RETIRED)

        self.assertEqual(orders(walker(rules, two)), [["process-gone", "retired"], ["retired", "process-gone"]])
        self.assertEqual(orders(walker(rules, three)), [
            ["process-gone", "protocol-closed", "retired"], ["process-gone", "retired", "protocol-closed"],
            ["protocol-closed", "process-gone", "retired"], ["protocol-closed", "retired", "process-gone"],
            ["retired", "process-gone", "protocol-closed"], ["retired", "protocol-closed", "process-gone"]])
        self.assertIn("ok   walk a-retired-seats-ends reached building", walker(rules, three))

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_in_another_state(self):
        rules = copy_of_the_rules(self, edit="d85-taken-out")

        failure = failures(walker(rules, walk(rules, RETIRED)))

        self.assertEqual(len(failure), 1)
        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual(first, ["process-gone", "protocol-closed", "retired"])
        self.assertEqual((first_end["stages"], first_end["seats"]), ({"S3.6": "paused"}, {"coder-1": "retired"}))
        self.assertEqual(other, ["retired", "process-gone", "protocol-closed"])
        self.assertEqual((other_end["stages"], other_end["seats"]), ({"S3.6": "building"}, {"coder-1": "retired"}))

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_with_other_counts(self):
        rules = copy_of_the_rules(self, edit="a-count-before-the-report")

        failure = failures(walker(rules, walk(rules, RETIRED)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["process-gone", "protocol-closed", "retired"], ["protocol-closed", "retired", "process-gone"]))
        self.assertEqual((first_end["stages"], first_end["seats"], first_end["operations"]), (other_end["stages"], other_end["seats"], other_end["operations"]))
        self.assertEqual((first_end["counters"], other_end["counters"]), ({}, {"S3.6": {"seat_gone_events": 1}}))

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_with_other_requested_operations(self):
        rules = copy_of_the_rules(self, edit="an-operation-before-the-report")

        failure = failures(walker(rules, walk(rules, RETIRED)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["process-gone", "protocol-closed", "retired"], ["protocol-closed", "retired", "process-gone"]))
        self.assertEqual((first_end["stages"], first_end["seats"], first_end["counters"]), (other_end["stages"], other_end["seats"], other_end["counters"]))
        self.assertEqual((first_end["operations"], other_end["operations"]), (
            ["seat.retire (retire coder-1)"], ["seat.compact (retire coder-1)", "seat.retire (retire coder-1)"]))

    def test_a_walk_that_binds_two_seats_to_a_stage_keeps_a_state_for_each_and_a_robot_hears_each_death_as_that_seats(self):
        rules = copy_of_the_rules(self)

        lines = walker(rules, walk(rules, TWO_SEATS))

        self.assertEqual(failures(lines), [])
        self.assertEqual([(step["fired"], step["seats"]) for step in traces(lines)], [
            ([], {"coder-1": "ready", "reviewer-1": "ready"}),  # the robot hears coder-1's death
            (["T-59", "SEAT-06"], {"coder-1": "gone", "reviewer-1": "ready"}),  # the first to die
            ([], {"coder-1": "gone", "reviewer-1": "ready"}),  # the robot hears reviewer-1's death as its own
            (["T-80", "SEAT-06"], {"coder-1": "gone", "reviewer-1": "gone"}),  # the second to die: both dead
            ([], {"coder-1": "gone", "reviewer-1": "gone"}),
            (["T-60", "SEAT-07"], {"coder-1": "ready", "reviewer-1": "gone"}),  # one re-seated
            ([], {"coder-1": "ready", "reviewer-1": "gone"}),
            (["SEAT-07"], {"coder-1": "ready", "reviewer-1": "ready"}),
        ])


# The edits a copy of the rules is given: D-85's taken out, as `_closed/rule-edit-d85-…` names it; or, for one spec each,
# a transition that counts, or requests an operation, on the retire's result only when no end report has come before it.
EDITS = {
    "a-count-before-the-report": "count: seat_gone_events",
    "an-operation-before-the-report": "do: seat.compact\n      with:\n        seat:\n          arg: seat",
}
BEFORE_THE_REPORT = """  - from: retired
    to: retired
    kind: retire
    guard: a spec's own (S3.6 T1)
    id: SEAT-90
    'on': op-result
    require:
    - op: seat.retire
      eq: ok
    - fact: seat.process_gone
      seat:
        arg: seat
      eq: false
    effects:
    - EFFECT
"""


def copy_of_the_rules(test, edit=None):
    """The walker and master's rules, robots and pool in a new folder under build/ — with one edit, when named."""
    copy = pathlib.Path(tempfile.mkdtemp(prefix="s3.6-rules-", dir=ROOT / "build"))
    test.addCleanup(shutil.rmtree, copy, ignore_errors=True)
    for folder in ("scripts", "rules", "robots", "pool"):
        shutil.copytree(ROOT / folder, copy / folder)
    if edit == "d85-taken-out":
        subprocess.run(["git", "apply", "-R", str(D85)], cwd=copy, check=True)
    elif edit in EDITS:
        lifecycle = copy / "rules/stage-lifecycle.yaml"
        text = lifecycle.read_text()
        anchor = "  - from: provisioned\n    to: provisioned\n    kind: session-report\n"
        assert anchor in text
        lifecycle.write_text(text.replace(anchor, BEFORE_THE_REPORT.replace("EFFECT", EDITS[edit]) + anchor, 1))
    return copy


def walk(rules, text):
    """[text] as a walk file in [rules]: its path."""
    path = rules / "walk.yaml"
    path.write_text(text)
    return path


def walker(rules, path):
    """`check-rules.py --trace <path>` in [rules]: its lines."""
    ran = subprocess.run([str(PYTHON), "scripts/check-rules.py", "--trace", str(path)], cwd=rules, text=True, capture_output=True, timeout=600)
    assert not ran.stderr, ran.stderr
    return ran.stdout.splitlines()


def orders(lines):
    """Each order the walker played, by the labels of its events, from its `END` lines."""
    return [[label for _, events in json.loads(line[4:])["orders"] for label in events] for line in lines if line.startswith("END ")]


def traces(lines):
    return [json.loads(line[6:]) for line in lines if line.startswith("TRACE ")]


def failures(lines):
    return [line for line in lines if line.startswith("FAIL walk ")]


ORDERS_APART = re.compile(r"^FAIL walk [\w-]+: its orders end apart — in the order (.+?) it ends (\{.*\}); in the order (.+?) it ends (\{.*\})$")


def named(failure):
    """The two orders a failure names, each as its labels, with its end."""
    first, first_end, other, other_end = ORDERS_APART.match(failure).groups()
    return (first.split(", "), json.loads(first_end)), (other.split(", "), json.loads(other_end))


if __name__ == "__main__":
    unittest.main()
