#!/usr/bin/env python3
"""`just many <spec> <times> [patch]` — does a spec pass every time, or only by chance? (S3.10 T1; D-106)

Runs one spec <times> times alone, then <times> times in its module's whole run, each run with a second build busy
beside it, on a throwaway copy of the working tree (`build/many/`, removed at the end) — with [patch] applied to the copy
when one is given. The working tree is not touched, as `just mutant` leaves it.

<spec> is a Kotlin spec — `<module>:<SpecClass>`, or `<module>:<SpecClass>/<case>` to keep to its cases whose name
<case> matches (`*` a wildcard; Kotest's `kotest_filter_tests`) — or a test of the Python suite,
`python:<module>.<Class>.<test>`. A Kotlin spec's module is its Gradle module, and its whole run that module's test task
— `hero` for the heroes'; a Python test's module is its file under tests/, and its whole run every test in it.

The second build is the copy's own: a second copy of the working tree, its `kp-engine` and `kp-mailroom` specs run over
and over by a Gradle of its own for as long as the runs last.

Each run is reported as it ends — `many: alone 3 of 20 — passed`, or `failed` with the cases and the first line of each
failure — and the last line counts them: `many: <spec> — alone 20 of 20 passed · in its module's whole run 20 of 20
passed · a second build busy beside each run`. A run that has not ended within <limit> minutes (KP_MANY_LIMIT, 30) is
ended and counts as failed.
Exit: 0 every run passed · 1 a run failed · 2 the patch does not apply · 3 <spec> names no spec, or a run ran none of its
cases · 4 the copy does not build. Killed — SIGTERM, SIGINT, SIGHUP — it ends its runs and the busy build and removes
its copies, 128 and the signal's number.
Usage: python3 build-support/many.py [--from DIR] <spec> <times> [patch]
"""
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mutant import NARROWED, ROOT, Killed, copy_of_the_working_tree, includes, on_signal, owner_alive, remove, tasks_for  # noqa: E402

PASSED, FAILED, DOES_NOT_APPLY, RUNS_NOTHING, DOES_NOT_BUILD = 0, 1, 2, 3, 4
BUSY = [":kp-engine:test", ":kp-mailroom:test"]
WHOLE = "in its module's whole run"
# One test of the Python suite, or every test of its file, run with each test's outcome on a line of its own: `MANY <test>
# ok`, or `MANY <test> failed <the first line of what failed>` (stdout and stderr of the tests themselves go before it).
PYTHON_RUN = """import sys, unittest
loader = unittest.defaultTestLoader
suite = loader.loadTestsFromName(sys.argv[1])
class Each(unittest.TextTestResult):
    def addSuccess(self, test):
        super().addSuccess(test); print(f"MANY {test.id()} ok", flush=True)
    def addFailure(self, test, err):
        super().addFailure(test, err); print(f"MANY {test.id()} failed {str(err[1]).splitlines()[0] if str(err[1]) else err[0].__name__}", flush=True)
    def addError(self, test, err):
        super().addError(test, err); print(f"MANY {test.id()} failed {err[0].__name__}: {(str(err[1]).splitlines() or [''])[0]}", flush=True)
unittest.TextTestRunner(resultclass=Each, verbosity=0).run(suite)
"""


def say(text):
    print(text, flush=True)


class Spec:
    """What <spec> names: a Kotlin spec's task, class and case pattern, or a Python test's id."""

    def __init__(self, text):
        self.text = text
        self.python = text.removeprefix("python:") if text.startswith("python:") else None
        self.tasks, self.pattern, self.case = [], None, None
        if self.python is None:
            named, _, self.case = text.partition("/")
            self.tasks, self.pattern = tasks_for(named)
            self.case = self.case or None

    def names_one(self, copy):
        if self.python is not None:
            module = self.python.split(".")[0]
            return (copy / "tests" / f"{module}.py").is_file() and len(self.python.split(".")) == 3
        return len(self.tasks) == 1

    def module(self):
        return f"tests/{self.python.split('.')[0]}.py" if self.python is not None else self.tasks[0].split(":")[1]


