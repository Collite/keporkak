"""KP/P3/S3.14 T1 — after the fourth wave's reviews (Bora reads these): the office check's line on `~/.netrc`.

Seam: `scripts/office-check.py` (behind `just office-check`) on the S0.3 specs' fake machine, whose home is a fixture:
a `~/.netrc` written there is the one the check reads (contracts.md §12, §4.4 Amendment 47). Every entry names a login
and a password the output must not hold. The daemon's sentences are AfterTheFourthWaveSpec's.

T4 — the fifth case that failed by chance, `AfterTheReviewsSpec`'s "a wake that fails leaves its message unread": through
`just many`, on demand, as S3.11 ran the four — `KP_MANY=<n> build/python/bin/python -m unittest test_s3_14_after_the_fourth_wave`
in tests/. It passes every run with S3.10's agent slowed, alone and in its module's whole run; with its wait taken out and
its tool call heard late it fails; a mutant of the daemon that reads the message of a failed wake turns it red.
"""
import importlib.util
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from retro_checks_support import ROOT, just  # noqa: E402

_spec = importlib.util.spec_from_file_location("office_specs", Path(__file__).with_name("test_s0_3_the_office.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)
FakeMachine = _specs.FakeMachine
_check = importlib.util.spec_from_file_location("office_check", ROOT / "scripts/office-check.py")
OFFICE_CHECK = importlib.util.module_from_spec(_check)
_check.loader.exec_module(OFFICE_CHECK)

COUNT = int(os.environ.get("KP_MANY", "0"))
FIFTH = "kp-daemon:AfterTheReviewsSpec/a wake that fails*"
SLOWED = "tests/fixtures/s3_10/the-agent-slowed.patch"
FIXTURES = "tests/fixtures/s3_14"

LOGIN, PASSWORD = "planted-login-7", "planted-password-7"
GITHUB_ALONE = f"machine github.com login {LOGIN} password {PASSWORD}\n"
AMONG_OTHERS = f"machine example.org\n  login {LOGIN}\n  password {PASSWORD}\n\nmachine github.com\n  login {LOGIN}\n  password {PASSWORD}\nmachine gitlab.com login {LOGIN} password {PASSWORD}\n"
AS_DEFAULT = f"machine example.org login {LOGIN} password {PASSWORD}\ndefault login {LOGIN} password {PASSWORD}\n"
OTHERS_ONLY = f"machine example.org login {LOGIN} password {PASSWORD}\nmachine api.github.com.example.net login {LOGIN} password {PASSWORD}\n"


class AfterTheFourthWave(unittest.TestCase):
    def test_the_office_check_says_to_do_for_a_netrc_that_names_github_com_alone_among_other_machines_as_default_and_nothing_for_one_that_does_not_and_prints_no_login_and_no_password(self):
        machine = FakeMachine(self, roots=["worktrees", "scratch"], linger="yes")

        alone = netrc_lines(machine, GITHUB_ALONE)
        among = netrc_lines(machine, AMONG_OTHERS)
        default = netrc_lines(machine, AS_DEFAULT)
        others = netrc_lines(machine, OTHERS_ONLY)
        none = netrc_lines(machine, None)

        self.assertEqual(alone, ["to do  no github.com in ~/.netrc: ~/.netrc names the machine github.com — remove that entry: "
                                 "a seat's git would read it before the seat's helper, and act as you (office/README.md §6)"])
        self.assertEqual(among, alone)
        self.assertEqual(default, ["to do  no github.com in ~/.netrc: ~/.netrc has a default entry, which git takes for github.com — remove that entry: "
                                   "a seat's git would read it before the seat's helper, and act as you (office/README.md §6)"])
        self.assertEqual(others, [])
        self.assertEqual(none, [])
        for text in (GITHUB_ALONE, AMONG_OTHERS, AS_DEFAULT, OTHERS_ONLY):
            output = run(machine, text)
            self.assertNotIn(LOGIN, output)
            self.assertNotIn(PASSWORD, output)


class TheNetrcReading(unittest.TestCase):
    """The slices behind the office check's line: how `~/.netrc` is read, by the machines' names only."""
    def test_a_name_is_read_quoted_and_in_any_case_a_macros_body_and_a_value_are_passed_over_and_default_is_seen(self):
        names = OFFICE_CHECK.netrc_names
        self.assertEqual(names('machine "GitHub.com" login a password b'), (["github.com"], False))
        self.assertEqual(names("macdef init\nmachine github.com login x\n\nmachine example.org"), (["example.org"], False))
        self.assertEqual(names("machine example.org password machine"), (["example.org"], False))
        self.assertEqual(names("machine example.org login a password b default login c password d"), (["example.org"], True))
        self.assertEqual(names(""), ([], False))


class TheFifthCase(unittest.TestCase):
    @unittest.skipUnless(COUNT, "on demand: KP_MANY=<n> runs the fifth case n times alone and n times in its module's whole run, with the agent slowed")
    def test_the_fifth_case_waits_for_the_record_it_reads_passes_every_run_with_the_agent_slowed_fails_with_its_wait_taken_out_and_its_tool_call_heard_late_and_fails_by_a_mutant_of_the_daemon(self):
        many = just(ROOT, "many", FIFTH, str(COUNT), SLOWED, timeout=7200)
        unwaited = just(ROOT, "many", FIFTH, "1", f"{FIXTURES}/the-tool-call-not-waited-for.patch", f"{FIXTURES}/the-tool-call-heard-late.patch", timeout=3600)
        red = just(ROOT, "mutant", f"{FIXTURES}/a-failed-wake-reads-its-message.patch", "kp-daemon:AfterTheReviewsSpec", timeout=3600)

        self.assertEqual(many.returncode, 0, many.stdout[-6000:])
        self.assertIn(f"— alone {COUNT} of {COUNT} passed · in its module's whole run {COUNT} of {COUNT} passed", many.stdout)
        self.assertEqual(unwaited.returncode, 1, unwaited.stdout[-6000:])
        self.assertEqual(red.returncode, 0, red.stdout[-6000:])
        self.assertIn("a wake that fails leaves its message unread", red.stdout.split("red — the specs that failed:")[-1])


def run(machine, netrc):
    """The office check's whole output with [netrc] as the home's `~/.netrc` — none when it is None."""
    path = machine.home / ".netrc"
    path.unlink(missing_ok=True)
    if netrc is not None:
        path.write_text(netrc)
        path.chmod(0o600)
    result = machine.office_check()
    return result.stdout + result.stderr


def netrc_lines(machine, netrc):
    """The office check's lines that speak of `~/.netrc`."""
    return [line for line in run(machine, netrc).splitlines() if ".netrc" in line]


if __name__ == "__main__":
    unittest.main()
