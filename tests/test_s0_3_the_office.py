"""S0.3 — The office. The five specs of the stage list, each named as its sentence.

Seams: `scripts/check-rules.py --pool <dir>` validates the three `pool/` files; `scripts/office-check.py` (behind
`just office-check`) reports on a machine. Here the machine is fake: a temporary home and a PATH of stub tools that
answer as Panda's did on 2026-11-14 (FakeMachine, at the bottom of this file).
"""
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
USER = getpass.getuser()
ADAPTER = "@agentclientprotocol/claude-agent-acp"
PIN = re.search(r'adapter_version: "?([^"\s]+)', (ROOT / "pool/roster.yaml").read_text()).group(1)


class TheOffice(unittest.TestCase):
    def test_a_roster_whose_review_floor_is_not_a_model_of_its_order_fails_the_check(self):
        pool = PandaPool(self)
        panda = pool.check()
        self.assertEqual(panda.returncode, 0, panda.stdout)
        self.assertIn("ok   pool/roster.yaml", panda.stdout)
        pool.edit("roster.yaml", "floor: claude-opus-5-5", "floor: claude-opus-9")
        result = pool.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL pool/roster.yaml: review floor 'claude-opus-9' is not a model of the order", result.stdout)

    def test_an_office_without_one_of_its_roots_or_with_an_unknown_computer_use_class_fails_the_check(self):
        pool = PandaPool(self)
        pool.edit("offices.yaml", "      secrets: ~/.config/keporkak/secrets\n", "")
        pool.edit("offices.yaml", 'computer_use: "off"', 'computer_use: "D"')
        result = pool.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL pool/offices.yaml: panda: roots lack 'secrets'", result.stdout)
        self.assertIn("FAIL pool/offices.yaml: panda: unknown computer_use class 'D'", result.stdout)

    def test_a_budget_that_names_an_unknown_subscription_or_office_fails_the_check(self):
        pool = PandaPool(self)
        pool.edit("budgets.yaml", "per_subscription: {claude: 4}", "per_subscription: {claude: 4, codex: 2}")
        pool.edit("budgets.yaml", "per_office: {panda: 4}", "per_office: {panda: 4, mac: 2}")
        result = pool.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("FAIL pool/budgets.yaml: per_subscription names unknown subscription 'codex'", result.stdout)
        self.assertIn("FAIL pool/budgets.yaml: per_office names unknown office 'mac'", result.stdout)

    def test_the_office_check_prints_one_line_per_item_ok_to_do_by_bora_or_delivered_by_a_later_stage_and_fails_when_anything_is_left_to_do(self):
        machine = FakeMachine(self, roots=["worktrees", "scratch"], linger="no")
        today = machine.office_check()
        self.assertEqual(today.returncode, 1)
        self.assertEqual(today.stdout, f"""\
ok     JDK 21: openjdk version "21.0.12.1" 2026-08-18 LTS
ok     Node: v24.22.0
to do  Claude adapter {ADAPTER}: not installed, the roster and the lockfile name {PIN}: just adapter
ok     Claude CLI: 2.1.325 (Claude Code)
ok     git: git version 2.43.0
ok     gh: gh version 2.102.0 (2026-09-30)
ok     just: just 1.59.0
ok     jq: jq-1.7
ok     tmux: tmux 3.4
ok     Python 3: Python 3.13.16
ok     root worktrees ~/Dev/keporkak/_wt
ok     root scratch ~/Dev/_scratch
to do  root seats ~/.local/share/keporkak/seats: mkdir -p ~/.local/share/keporkak/seats
to do  root secrets ~/.config/keporkak/secrets: mkdir -p -m 0700 ~/.config/keporkak/secrets
ok     project checkout ~/Dev/keporkak/project
ok     project folder KP ~/Dev/keporkak/project
ok     project folder TOY ~/Dev/keporkak/project/toy
to do  secret claude-token: claude setup-token, then: install -m 0600 /dev/stdin ~/.config/keporkak/secrets/claude-token (office/README.md)
later  secrets apps/kp-merger.pem, apps/kp-coder.pem, apps/kp-reviewer.pem, apps/kp-gardener.pem: S3.2 — the four GitHub Apps' keys
later  kp root-owned and not writable by {USER}: S1.5 installs kp
later  sudo asks every time for kp: S1.5 — Bora installs office/sudoers.d/zz-keporkak-kp before it (office/README.md)
ok     seats' permissions deny kp and sudo: 1 home read from {machine.homes}; each denies `kp approve 1` and `sudo kp approve 1`, by border side-channels
later  kubectl context bp-dsk: S4.1
later  systemd user service keporkak.service: S1.5 delivers it (template office/systemd/keporkak.service)
to do  lingering for {USER}: sudo loginctl enable-linger {USER}
ok     no vendor global configuration reachable from a seat's config home: a seat reads its config home under ~/.local/share/keporkak/seats, not ~/.claude (S2.2's probe); no home names an MCP server
RESULT: NOT READY — 5 to do, 5 later, 16 ok
""")
        machine.bora_does_the_to_dos()
        done = machine.office_check()
        self.assertEqual(done.returncode, 0, done.stdout)
        self.assertEqual(done.stdout.splitlines()[-1], "RESULT: READY — 0 to do, 5 later, 21 ok")

    def test_the_office_check_changes_nothing_on_the_machine(self):
        machine = FakeMachine(self, roots=["worktrees", "scratch"], linger="no")
        before = machine.snapshot()
        machine.office_check()
        machine.office_check()
        self.assertEqual(machine.snapshot(), before)


