"""The office check's other paths, on the same fake machine as the S0.3 specs — for the next developer."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("office_specs", Path(__file__).with_name("test_s0_3_the_office.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)
FakeMachine, USER, ADAPTER, PIN, SIDE_CHANNELS = _specs.FakeMachine, _specs.USER, _specs.ADAPTER, _specs.PIN, _specs.SIDE_CHANNELS


class OfficeCheckTests(unittest.TestCase):
    def setUp(self):
        self.machine = FakeMachine(self, roots=["worktrees", "scratch", "seats", "secrets"], linger="yes")
        self.secrets = self.machine.home / ".config/keporkak/secrets"
        self.secrets.chmod(0o700)
        self.machine.install_adapter()
        self.machine.put_token()

    def lines(self):
        return self.machine.office_check().stdout.splitlines()

    def test_a_machine_where_bora_did_his_steps_but_roots_lacks_only_those(self):
        self.machine.stub("systemctl", "echo loaded")
        result = self.machine.office_check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertEqual([line.split(":")[0] for line in result.stdout.splitlines() if line.startswith("to do")],
                         [f"to do  kp root-owned and not writable by {USER}", "to do  sudo asks every time for kp"])

    def test_every_home_whose_compiled_permissions_deny_kp_and_sudo_by_a_border_is_ok(self):
        self.machine.put_home("reviewer-keporkak", SIDE_CHANNELS)
        self.assertIn(f"ok     seats' permissions deny kp and sudo: 2 homes read from {self.machine.homes}; each denies `kp approve 1` and `sudo kp approve 1`, by border side-channels", self.lines())

    def test_a_home_that_lets_kp_or_sudo_through_is_to_do(self):
        self.machine.put_home("deputy-keporkak", {**SIDE_CHANNELS, "rules": [{**SIDE_CHANNELS["rules"][0], "command": "(^|[;&|(])\\s*kp(\\s|$)"}]})
        self.assertIn("to do  seats' permissions deny kp and sudo: deputy-keporkak does not deny `sudo kp approve 1` by a border"
                      " — a persona's deny: or the packaging data changes through a stage (office/README.md §5)", self.lines())

    def test_a_home_whose_permissions_the_hook_cannot_read_is_to_do_though_the_hook_denies(self):
        self.machine.put_home("pool-keporkak", "{not json")
        self.assertIn("to do  seats' permissions deny kp and sudo: pool-keporkak does not deny `kp approve 1` by a border; pool-keporkak does not deny `sudo kp approve 1` by a border"
                      " — a persona's deny: or the packaging data changes through a stage (office/README.md §5)", self.lines())

    def test_no_home_to_read_is_to_do(self):
        empty = self.machine.base / "no-homes"
        empty.mkdir()
        self.machine.homes = empty
        self.assertIn(f"to do  seats' permissions deny kp and sudo: no home's permissions.json to read in {empty} — a home is built by kp-driver (office/README.md §5)", self.lines())

    def test_a_secrets_root_open_to_others_is_to_do(self):
        self.secrets.chmod(0o755)
        self.assertIn("to do  root secrets ~/.config/keporkak/secrets: chmod 0700 ~/.config/keporkak/secrets", self.lines())

    def test_a_missing_project_checkout_or_effort_folder_is_to_do(self):
        project = self.machine.home / _specs.PROJECT
        (project / "toy").rmdir()
        self.assertIn("to do  project folder TOY ~/Dev/keporkak/project/toy: git -C ~/Dev/keporkak/project pull --ff-only", self.lines())
        (project / ".git").rmdir()
        lines = self.lines()
        self.assertIn("to do  project checkout ~/Dev/keporkak/project: clone the project repository to ~/Dev/keporkak/project (docs/profiles/ names it)", lines)
        self.assertFalse(any(line.startswith(("ok     project folder", "to do  project folder")) for line in lines))

    def test_a_token_in_place_is_ok_and_one_open_to_others_on_two_lines_or_missing_is_to_do(self):
        token = self.secrets / "claude-token"
        self.assertIn("ok     secret claude-token", self.lines())
        token.chmod(0o644)
        self.assertIn("to do  secret claude-token: chmod 0600 ~/.config/keporkak/secrets/claude-token", self.lines())
        token.chmod(0o600)
        token.write_text("not a real\ntoken\n")
        self.assertIn("to do  secret claude-token: it holds 2 lines, not one; claude setup-token, then: install -m 0600 /dev/stdin ~/.config/keporkak/secrets/claude-token (office/README.md)", self.lines())
        token.unlink()
        self.assertIn("to do  secret claude-token: claude setup-token, then: install -m 0600 /dev/stdin ~/.config/keporkak/secrets/claude-token (office/README.md)", self.lines())

    def test_the_adapter_installed_at_the_pin_is_ok_and_at_another_version_is_to_do(self):
        self.assertIn(f"ok     Claude adapter {ADAPTER}: {PIN}, as the roster and the lockfile name; it bundles Claude Code 2.1.322", self.lines())
        self.machine.install_adapter("0.0.1")
        self.assertIn(f"to do  Claude adapter {ADAPTER}: installed 0.0.1, the roster and the lockfile name {PIN}: just adapter", self.lines())

    def test_a_roster_and_a_lockfile_that_name_different_adapter_versions_is_to_do(self):
        roster = self.machine.pool / "roster.yaml"
        roster.write_text(roster.read_text().replace(f'adapter_version: "{PIN}"', 'adapter_version: "0.0.1"'))
        self.assertIn(f"to do  Claude adapter {ADAPTER}: pool/roster.yaml names 0.0.1 and office/adapter/package-lock.json {PIN}: correct the repository so they name one version (contracts.md §9)", self.lines())

    def test_a_missing_claude_cli_is_to_do(self):
        (self.machine.bin / "claude").unlink()
        self.assertIn("to do  Claude CLI: curl -fsSL https://claude.ai/install.sh | bash", self.lines())

    def test_a_jdk_other_than_21_is_to_do(self):
        self.machine.stub("java", "echo 'openjdk version \"17.0.9\" 2023-10-17' >&2")
        self.assertIn('to do  JDK 21: found openjdk version "17.0.9" 2023-10-17; mise use -g java@temurin-21', self.lines())

    def test_a_missing_tool_is_to_do_with_its_install_command(self):
        (self.machine.bin / "jq").unlink()
        self.assertIn("to do  jq: sudo apt install jq", self.lines())

    def test_a_seat_home_whose_claude_json_names_an_mcp_server_is_to_do(self):
        seat = self.machine.home / ".local/share/keporkak/seats/coder-1"
        seat.mkdir(parents=True)
        (seat / ".claude.json").write_text('{"userID": "x", "projects": {"/wt": {"mcpServers": {"slack": {}}}}, "mcpServers": {"poison": {"type": "http"}}}')
        self.assertIn("to do  no vendor global configuration reachable from a seat's config home: ~/.local/share/keporkak/seats/coder-1/.claude.json names poison, slack"
                      " — remove them: a seat's home is built at every start, never edited by hand (office/README.md)", self.lines())
        (seat / ".claude.json").write_text('{"userID": "x", "projects": {"/wt": {"mcpServers": {}}}}')
        self.assertIn("ok     no vendor global configuration reachable from a seat's config home: a seat reads its config home under ~/.local/share/keporkak/seats,"
                      " not ~/.claude (S2.2's probe); no home names an MCP server", self.lines())

    def test_a_seats_root_inside_the_office_users_claude_folder_is_to_do(self):
        offices = self.machine.pool / "offices.yaml"
        offices.write_text(offices.read_text().replace("seats: ~/.local/share/keporkak/seats", "seats: ~/.claude/seats"))
        (self.machine.home / ".claude/seats").mkdir(parents=True)
        self.assertIn("to do  no vendor global configuration reachable from a seat's config home: roots.seats ~/.claude/seats is ~/.claude or inside it:"
                      " name a folder outside it in pool/offices.yaml", self.lines())

    def test_no_kp_on_path_is_to_do_with_just_install_and_the_sudo_step(self):
        line = next(line for line in self.lines() if line.startswith("to do  kp root-owned"))
        self.assertEqual(line, f"to do  kp root-owned and not writable by {USER}: no kp on PATH: just install, then sudo install -o root -g root -m 0755 {ROOT}/scripts/kp /usr/local/bin/kp (office/README.md §3)")

    def test_a_kp_elsewhere_than_where_the_sudo_rule_names_it_is_to_do(self):
        self.machine.stub("kp", "exit 0")
        line = next(line for line in self.lines() if line.startswith("to do  kp root-owned"))
        self.assertEqual(line, f"to do  kp root-owned and not writable by {USER}: the kp on PATH is {self.machine.bin}/kp, not /usr/local/bin/kp, where the sudo rule names it:"
                               f" sudo install -o root -g root -m 0755 {ROOT}/scripts/kp /usr/local/bin/kp, and keep it the only kp on PATH")

    def test_a_sudo_drop_in_that_is_not_roots_not_0440_not_the_templates_or_not_last_is_to_do(self):
        drop_in = self.machine.sudoers / "zz-keporkak-kp"
        drop_in.write_text("bora ALL=(root) NOPASSWD: /usr/local/bin/kp\n")
        drop_in.chmod(0o644)
        (self.machine.sudoers / "zzz-later").write_text("")
        (self.machine.sudoers / "zzz-skipped.bak").write_text("")
        line = next(line for line in self.lines() if line.startswith("to do  sudo asks"))
        self.assertEqual(line, f"to do  sudo asks every time for kp: {drop_in}: it is not root:root's; its mode is 0644, not 0440; it is not the size of the template;"
                               f" zzz-later sorts after it — sudo visudo -cf {ROOT}/office/sudoers.d/zz-keporkak-kp && sudo install -o root -g root -m 0440"
                               f" {ROOT}/office/sudoers.d/zz-keporkak-kp {drop_in} (office/README.md §2)")

    def test_a_loaded_service_whose_launcher_is_gone_is_to_do_and_one_whose_launcher_is_there_is_ok(self):
        self.machine.stub("systemctl", 'case "$*" in *ExecStart*) echo "{ path=/nowhere/kp-daemon ; argv[]=/nowhere/kp-daemon run ; ignore_errors=no }";; *) echo loaded;; esac')
        self.assertIn("to do  systemd user service keporkak.service: its launcher /nowhere/kp-daemon is not there: just install (office/README.md §3)", self.lines())
        launcher = self.machine.base / "kp-daemon"
        launcher.write_text("")
        self.machine.stub("systemctl", f'case "$*" in *ExecStart*) echo "{{ path={launcher} ; argv[]={launcher} run ; ignore_errors=no }}";; *) echo loaded;; esac')
        self.assertIn(f"ok     systemd user service keporkak.service: loaded; it starts {launcher}", self.lines())

    def test_the_office_checks_build_of_the_homes_leaves_nothing_behind(self):
        """Issue #24: without --homes the check builds every persona's home with kp-driver's builder, in a temporary folder
        it removes; the machine is as it was, and so is the temporary folder (the checkout's build/ is Gradle's)."""
        java_home = next(line.split("=", 1)[1].strip() for line in subprocess.run(["java", "-XshowSettings:properties", "-version"], text=True, capture_output=True).stderr.splitlines() if "java.home =" in line)
        (self.machine.bin / "java").unlink()
        temporary = self.machine.base / "tmp"
        temporary.mkdir()
        before = self.machine.snapshot()
        environment = {"HOME": str(self.machine.home), "PATH": f"{self.machine.bin}:{java_home}/bin:/usr/bin:/bin", "LANG": "C.UTF-8", "TMPDIR": str(temporary),
                       "JAVA_HOME": java_home, "GRADLE_USER_HOME": os.environ.get("GRADLE_USER_HOME") or str(Path.home() / ".gradle")}
        result = subprocess.run([sys.executable, str(ROOT / "scripts/office-check.py"), "--pool", str(self.machine.pool), "--adapter", str(self.machine.adapter),
                                 "--office", "panda", "--sudoers", str(self.machine.sudoers)], cwd=ROOT, env=environment, text=True, capture_output=True, timeout=900)
        line = next(line for line in result.stdout.splitlines() if "seats' permissions deny kp and sudo" in line)
        self.assertTrue(line.startswith("ok     seats' permissions deny kp and sudo: "), line)
        self.assertIn("homes built from this checkout's personas and profiles", line)
        self.assertEqual(list(temporary.iterdir()), [])
        # The temporary folder's own time moves: a folder was made in it and removed. Nothing else on the machine moves.
        after = self.machine.snapshot()
        changed = {path for path in set(before) | set(after) if before.get(path) != after.get(path)}
        self.assertEqual(changed, {"tmp"})

    def test_the_live_targets_context_and_a_loaded_service_are_ok(self):
        self.machine.stub("kubectl", "echo dsk; echo bp-dsk")
        self.machine.stub("systemctl", "echo loaded")
        lines = self.lines()
        self.assertIn("ok     kubectl context bp-dsk", lines)
        self.assertIn("ok     systemd user service keporkak.service: loaded", lines)

    def test_an_unknown_office_is_refused_without_a_report(self):
        result = subprocess.run([sys.executable, "scripts/office-check.py", "--pool", str(self.machine.pool), "--office", "mac"], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("cannot read office 'mac'", result.stderr)


if __name__ == "__main__":
    unittest.main()
