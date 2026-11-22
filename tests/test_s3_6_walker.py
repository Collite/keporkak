"""S3.6 — the walker: events a walk marks as unordered, and a seat per seat a walk binds (T1's specs; Bora reads these).

Seam: `scripts/check-rules.py --trace <walk file>` run in a copy of the rules under build/ — master's, or master's with one
edit — on a walk written here. Its lines: `ORDER` when an order of a step's unordered events begins, `TRACE` after each
step or event, `END` with each order's end, `LEAVES` with what an end leaves out, `FAIL walk …` and `ok   walk …`.

An end holds everything the walker keeps but what it leaves out (review-027 B1, B2). Of each stage: its state, the state
it returns to from a pause, its flag thread, Found lines, other machines, facts and counters. Of each seat: its state and
the stage it is bound to. Bora's presence and the phase; how often each kind fired; every message, refusal, operation's
result and robot's wake kept, and each message's present status; what a robot or the mailroom sent that no step
delivered; the operations a transition requested, and those still waiting, each with the message that asked it. A
record is held whole but for the fields named below.

It leaves out, each with its reason in the walker (`LEAVES`): of the walk, `definition`, `name`, `index`, `stage`,
`current`, `ctx`, `robot`, `subject`, `fired`, `first_candidate`, `in_group`, `group_sent`, `first_moved`,
`used_operations`, `thread_hops` and `history's order`; of a stage's machines, `seat` and `found_item`; of a record,
`id`, `re`, `by` and `rule`.
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

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_with_another_robots_wake(self):
        rules = copy_of_the_rules(self, edit="a-wake-before-the-report")

        failure = failures(walker(rules, walk(rules, RETIRED)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["process-gone", "protocol-closed", "retired"], ["protocol-closed", "retired", "process-gone"]))
        self.assertEqual(without(first_end, "log"), without(other_end, "log"))
        self.assertEqual(wakes(first_end), [])
        self.assertEqual(wakes(other_end), [{"operation": "seat.wake", "role": "pool", "mode": "report", "stage": "S3.6", "tick": 2}])

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_with_a_wake_in_another_stage(self):
        rules = copy_of_the_rules(self)

        failure = failures(walker(rules, walk(rules, A_WAKE_IN_EITHER_STAGE)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["other-stage", "long-call"], ["long-call", "other-stage"]))
        self.assertEqual(without(first_end, "log"), without(other_end, "log"))
        self.assertEqual((wakes(first_end), wakes(other_end)), (
            [{"operation": "seat.wake", "role": "pool", "mode": "report", "stage": "S3.7", "tick": 1}],
            [{"operation": "seat.wake", "role": "pool", "mode": "report", "stage": "S3.6", "tick": 1}]))

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_with_another_machine_in_another_state(self):
        rules = copy_of_the_rules(self, edit="a-thread-held-before-the-report")

        failure = failures(walker(rules, walk(rules, RETIRED)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["process-gone", "protocol-closed", "retired"], ["protocol-closed", "retired", "process-gone"]))
        self.assertEqual(without(first_end, "threads"), without(other_end, "threads"))
        self.assertEqual((first_end["threads"], other_end["threads"]), ({"S3.6": "asked"}, {"S3.6": "held"}))

    def test_a_walk_fails_naming_two_orders_and_their_ends_when_one_order_ends_paused_to_return_to_another_state(self):
        rules = copy_of_the_rules(self, edit="a-fact-moves-the-stage")

        failure = failures(walker(rules, walk(rules, PAUSED)))

        (first, first_end), (other, other_end) = named(failure[0])
        self.assertEqual((first, other), (["fence-complete", "process-gone"], ["process-gone", "fence-complete"]))
        self.assertEqual((first_end["stages"], other_end["stages"]), ({"S3.6": "paused"}, {"S3.6": "paused"}))
        self.assertEqual(without(first_end, "returns"), without(other_end, "returns"))
        self.assertEqual((first_end["returns"], other_end["returns"]), ({"S3.6": "ready_for_review"}, {"S3.6": "building"}))

    def test_an_end_leaves_out_only_what_this_module_names_and_the_walker_gives_a_reason_for_each(self):
        rules = copy_of_the_rules(self)

        leaves = next(json.loads(line[7:]) for line in walker(rules, walk(rules, RETIRED)) if line.startswith("LEAVES "))

        named_here = re.search(r"of the walk,\s(.*?);\sof a stage's machines,\s(.*?);\sof a record,\s(.*?)\.\n", __doc__, re.S).groups()
        self.assertEqual([sorted(leaves[part]) for part in ("walk", "stage", "record")],
                         [sorted(re.findall(r"`([^`]+)`", clause)) for clause in named_here])
        self.assertEqual([part for part, reasons in leaves.items() for name, reason in reasons.items() if not reason.strip()], [])

    def test_a_walker_that_keeps_more_than_an_end_holds_or_leaves_out_refuses_to_end_a_walk_naming_it(self):
        rules = copy_of_the_rules(self, edit="a-walker-that-keeps-more")

        ran = subprocess.run([str(PYTHON), "scripts/check-rules.py", "--trace", str(walk(rules, RETIRED))], cwd=rules, text=True, capture_output=True, timeout=600)

        self.assertNotEqual(ran.returncode, 0)
        self.assertIn("RuntimeError: walk a-retired-seats-ends: its end neither holds nor leaves out kept_more", ran.stderr)

    def test_a_walker_that_keeps_a_field_more_on_a_wake_or_a_message_holds_it_in_each_end(self):
        rules = copy_of_the_rules(self, edit="a-walker-that-keeps-a-field-more")

        two_seats = [json.loads(line[4:])["end"] for line in walker(rules, walk(rules, TWO_SEATS)) if line.startswith("END ")]
        either = [json.loads(line[4:])["end"] for line in walker(rules, walk(rules, A_WAKE_IN_EITHER_STAGE)) if line.startswith("END ")]

        held = [json.loads(record) for end in two_seats for key in ("log", "messages") for record in end[key] if "kind" in json.loads(record) and "status" in json.loads(record)]
        self.assertTrue(held)
        self.assertEqual([record for record in held if record.get("kept_more") != "yes"], [])
        self.assertEqual([wake.get("kept_more") for end in either for wake in wakes(end)], ["yes", "yes"])

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


# A seat dies while its stage's fence is reported complete, in an order the world does not fix; the robot's seat-gone pauses
# the stage, from where it is then (review-027 B2).
PAUSED = """
name: a-paused-stages-return
stage: S3.6
initial: building
final: paused
seats:
  coder-1: coder
