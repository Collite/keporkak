"""KP/P3/S3.10 — the specs that fail by chance (D-106): T1's first sentence (Bora reads this).

Seam: `just many`, which runs one spec many times over — alone, then in its module's whole run — with a second build busy
beside each run, on a throwaway copy of the working tree, a patch applied to the copy when one is given. The three specs:
`TheSeatsSpec`'s first case and `TheThinThreadSpec`'s first case, in kp-daemon, and S3.0's never-ending spec — the
Python suite's, which plants a spec that never ends in kp-log and runs it in every module's test task. Each is run
KP_MANY times each way (2 when it is not set; the hand-off gives a run with more), and once with a patch that breaks
what it holds: `tests/fixtures/s3_10/`.
"""
import os
import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from retro_checks_support import ROOT, just  # noqa: E402

COUNT = int(os.environ.get("KP_MANY", "2"))
FIXTURES = "tests/fixtures/s3_10"

# Each of the three, and the patch that breaks what it holds — in the daemon, or in the specs' timeout.
THE_THREE = {
    "kp-daemon:TheSeatsSpec/a message to an idle seat is its next prompt*": f"{FIXTURES}/the-prompt-without-its-message.patch",
    "kp-daemon:TheThinThreadSpec/a step of a real seat is waited for*": f"{FIXTURES}/a-refusal-without-its-requirement.patch",
    "python:test_s3_0_retro_checks.TheChecksP2sRetroAskedFor.test_a_spec_that_never_ends_fails_by_the_default_timeout_in_every_module_that_has_specs":
        f"{FIXTURES}/kp-logs-timeout-not-the-builds.patch",
}


class TheSpecsThatFailedByChance(unittest.TestCase):
    def test_a_spec_that_reads_what_a_seat_or_the_daemon_wrote_waits_for_that_record_by_the_log_never_for_a_time_the_three_pass_every_run_alone_in_their_modules_whole_run_and_with_a_second_build_busy_and_still_fail_when_what_they_hold_is_broken(self):
        for spec, broken in THE_THREE.items():
            with self.subTest(spec):
                many = just(ROOT, "many", spec, str(COUNT), timeout=7200)
                red = just(ROOT, "many", spec, "1", broken, timeout=3600)

                self.assertEqual(many.returncode, 0, many.stdout[-6000:])
                self.assertIn(f"— alone {COUNT} of {COUNT} passed · in its module's whole run {COUNT} of {COUNT} passed · a second build busy beside each run",
                              many.stdout)
                self.assertEqual(red.returncode, 1, red.stdout[-6000:])
                self.assertRegex(red.stdout, re.compile(r"— alone 0 of 1 passed · in its module's whole run 0 of 1 passed"))


if __name__ == "__main__":
    unittest.main()