def started(command, copy, environment=None, output=None):
    return subprocess.Popen(command, cwd=copy, text=True, stdout=output or subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True,
                            env={**os.environ, **(environment or {})})


def ended(process):
    """[process] and every process of its group ended: SIGTERM, then SIGKILL."""
    if process is None or process.poll() is not None:
        return
    for sent, wait in ((signal.SIGTERM, 30), (signal.SIGKILL, 10)):
        try:
            os.killpg(process.pid, sent)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue


def gradle(copy, *arguments):
    # A daemon of its own and Kotlin compiled inside it, as `just mutant` runs: a run ended takes all of its build with it.
    return ["./gradlew", "--console=plain", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", *arguments]


def kotlin_cases(copy, spec):
    """The cases of [spec]'s class its task ran, from the JUnit reports: (case, the first line of its failure or None)."""
    _, module, name = spec.tasks[0].split(":")
    found = []
    for report in sorted((copy / module / "build/test-results" / name).glob("TEST-*.xml")):
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            classname, title = case.get("classname"), case.get("name")
            if case.find("skipped") is not None or not re.fullmatch(spec.pattern.replace(".", r"\.").replace("*", ".*"), classname)\
                    and not re.fullmatch(spec.pattern.replace("*", ".*"), classname.rsplit(".", 1)[-1]):
                continue
            if spec.case is not None and not re.fullmatch(re.escape(spec.case).replace(r"\*", ".*"), title):
                continue
            broke = case.find("failure") if case.find("failure") is not None else case.find("error")
            found.append((title, None if broke is None else ((broke.get("message") or broke.text or "(no message)").strip().splitlines() or ["(no message)"])[0]))
    return found


def run_once(copy, spec, whole, limit):
    """One run: (the cases that ran, each with its failure or None; the run's output). A run past [limit] seconds is ended."""
    if spec.python is not None:
        target = spec.python.split(".")[0] if whole else spec.python
        process = started([str(copy / "build/python/bin/python"), "-c", PYTHON_RUN, target], copy / "tests")
    else:
        _, module, name = spec.tasks[0].split(":")
        shutil.rmtree(copy / module / "build/test-results" / name, ignore_errors=True)
        if whole:
            command = gradle(copy, spec.tasks[0], "--rerun")
            process = started(command, copy)
        else:
            command = gradle(copy, "--init-script", "build/narrowed.gradle", spec.tasks[0], "--rerun", "--tests", spec.pattern)
            # Kotest matches its filter against `<spec>/<case>`: the spec's name goes first, as any.
            process = started(command, copy, {"kotest_filter_tests": "*/" + spec.case} if spec.case else None)
    try:
        output, _ = process.communicate(timeout=limit)
    except subprocess.TimeoutExpired:
        ended(process)
        output = (process.stdout.read() if process.stdout else "") + f"\n(ended: the run had not ended within {limit // 60} minutes)"
        return [("the run", f"did not end within {limit // 60} minutes")], output
    if spec.python is not None:
        ran = re.findall(r"^MANY (\S+) (ok|failed)(?: (.*))?$", output, re.MULTILINE)
        return [(test.rsplit(".", 1)[-1], None if outcome == "ok" else why or "failed") for test, outcome, why in ran if test == spec.python], output
    return kotlin_cases(copy, spec), output


def busy(copy):
    """The second build: kp-engine's and kp-mailroom's specs, run over and over until it is ended."""
    command = " ".join(gradle(copy, *BUSY, "--rerun", "--quiet", "--continue"))
    return subprocess.Popen(["sh", "-c", f"while true; do {command} >/dev/null 2>&1; done"], cwd=copy, start_new_session=True,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main(arguments):
    origin = Path(arguments.pop(arguments.index("--from") + 1)) if "--from" in arguments else Path.cwd()
    arguments = [a for a in arguments if a != "--from"]
    if len(arguments) not in (2, 3) or not arguments[1].isdigit() or int(arguments[1]) < 1:
        say(__doc__.strip().splitlines()[-1])
        return RUNS_NOTHING
    spec, times = Spec(arguments[0]), int(arguments[1])
    patch = (origin / arguments[2]).resolve() if len(arguments) == 3 else None
    if patch is not None and not patch.is_file():
        say(f"error: no patch at {patch} — nothing was run (exit {DOES_NOT_APPLY})")
        return DOES_NOT_APPLY
    if not spec.names_one(ROOT):
        say(f"error: '{spec.text}' names no spec, or more than one module's — nothing was run (exit {RUNS_NOTHING})")
        return RUNS_NOTHING
    limit = int(os.environ.get("KP_MANY_LIMIT", "30")) * 60
    folder = ROOT / "build/many"
    folder.mkdir(parents=True, exist_ok=True)
    for stale in folder.glob("copy-*"):
        if not owner_alive(stale):
            shutil.rmtree(stale, ignore_errors=True)
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, on_signal)
    copy = Path(tempfile.mkdtemp(prefix="copy-", dir=folder))
    second = Path(tempfile.mkdtemp(prefix="copy-", dir=folder))
    beside = None
    try:
        for each in (copy, second):
            (each / ".owner").write_text(f"{os.getpid()}\n")
            copy_of_the_working_tree(each)
        # A repository of its own, as `just mutant`'s copy: git applies a patch here, and a spec that lists the working tree
        # with git (the Python suite's copies) lists this copy's, not the checkout's around it.
        subprocess.run(["git", "init", "--quiet"], cwd=copy, check=True)
        if patch is not None:
            applies = subprocess.run(["git", "apply", "--check", str(patch)], cwd=copy, capture_output=True, text=True)
            if applies.returncode != 0:
                say(f"error: the patch does not apply to the working tree — nothing was run (exit {DOES_NOT_APPLY})\n" + applies.stderr.strip())
                return DOES_NOT_APPLY
            subprocess.run(["git", "apply", str(patch)], cwd=copy, check=True)
        if spec.python is None:
            (copy / "build/narrowed.gradle").write_text(NARROWED % (", ".join(map(repr, includes("hero", spec.pattern))), ", ".join(map(repr, includes("test", spec.pattern)))))
            compiled = subprocess.run(gradle(copy, spec.tasks[0].rsplit(":", 1)[0] + ":testClasses"),
                                      cwd=copy, text=True, capture_output=True)
            if compiled.returncode != 0:
                say(compiled.stdout[-4000:])
                say(f"error: the copy does not build — nothing was run (exit {DOES_NOT_BUILD})")
                return DOES_NOT_BUILD
        say(f"many: {spec.text} — {times} {'time' if times == 1 else 'times'} alone, then as often in its module's whole run ({spec.module()}), "
            f"a second build busy beside each run{f'; {patch.name} applied' if patch else ''}")
        beside = busy(second)
        counts = {}
        for whole in (False, True):
            where = WHOLE if whole else "alone"
            passed = 0
            for number in range(1, times + 1):
                began = time.monotonic()
                cases, output = run_once(copy, spec, whole, limit)
                if not cases:
                    say(output[-4000:])
                    say(f"error: run {number} {where} ran none of {spec.text}'s cases (exit {RUNS_NOTHING})")
                    return RUNS_NOTHING
                failed = [(case, why) for case, why in cases if why is not None]
                passed += not failed
                say(f"many: {where} {number} of {times} — {'passed' if not failed else 'failed'} ({len(cases)} "
                    f"{'case' if len(cases) == 1 else 'cases'}, {round(time.monotonic() - began)} s)")
                for case, why in failed:
                    say(f"      {case}: {why}")
            counts[where] = passed
        say(f"many: {spec.text} — alone {counts['alone']} of {times} passed · in its module's whole run "
            f"{counts[WHOLE]} of {times} passed · a second build busy beside each run")
        return PASSED if all(count == times for count in counts.values()) else FAILED
    except Killed as killed:
        say(f"many: {killed}; its runs and the busy build stopped and its copies removed")
        return 128 + killed.number
    finally:
        for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(number, signal.SIG_IGN)
        ended(beside)
        remove(copy, None)
        remove(second, None)
        if folder.is_dir() and not any(folder.iterdir()):
            folder.rmdir()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
