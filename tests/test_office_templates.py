"""The templates in office/ stay installable."""
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
VISUDO = shutil.which("visudo") or next((p for p in ("/usr/sbin/visudo", "/sbin/visudo") if Path(p).exists()), None)


class OfficeTemplateTests(unittest.TestCase):
    @unittest.skipUnless(VISUDO, "visudo is not installed")
    def test_the_sudoers_drop_in_parses(self):
        result = subprocess.run([VISUDO, "-cf", "office/sudoers.d/zz-keporkak-kp"], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_the_sudoers_drop_in_has_a_name_sudo_reads_and_sorts_last(self):
        name = "zz-keporkak-kp"
        self.assertTrue((ROOT / "office/sudoers.d" / name).is_file())
        self.assertNotIn(".", name)
        self.assertFalse(name.endswith("~"))

    def test_the_unit_names_the_two_placeholders_just_install_replaces_and_a_path_with_mises_shims(self):
        unit = (ROOT / "office/systemd/keporkak.service").read_text()
        self.assertIn("ExecStart=@DAEMON_LAUNCHER@", unit)
        self.assertIn("WorkingDirectory=@DAEMON_WORKING_DIRECTORY@", unit)
        self.assertIn("Environment=PATH=%h/.local/share/mise/shims:%h/.local/bin:", unit)
        install = (ROOT / "scripts/install.py").read_text()
        self.assertIn('"@DAEMON_LAUNCHER@"', install)
        self.assertIn('"@DAEMON_WORKING_DIRECTORY@"', install)


if __name__ == "__main__":
    unittest.main()
