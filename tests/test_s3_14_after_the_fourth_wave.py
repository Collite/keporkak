"""KP/P3/S3.14 T1 — after the fourth wave's reviews (Bora reads these): the office check's line on `~/.netrc`.

Seam: `scripts/office-check.py` (behind `just office-check`) on the S0.3 specs' fake machine, whose home is a fixture:
a `~/.netrc` written there is the one the check reads (contracts.md §12, §4.4 Amendment 47). Every entry names a login
and a password the output must not hold. The daemon's sentences are AfterTheFourthWaveSpec's.
"""
import importlib.util
from pathlib import Path
import unittest

_spec = importlib.util.spec_from_file_location("office_specs", Path(__file__).with_name("test_s0_3_the_office.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)
FakeMachine = _specs.FakeMachine

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