class PandaPool:
    """A copy of the repo's pool/ that a spec may edit, and the validator run over it."""
    def __init__(self, test):
        self.test = test
        self.dir = Path(tempfile.mkdtemp(dir=ROOT / "build")) / "pool"
        test.addCleanup(shutil.rmtree, self.dir.parent)
        shutil.copytree(ROOT / "pool", self.dir)
    def edit(self, name, old, new):
        path = self.dir / name
        self.test.assertIn(old, path.read_text())
        path.write_text(path.read_text().replace(old, new))
    def check(self):
        return subprocess.run([sys.executable, "scripts/check-rules.py", "--pool", str(self.dir)], cwd=ROOT, text=True, capture_output=True)


# What each tool printed on Panda, 2026-11-14. `sudo` is a tripwire: a call leaves a file in the home.
STUBS = {
    "java": "echo 'openjdk version \"21.0.12.1\" 2026-08-18 LTS' >&2",
    "node": "echo v24.22.0",
    "claude": 'echo "2.1.325 (Claude Code)"',
    "git": "echo git version 2.43.0",
    "gh": "echo 'gh version 2.102.0 (2026-09-30)'",
    "just": "echo just 1.59.0",
    "jq": "echo jq-1.7",
    "tmux": "echo tmux 3.4",
    "python3": "echo Python 3.13.16",
    "kubectl": "echo dsk",
    "systemctl": "echo not-found",
    "sudo": ': > "$HOME/sudo-was-called"',
}
# A home's compiled permissions as kp-driver writes them, cut to one rule: what the office check reads (--homes).
SIDE_CHANNELS = {"version": 1, "working_directory": "/office/_wt/keporkak-S2.6", "paths": ["file_path"], "rules": [
    {"border": "side-channels", "denies": "tmux, ssh, claude, kp, sudo", "tool": "^(Bash)$", "command": "(^|[;&|(])\\s*(\\S*/)?(kp|sudo)(\\s|$)"}]}
ROOTS = {"worktrees": "Dev/keporkak/_wt", "scratch": "Dev/_scratch", "seats": ".local/share/keporkak/seats", "secrets": ".config/keporkak/secrets"}
PROJECT = "Dev/keporkak/project"


class FakeMachine:
    def __init__(self, test, roots, linger):
        self.base = Path(tempfile.mkdtemp(dir=ROOT / "build"))
        test.addCleanup(shutil.rmtree, self.base)
        self.home, self.bin, self.pool, self.adapter = self.base / "home", self.base / "bin", self.base / "pool", self.base / "adapter"
        self.homes = self.base / "homes"
        self.put_home("coder-keporkak", SIDE_CHANNELS)
        self.home.mkdir()
        self.bin.mkdir()
        shutil.copytree(ROOT / "pool", self.pool)
        self.adapter.mkdir()
        for name in ("package.json", "package-lock.json"):
            shutil.copy(ROOT / "office/adapter" / name, self.adapter / name)
        offices = self.pool / "offices.yaml"
        offices.write_text(offices.read_text().replace("user: bora", f"user: {USER}"))
        for name, script in STUBS.items():
            self.stub(name, script)
        self.stub("loginctl", f"echo {linger}")
        for name in roots:
            (self.home / ROOTS[name]).mkdir(parents=True)
        (self.home / PROJECT / ".git").mkdir(parents=True)
        (self.home / PROJECT / "toy").mkdir()
    def stub(self, name, script):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + script + "\n")
        path.chmod(0o755)
    def bora_does_the_to_dos(self):
        (self.home / ROOTS["seats"]).mkdir(parents=True)
        (self.home / ROOTS["secrets"]).mkdir(parents=True, mode=0o700)
        (self.home / ROOTS["secrets"]).chmod(0o700)
        self.stub("loginctl", "echo yes")
        self.install_adapter()
        self.put_token()
    def install_adapter(self, version=PIN):
        """What `just adapter` leaves behind, as far as the check reads it: the adapter's and its SDK's package files."""
        for name, fields in ((ADAPTER, {"version": version}), ("@anthropic-ai/claude-agent-sdk", {"claudeCodeVersion": "2.1.322"})):
            folder = self.adapter / "node_modules" / name
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "package.json").write_text(json.dumps({"name": name, **fields}))
    def put_home(self, seat, permissions):
        """A built home in the folder the check reads with --homes: its permissions.json, as text or as JSON."""
        (self.homes / seat).mkdir(parents=True, exist_ok=True)
        (self.homes / seat / "permissions.json").write_text(permissions if isinstance(permissions, str) else json.dumps(permissions))
    def put_token(self):
        token = self.home / ROOTS["secrets"] / "claude-token"
        token.write_text("not a real token\n")
        token.chmod(0o600)
    def office_check(self):
        environment = {"HOME": str(self.home), "PATH": str(self.bin), "LANG": "C.UTF-8"}
        return subprocess.run([sys.executable, str(ROOT / "scripts/office-check.py"), "--pool", str(self.pool), "--adapter", str(self.adapter), "--office", "panda", "--homes", str(self.homes)], cwd=ROOT, env=environment, text=True, capture_output=True)
    def snapshot(self):
        entries = {}
        for folder, directories, files in os.walk(self.base):
            for name in [folder] + [os.path.join(folder, item) for item in directories + files]:
                info = os.lstat(name)
                content = Path(name).read_bytes() if os.path.isfile(name) else b""
                entries[os.path.relpath(name, self.base)] = (info.st_mode, info.st_uid, info.st_size, info.st_mtime_ns, hashlib.sha256(content).hexdigest())
        return entries


if __name__ == "__main__":
    unittest.main()
