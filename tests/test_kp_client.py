"""S1.5 T3 — `kp`, Bora's thin client (contracts.md §6): one file, the standard library only, all logic in the daemon.

The daemon here is a fake one: a Unix socket in a temporary folder that records the request and answers as a test says.
"""
import ast
import json
import os
from pathlib import Path
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
KP = ROOT / "scripts/kp"


class FakeDaemon:
    """Accepts one connection on `socket`, keeps the request line, writes `answers` (a line each), then closes."""
    def __init__(self, test, answers, folder=None):
        self.folder = Path(folder or tempfile.mkdtemp(prefix="kp-"))
        test.addCleanup(shutil.rmtree, self.folder, True)
        self.path = self.folder / "kp.sock"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(str(self.path))
        self.server.listen(1)
        self.request = None
        self.answers = answers
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()
        test.addCleanup(self.server.close)

    def serve(self):
        connection, _ = self.server.accept()
        with connection, connection.makefile("rw", encoding="utf-8") as stream:
            self.request = json.loads(stream.readline())
            for answer in self.answers:
                stream.write(json.dumps(answer) + "\n")
                stream.flush()


def kp(*args, socket_path=None, environment=None, cwd=None):
    env = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", **(environment or {})}
    if socket_path:
        env["KP_SOCKET"] = str(socket_path)
    return subprocess.run([str(KP), *args], env=env, cwd=cwd, text=True, capture_output=True, timeout=30)


class TheKpClient(unittest.TestCase):
    def test_kp_imports_nothing_outside_the_standard_library(self):
        tree = ast.parse(KP.read_text())
        modules = {alias.name.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names}
        modules |= {node.module.split(".")[0] for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)}
        self.assertEqual(sorted(modules - set(sys.stdlib_module_names)), [])
        self.assertEqual(sorted(modules), ["json", "os", "socket", "sys"])

    def test_kp_is_one_executable_file_that_runs_the_systems_python_in_isolated_mode(self):
        self.assertEqual(KP.read_text().splitlines()[0], "#!/usr/bin/python3 -I")
        self.assertTrue(KP.stat().st_mode & stat.S_IXUSR)

    def test_kp_sends_one_request_and_prints_each_text_until_the_code(self):
        daemon = FakeDaemon(self, [{"text": "#5 · B · assignment-proposal"}, {"text": "   rolls: none"}, {"done": True, "code": 0}])
        result = kp("approve", "5", socket_path=daemon.path)
        daemon.thread.join(5)
        self.assertEqual(daemon.request, {"v": 1, "cmd": "approve", "args": ["5"], "tty": False})
        self.assertEqual(result.stdout, "#5 · B · assignment-proposal\n   rolls: none\n")
        self.assertEqual(result.returncode, 0)

    def test_kp_exits_with_the_daemons_code(self):
        daemon = FakeDaemon(self, [{"text": "refused m-panda-000009 — approval: signed by Bora's CLI"}, {"done": True, "code": 1}])
        result = kp("approve", "5", socket_path=daemon.path)
        self.assertEqual((result.returncode, result.stdout), (1, "refused m-panda-000009 — approval: signed by Bora's CLI\n"))

    def test_kp_follow_is_passed_on_and_the_lines_are_printed_as_they_come(self):
        daemon = FakeDaemon(self, [{"text": "#5"}, {"text": "#9"}, {"done": True, "code": 0}])
        result = kp("inbox", "--follow", socket_path=daemon.path)
        daemon.thread.join(5)
        self.assertEqual(daemon.request["args"], ["--follow"])
        self.assertEqual(result.stdout, "#5\n#9\n")

    def test_kp_check_names_its_logs_by_absolute_path(self):
        daemon = FakeDaemon(self, [{"done": True, "code": 0}])
        folder = tempfile.mkdtemp(prefix="kp-cwd-")
        self.addCleanup(shutil.rmtree, folder, True)
        kp("check", "mail/KP/_hand/p0-s0.3.jsonl", "/abs/log.jsonl", socket_path=daemon.path, cwd=folder)
        daemon.thread.join(5)
        self.assertEqual(daemon.request["args"], [os.path.join(os.path.realpath(folder), "mail/KP/_hand/p0-s0.3.jsonl"), "/abs/log.jsonl"])

    def test_with_no_daemon_kp_says_so_in_one_line_and_exits_non_zero(self):
        missing = Path(tempfile.mkdtemp(prefix="kp-")) / "kp.sock"
        self.addCleanup(shutil.rmtree, missing.parent, True)
        result = kp("inbox", socket_path=missing)
        self.assertEqual(result.returncode, 4)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr.count("\n"), 1)
        self.assertTrue(result.stderr.startswith(f"kp: no daemon answers at {missing} ("), result.stderr)
        self.assertIn("start it with `just run`", result.stderr)

    def test_a_connection_that_ends_without_a_code_is_not_success(self):
        daemon = FakeDaemon(self, [{"text": "half an answer"}])
        result = kp("status", socket_path=daemon.path)
        self.assertEqual(result.returncode, 4)
        self.assertIn("closed the connection without an answer", result.stderr)

    def test_without_kp_socket_kp_finds_the_socket_in_the_office_users_runtime_folder(self):
        runtime = Path(tempfile.mkdtemp(prefix="kp-run-"))
        self.addCleanup(shutil.rmtree, runtime, True)
        daemon = FakeDaemon(self, [{"done": True, "code": 0}], folder=runtime / "keporkak")
        result = kp("status", environment={"XDG_RUNTIME_DIR": str(runtime)})
        self.assertEqual(result.returncode, 0, result.stderr)
        daemon.thread.join(5)
        self.assertEqual(daemon.request["cmd"], "status")


if __name__ == "__main__":
    unittest.main()
