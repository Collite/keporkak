"""KP/P3/S3.12 T1 — the office is dsk (Bora reads these): the office check and the build's Python.

Seams: `scripts/office-check.py` (behind `just office-check`) on a fixture pool, on the S0.3 specs' fake machine — the
office it answers for shows in the roots it reports; `build-support/bootstrap_python.py` (behind every recipe that runs
Python) in a copy of the checkout's `build-support/` and `requirements.txt`, with a `python3.14` on the path that answers
as Ubuntu's does without its `python3.14-venv` package. The pool's lines and `kp status` are TheOfficeIsDskSpec's.
"""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("office_specs", Path(__file__).with_name("test_s0_3_the_office.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)
FakeMachine, USER = _specs.FakeMachine, _specs.USER
# The host's short name as uname gives it: what `just office-check` with no flag answers for.
HOST = os.uname().nodename.split(".")[0]


class TheOfficeIsDsk(unittest.TestCase):
    def test_the_office_check_answers_for_the_office_it_is_asked_for_and_with_no_flag_for_the_hosts(self):
        machine = FakeMachine(self, roots=["worktrees", "scratch"], linger="yes")
        machine.bora_does_the_to_dos()

        # An office the pool names: each answers with its own roots.
        machine.pool.joinpath("offices.yaml").write_text(pool(office("here", HERE), office("there", THERE)))
        here = roots(office_check(machine, "--office", "here"))
        self.assertEqual(here, [
            "ok     root worktrees ~/Dev/keporkak/_wt", "ok     root scratch ~/Dev/_scratch",
            "ok     root seats ~/.local/share/keporkak/seats", "ok     root secrets ~/.config/keporkak/secrets"])
        self.assertEqual(roots(office_check(machine, "--office", "there")), [
            "to do  root worktrees ~/Elsewhere/_wt: mkdir -p ~/Elsewhere/_wt", "to do  root scratch ~/Elsewhere/_scratch: mkdir -p ~/Elsewhere/_scratch",
            "to do  root seats ~/Elsewhere/seats: mkdir -p ~/Elsewhere/seats", "to do  root secrets ~/Elsewhere/secrets: mkdir -p -m 0700 ~/Elsewhere/secrets"])

        # One it does not name: one line that says so, and no report.
        nowhere = office_check(machine, "--office", "nowhere")
        self.assertEqual((nowhere.returncode, nowhere.stdout), (2, ""))
        self.assertEqual(nowhere.stderr, f"office check: cannot read office 'nowhere' from {machine.pool} ('nowhere'); run `just rules`, or pass --office\n")

        # Two offices with the same paths: each answers for its roots, and the two answers are the same.
        machine.pool.joinpath("offices.yaml").write_text(pool(office("panda", HERE), office("dsk", HERE)))
        panda, dsk = office_check(machine, "--office", "panda"), office_check(machine, "--office", "dsk")
        self.assertEqual((roots(panda), roots(dsk)), (here, here))
        self.assertEqual(panda.stdout, dsk.stdout)

        # With no flag, the host's — not the first the pool names.
        machine.pool.joinpath("offices.yaml").write_text(pool(office("first", HERE), office(HOST, THERE)))
        self.assertEqual(roots(office_check(machine))[0], "to do  root worktrees ~/Elsewhere/_wt: mkdir -p ~/Elsewhere/_wt")

    def test_the_builds_python_where_python3_has_no_venv_says_in_one_line_what_to_do_and_no_traceback(self):
        checkout = venvless_checkout(self)

        first = bootstrap(checkout)
        second = bootstrap(checkout)

        line = (f"build/python: {checkout / 'bin/python3.14'} cannot make a virtual environment: its venv module or ensurepip is missing."
                " Set KP_PYTHON to a Python 3.11–3.14 that has both, or install the system's package: sudo apt install python3.14-venv")
        self.assertEqual((first.returncode, first.stdout + first.stderr), (1, line + "\n"))
        self.assertEqual((second.returncode, second.stdout + second.stderr), (1, line + "\n"))
        self.assertFalse((checkout / "build/python").exists())


# The roots the fake machine has once Bora's steps are done — the checkout's pool's — and roots it has not.
HERE = "{worktrees: ~/Dev/keporkak/_wt, scratch: ~/Dev/_scratch, seats: ~/.local/share/keporkak/seats, secrets: ~/.config/keporkak/secrets}"
THERE = "{worktrees: ~/Elsewhere/_wt, scratch: ~/Elsewhere/_scratch, seats: ~/Elsewhere/seats, secrets: ~/Elsewhere/secrets}"


def office(name, roots):
    """An office of the pool's shape with [roots], the rest as the checkout's pool has it."""
    return f"""\
  "{name}":
    os: linux
    user: {USER}
    roots: {roots}
    project: {{checkout: ~/Dev/keporkak/project, efforts: {{KP: ".", TOY: toy}}}}
    repos: {{keporkak: ~/Dev/keporkak/keporkak, kp-toy: ~/Dev/keporkak/kp-toy}}
    live_targets: [bp-dsk]
    computer_use: "off"
    qa: {{vendor: claude}}
    listen: {{mcp: "127.0.0.1:7711", board: "127.0.0.1:7712"}}
    watch: {{every: 30s}}
"""


def pool(*offices):
    return "version: 1\noffices:\n" + "".join(offices)


def office_check(machine, *flags):
    """`just office-check` on the fake machine, with [flags] for --office."""
    environment = {"HOME": str(machine.home), "PATH": str(machine.bin), "LANG": "C.UTF-8"}
    return subprocess.run([sys.executable, str(ROOT / "scripts/office-check.py"), "--pool", str(machine.pool), "--adapter", str(machine.adapter),
                           "--homes", str(machine.homes), "--sudoers", str(machine.sudoers), *flags], cwd=ROOT, env=environment, text=True, capture_output=True)


def roots(result):
    """The report's lines about the office's roots: an outcome in six columns, a space, then the item."""
    return [line for line in result.stdout.splitlines() if line[7:].startswith("root ")]


# Ubuntu's python3.14 without its python3.14-venv package, as `-m venv` answers: the folder is made, then ensurepip is
# missing. It runs on the suite's own interpreter, so that it needs nothing on the path it is found on.
VENVLESS = f"""#!{sys.executable}
import os, sys
if sys.argv[1:] == ["--version"]:
    print("Python 3.14.4")
elif sys.argv[1:3] == ["-m", "venv"]:
    os.makedirs(os.path.join(sys.argv[3], "bin"), exist_ok=True)
    os.symlink(sys.argv[0], os.path.join(sys.argv[3], "bin/python"))
    print('''The virtual environment was not successfully created because ensurepip is not
available.  On Debian/Ubuntu systems, you need to install the python3-venv
package using the following command.

    apt install python3.14-venv

You may need to use sudo with that command.  After installing the python3-venv
package, recreate your virtual environment.

Failing command: ''' + os.path.join(sys.argv[3], "bin/python3.14"))
    sys.exit(1)
"""


def venvless_checkout(test):
    """A copy of the bootstrap and the requirements, beside a bin/ whose only Python cannot make a venv."""
    checkout = Path(tempfile.mkdtemp(dir=ROOT / "build"))
    test.addCleanup(shutil.rmtree, checkout)
    (checkout / "build-support").mkdir()
    shutil.copy(ROOT / "build-support/bootstrap_python.py", checkout / "build-support")
    shutil.copy(ROOT / "requirements.txt", checkout)
    (checkout / "bin").mkdir()
    (checkout / "bin/python3.14").write_text(VENVLESS)
    (checkout / "bin/python3.14").chmod(0o755)
    return checkout


def bootstrap(checkout):
    """`just`'s `_python-env` in [checkout]: KP_PYTHON unset, the path its bin/ alone."""
    return subprocess.run([sys.executable, str(checkout / "build-support/bootstrap_python.py")], cwd=checkout,
                          env={"PATH": str(checkout / "bin"), "LANG": "C.UTF-8"}, text=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
