"""build-support/bootstrap_python.py's other path, for the next developer (S3.12 T5): `-m venv` fails for a reason
that is not a missing venv module or ensurepip — its own words are shown, then one line names the command, and no
traceback. The missing-venv case is test_s3_12_the_office_is_dsk's."""
import importlib.util
from pathlib import Path
import sys
import unittest

_spec = importlib.util.spec_from_file_location("office_is_dsk", Path(__file__).with_name("test_s3_12_the_office_is_dsk.py"))
_specs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_specs)

# A python3.14 whose venv fails as a full disk would make it: the folder half made, then an error of its own.
DISK_FULL = f"""#!{sys.executable}
import os, sys
if sys.argv[1:3] == ["-m", "venv"]:
    os.makedirs(os.path.join(sys.argv[3], "bin"), exist_ok=True)
    print("Error: [Errno 28] No space left on device", file=sys.stderr)
    sys.exit(1)
"""


class BootstrapTests(unittest.TestCase):
    def test_a_venv_that_fails_otherwise_shows_its_words_then_the_command_and_leaves_nothing(self):
        checkout = _specs.venvless_checkout(self)
        (checkout / "bin/python3.14").write_text(DISK_FULL)

        result = _specs.bootstrap(checkout)

        self.assertEqual((result.returncode, result.stdout, result.stderr), (1, "", "Error: [Errno 28] No space left on device\n"
                         f"build/python: `{checkout / 'bin/python3.14'} -m venv` failed (exit 1)\n"))
        self.assertFalse((checkout / "build/python").exists())


if __name__ == "__main__":
    unittest.main()
