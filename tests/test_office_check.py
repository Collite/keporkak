"""The office check's other paths, on the same fake machine as the S0.3 specs — for the next developer."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("office_specs", Path(__file__).with_name("test_s0_3_the_office.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)
FakeMachine, USER = _specs.FakeMachine, _specs.USER


class OfficeCheckTests(unittest.TestCase):
    def setUp(self):
        self.machine = FakeMachine(self, roots=["worktrees", "scratch", "seats", "secrets"], linger="yes")
        self.secrets = self.machine.home / ".config/keporkak/secrets"
        self.secrets.chmod(0o700)

    def lines(self):
        return self.machine.office_check().stdout.splitlines()

    def test_a_ready_machine_passes(self):
        result = self.machine.office_check()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_secrets_root_open_to_others_is_to_do(self):
        self.secrets.chmod(0o755)
        self.assertIn("to do  root secrets ~/.config/keporkak/secrets: chmod 0700 ~/.config/keporkak/secrets", self.lines())

    def test_a_token_in_place_is_ok_and_one_open_to_others_is_to_do(self):
        token = self.secrets / "claude-token"
        token.write_text("not a real token\n")
        token.chmod(0o600)
        self.assertIn("ok     secret claude-token", self.lines())
        token.chmod(0o644)
        self.assertIn("to do  secret claude-token: chmod 0600 ~/.config/keporkak/secrets/claude-token", self.lines())

    def test_a_roster_model_missing_from_the_cli_catalog_is_to_do(self):
        self.machine.stub("claude", 'echo "2.1.325 (Claude Code)"\n# {id:"claude-haiku-5-5"} {id:"claude-sonnet-5-5"}')
        self.assertIn("to do  Claude CLI: 2.1.325 (Claude Code); its catalog lacks claude-opus-5-5: claude update, or correct pool/roster.yaml", self.lines())

    def test_a_jdk_other_than_21_is_to_do(self):
        self.machine.stub("java", "echo 'openjdk version \"17.0.9\" 2023-10-17' >&2")
        self.assertIn('to do  JDK 21: found openjdk version "17.0.9" 2023-10-17; mise use -g java@temurin-21', self.lines())

    def test_a_missing_tool_is_to_do_with_its_install_command(self):
        (self.machine.bin / "jq").unlink()
        self.assertIn("to do  jq: sudo apt install jq", self.lines())

    def test_a_kp_the_office_user_can_write_is_to_do(self):
        self.machine.stub("kp", "exit 0")
        line = next(line for line in self.lines() if line.startswith("to do  kp root-owned"))
        self.assertEqual(line, f"to do  kp root-owned and not writable by {USER}: sudo install -o root -g root -m 0755 {self.machine.bin}/kp /usr/local/bin/kp, and keep it the only kp on PATH")

    def test_the_live_targets_context_and_a_loaded_service_are_ok(self):
        self.machine.stub("kubectl", "echo dsk; echo bp-dsk")
        self.machine.stub("systemctl", "echo loaded")
        lines = self.lines()
        self.assertIn("ok     kubectl context bp-dsk", lines)
        self.assertIn("ok     systemd user service keporkak.service", lines)

    def test_an_unknown_office_is_refused_without_a_report(self):
        result = subprocess.run([sys.executable, "scripts/office-check.py", "--pool", str(self.machine.pool), "--office", "mac"], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("cannot read office 'mac'", result.stderr)


if __name__ == "__main__":
    unittest.main()
