"""S1.5 T7 — `just install` (scripts/install.py): the daemon and its systemd user unit installed for the office user,
the one sudo step printed and none run. The machine is fake: a temporary home, mise's shims there, and a PATH of stubs
in which `sudo` is a tripwire and `systemctl` notes how it was called."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FakeOffice:
    def __init__(self, test, java_version='openjdk version "21.0.12.1" 2026-08-18 LTS'):
        self.base = Path(tempfile.mkdtemp(dir=ROOT / "build"))
        test.addCleanup(shutil.rmtree, self.base)
        self.home, self.bin, self.dist = self.base / "home", self.base / "bin", self.base / "dist"
        for folder in (self.home / ".local/share/mise/shims", self.bin, self.dist / "bin", self.dist / "lib"):
            folder.mkdir(parents=True)
        self.stub(self.home / ".local/share/mise/shims/java", f"echo '{java_version}' >&2")
        self.stub(self.bin / "sudo", ': > "$HOME/sudo-was-called"')
        self.stub(self.bin / "systemctl", 'echo "$@" >> "$HOME/systemctl-calls"')
        self.stub(self.dist / "bin/kp-daemon", "exit 0")
        (self.dist / "lib/kp-daemon.jar").write_text("a jar")

    @staticmethod
    def stub(path, script):
        path.write_text("#!/bin/sh\n" + script + "\n")
        path.chmod(0o755)

    def install(self):
        environment = {"HOME": str(self.home), "PATH": f"{self.bin}:/usr/bin:/bin", "LANG": "C.UTF-8"}
        return subprocess.run([sys.executable, str(ROOT / "scripts/install.py"), "--dist", str(self.dist)], cwd=ROOT, env=environment, text=True, capture_output=True)


class JustInstall(unittest.TestCase):
    def test_install_prints_its_one_sudo_step_and_runs_nothing_as_root(self):
        office = FakeOffice(self)
        result = office.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[-2:], [
            "Bora's one step, with sudo — kp, root-owned and not writable by the office user, where the sudo rule names it:",
            f"  sudo install -o root -g root -m 0755 {ROOT}/scripts/kp /usr/local/bin/kp",
        ])
        self.assertFalse((office.home / "sudo-was-called").exists())

    def test_the_unit_is_installed_with_its_launcher_and_working_folder_made_real_and_systemd_reloaded(self):
        office = FakeOffice(self)
        office.install()
        unit = (office.home / ".config/systemd/user/keporkak.service").read_text()
        launcher = office.home / ".local/share/keporkak/daemon/bin/kp-daemon"
        self.assertNotIn("@", unit)
        self.assertIn(f"ExecStart={launcher} run --root {ROOT}\n", unit)
        self.assertIn(f"WorkingDirectory={ROOT}\n", unit)
        self.assertIn("Environment=PATH=%h/.local/share/mise/shims:", unit)
        self.assertTrue(launcher.is_file())
        self.assertEqual((office.home / "systemctl-calls").read_text(), "--user daemon-reload\n")

    def test_a_second_install_replaces_the_daemon_it_installed_before(self):
        office = FakeOffice(self)
        office.install()
        (office.dist / "lib/kp-daemon.jar").unlink()
        (office.dist / "lib/kp-daemon-2.jar").write_text("another jar")
        office.install()
        self.assertEqual(sorted(p.name for p in (office.home / ".local/share/keporkak/daemon/lib").iterdir()), ["kp-daemon-2.jar"])

    def test_a_unit_path_without_a_jdk_21_installs_nothing_and_says_what_to_do(self):
        office = FakeOffice(self, java_version='openjdk version "17.0.9" 2023-10-17')
        result = office.install()
        self.assertEqual(result.returncode, 1)
        self.assertIn('not 21: mise use -g java@temurin-21 — nothing was installed', result.stderr)
        self.assertFalse((office.home / ".config/systemd/user/keporkak.service").exists())
        self.assertFalse((office.home / ".local/share/keporkak").exists())


if __name__ == "__main__":
    unittest.main()