facts:
  seat.name:
    coder: coder-1
  seat.process_gone: false
  seat.protocol_closed: false
  seat.tool_call_open_for: 0
  seat.inbox_replayed: true
  list.fence_complete: false
steps:
- id: gone
  unordered:
  - id: fence-complete
    who: daemon
    event:
      fact: list.fence_complete
    facts:
      list.fence_complete: true
  - id: process-gone
    who: daemon
    event:
      fact: seat.process_gone
    trigger:
      seat: coder-1
    facts:
      seat.process_gone: true
- operation: seat.reseat
  request: gone/seat-gone
  result: ok
"""


# Master's rules, nothing planted: a seat's tool call is open past POOL-9's watch, and its event names no stage — it is
# played in the stage of the event before it, so the robot's wake lands in either stage (review-027 B2, round 2).
A_WAKE_IN_EITHER_STAGE = """
name: a-wake-in-either-stage
stage: S3.6
initial: building
final:
  S3.6: building
  S3.7: building
seats:
  coder-1: coder
facts:
  seat.name:
    coder: coder-1
  seat.tool_call_open_for: 90m
  list.fence_complete: false
steps:
- id: either
  unordered:
  - id: other-stage
    who: daemon
    stage: S3.7
    event:
      fact: list.fence_complete
    facts:
      list.fence_complete: true
  - id: long-call
    who: daemon
    event:
      fact: seat.tool_call_open_for
    trigger:
      seat: coder-1
"""


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


# Review-027 B1's probes: a robot that wakes its own seat, or a flag thread held, on the retire's result only when no end
# report has come before it.
A_WAKE = """- id: POOL-90
  when: a spec's own (S3.6, review-027 B1)
  'on':
    op: seat.retire
    eq: ok
  if:
  - fact: seat.process_gone
    seat:
      trigger: seat
    eq: false
  then:
  - wake: report
