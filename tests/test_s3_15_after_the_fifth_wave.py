"""KP/P3/S3.15 T1 — after the fifth wave's reviews: the walker asks the robots by `order:` (Bora reads this).

Seam: `scripts/check-rules.py --trace <walk file>` on a copy of master's rules, as `test_s3_6_walker.py` runs it, in which
the Pool Manager too takes a `classify-request` to Bora while he is away and holds it, as the Deputy's DEP-9 does — the
fixture of the engine's `TheRobotsInTheirOrderSpec` — and on that copy with the Deputy's and the Pool Manager's `order:`
exchanged, their files' names unchanged. The walk's one message is taken by both robots; its step expects the rules that
took it, the robot asked first first (contracts.md §8, Amendment 45). The engine's order is `TheRobotsInTheirOrderSpec`'s,
the refusals of a robot file's order `TheRobotsOrderSpec`'s; neither runs the walker.
"""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PYTHON = ROOT / "build/python/bin/python"

# Bora goes away; the reviewer asks him to classify a finding — a class-B item the Deputy holds while he is away (DEP-9).
WALK = """
name: two-robots-take-one-message
stage: S3.15
initial: in_review
final: in_review
seats:
  reviewer-1: reviewer
steps:
- who: bora
  kind: away
  signed: bora
  args: {}
  expect:
    states:
      presence: absent
- id: classification
  who: reviewer-1
  kind: classify-request
  args:
    question: blocker or follow-up?
    default: follow-up
  expect:
    held: true
    rules: [TAKEN_FIRST, TAKEN_SECOND]
"""

# The Pool Manager intercepts the kind and holds it as DEP-9 does: `hold` is the Deputy's kind on master, the pool's too here.
POOL_TAKES_IT = [
    ("rules/message-kinds.yaml", "  hold:\n    from: deputy\n", "  hold:\n    from: deputy | pool\n"),
    ("robots/pool.yaml", "intercepts: []\nrules:\n", """intercepts:
- classify-request
rules:
- id: POOL-90
  when: a classify-request arrives (a fixture's)
  note: send hold to the asker
  'on':
    arrives: classify-request
  if:
  - msg: to
    eq: bora
  then:
  - send: hold
    to:
      trigger: from
    args: {}
"""),
]
EXCHANGED = [("robots/deputy.yaml", "\norder: 1\n", "\norder: 2\n"), ("robots/pool.yaml", "\norder: 2\n", "\norder: 1\n")]


class TheWalkersOrder(unittest.TestCase):
    def test_the_walker_asks_the_robots_by_order_with_two_robots_numbers_exchanged_a_message_both_would_take_is_taken_by_the_other(self):
        master = self.copy_of_the_rules(POOL_TAKES_IT)
        exchanged = self.copy_of_the_rules(POOL_TAKES_IT + EXCHANGED)

        # Master's numbers: the Deputy's 1 before the Pool Manager's 2 — DEP-9 takes it first.
        self.assertEqual(walked(master, "DEP-9", "POOL-90"), "ok   walk two-robots-take-one-message reached in_review")
        # Exchanged: the Pool Manager's 1 before the Deputy's 2 — POOL-90 takes it first; the files' names are as they were.
        self.assertEqual(walked(exchanged, "POOL-90", "DEP-9"), "ok   walk two-robots-take-one-message reached in_review")
        self.assertEqual(walked(exchanged, "DEP-9", "POOL-90"),
                         "FAIL walk two-robots-take-one-message step 2: expected rules ['DEP-9', 'POOL-90'], observed ['POOL-90', 'DEP-9']")

    def copy_of_the_rules(self, edits):
        """The walker and master's rules, robots, pool and personas in a new folder outside the checkout, with [edits]."""
        copy = pathlib.Path(tempfile.mkdtemp(prefix="kp-s3.15-rules-"))
        self.addCleanup(shutil.rmtree, copy, ignore_errors=True)
        for folder in ("scripts", "rules", "robots", "pool", "agents"):
            shutil.copytree(ROOT / folder, copy / folder)
        for file, before, after in edits:
            text = (copy / file).read_text()
            self.assertEqual(text.count(before), 1, f"{file} holds {before!r} once")
            (copy / file).write_text(text.replace(before, after))
        return copy


def walked(rules, first, second):
    """The walk, its message expected taken by [first] then [second], played by `check-rules.py --trace` in [rules]: its verdict."""
    path = rules / "rules/walks/two-robots-take-one-message.yaml"
    path.write_text(WALK.replace("TAKEN_FIRST", first).replace("TAKEN_SECOND", second))
    ran = subprocess.run([str(PYTHON), "scripts/check-rules.py", "--trace", str(path)], cwd=rules, text=True, capture_output=True, timeout=600)
    assert not ran.stderr, ran.stderr
    verdicts = [line for line in ran.stdout.splitlines() if line.startswith(("ok   walk ", "FAIL walk "))]
    assert len(verdicts) == 1, ran.stdout
    return verdicts[0]


if __name__ == "__main__":
    unittest.main()