"""
A_THREAD_HELD = """  - from: asked
    to: held
    kind: retire
    guard: a spec's own (S3.6, review-027 B1)
    id: FLAG_THREAD-90
    'on': op-result
    require:
    - op: seat.retire
      eq: ok
    - fact: seat.process_gone
      seat:
        arg: seat
      eq: false
    effects: []
"""

# Review-027 B2's probe: a stage that a fact moves on to review, with nothing else.
A_FACT_MOVES_THE_STAGE = """- from: building
  to: ready_for_review
  guard: a spec's own (S3.6, review-027 B2)
  id: T-90
  'on': fact
  require:
  - fact: list.fence_complete
  effects: []
"""


def copy_of_the_rules(test, edit=None):
    """The walker and master's rules, robots and pool in a new folder outside the checkout — git applies a patch there as
    a plain patch tool, not as the checkout's — with one edit, when named."""
    copy = pathlib.Path(tempfile.mkdtemp(prefix="kp-s3.6-rules-"))
    test.addCleanup(shutil.rmtree, copy, ignore_errors=True)
    for folder in ("scripts", "rules", "robots", "pool", "agents"):
        shutil.copytree(ROOT / folder, copy / folder)
    if edit == "d85-taken-out":
        subprocess.run(["git", "apply", "-R", str(D85)], cwd=copy, check=True)
        assert "SEAT-14" in (copy / "rules/stage-lifecycle.yaml").read_text()
    elif edit == "a-wake-before-the-report":
        pool = copy / "robots/pool.yaml"
        text = pool.read_text()
        assert "- id: POOL-2\n" in text
        pool.write_text(text.replace("- id: POOL-2\n", A_WAKE + "- id: POOL-2\n", 1))
    elif edit == "a-thread-held-before-the-report":
        lifecycle = copy / "rules/stage-lifecycle.yaml"
        text = lifecycle.read_text()
        anchor = "flag_thread:\n  states:\n  - asked\n  - held\n  - ruled\n  - expired\n  transitions:\n"
        assert anchor in text
        lifecycle.write_text(text.replace(anchor, anchor + A_THREAD_HELD, 1))
    elif edit == "a-fact-moves-the-stage":
        lifecycle = copy / "rules/stage-lifecycle.yaml"
        text = lifecycle.read_text()
        anchor = "- from: '*active'\n  to: paused\n  kind: seat-gone\n"
        assert anchor in text
        lifecycle.write_text(text.replace(anchor, A_FACT_MOVES_THE_STAGE + anchor, 1))
    elif edit == "a-walker-that-keeps-a-field-more":
        script = copy / "scripts/check-rules.py"
        text = script.read_text()
        for anchor in ('self.history.append({"operation":"seat.wake","mode":action["wake"],', 'self.ctx={"kind":kind_name,"from":who,'):
            assert text.count(anchor) == 1
            text = text.replace(anchor, anchor.replace("({", "({\"kept_more\":\"yes\",").replace("={", "={\"kept_more\":\"yes\","), 1)
        script.write_text(text)
    elif edit == "a-walker-that-keeps-more":
        script = copy / "scripts/check-rules.py"
        text = script.read_text()
        anchor = "        self.emissions=[]\n"
        assert text.count(anchor) == 1
        script.write_text(text.replace(anchor, anchor + "        self.kept_more={}\n", 1))
    elif edit in EDITS:
        lifecycle = copy / "rules/stage-lifecycle.yaml"
        text = lifecycle.read_text()
        anchor = "  - from: provisioned\n    to: provisioned\n    kind: session-report\n"
        assert anchor in text
        lifecycle.write_text(text.replace(anchor, BEFORE_THE_REPORT.replace("EFFECT", EDITS[edit]) + anchor, 1))
    return copy


def wakes(end):
    """The robots' wakes an end's log holds, each as the walker keeps it but for what an end leaves out of a record."""
    return [record for record in map(json.loads, end["log"]) if record.get("operation") == "seat.wake" and "mode" in record]


def without(end, key):
    """[end] with [key] left out: what two ends hold besides it."""
    return {name: value for name, value in end.items() if name != key}


def walk(rules, text):
    """[text] as a walk file of its own in [rules]: its path."""
    path = pathlib.Path(tempfile.mkstemp(prefix="walk-", suffix=".yaml", dir=rules)[1])
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
