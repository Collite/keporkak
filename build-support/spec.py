#!/usr/bin/env python3
"""`just spec <filter>` — run the specs a filter names, and no other (the retro of P2, candidate 8; S3.0 T4).

The narrowing `just mutant` does, without a patch and in the working tree: Gradle's `--tests` does not keep Kotest 6
from running a task's other specs, so each test task the filter reaches is narrowed to the named spec classes by an
init script (build/spec/narrowed.gradle), and run again whether or not it is up to date. The filter is what `just
mutant` takes: a spec class by its name or its qualified name, `*` a wildcard, `<module>:<filter>` to keep it to one
module; the hero's specs run in `:kp-daemon:hero`.

It prints each spec case that ran — `<module> · <spec> — <case> — passed|failed`, a failure's message below it, whole —
and the counts. KP_SPEC_TIMEOUT, as for every test task, sets the default timeout of a spec.
Stopped — SIGTERM, SIGINT, SIGHUP, or `just` above it gone — it stops its Gradle run and waits until the run's test
workers have ended before it exits, 128 and the signal's number (S3.8 T6): a run ended by its timeout leaves nothing
going.
Exit: 0 every case passed · 1 one failed · 3 the filter names no spec, or the run left it none · 4 the build failed
before its specs reported.
Usage: python3 build-support/spec.py <spec filter>
"""
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mutant import NARROWED, ROOT, includes, tasks_for  # noqa: E402

PASSED, FAILED, RUNS_NOTHING, DOES_NOT_BUILD = 0, 1, 3, 4


def say(text):
    print(text, flush=True)


class Stopped(Exception):
    """The run was told to stop: by a signal, or because the `just` that started it is gone (SIGHUP's number then)."""

    def __init__(self, number):
        super().__init__(f"stopped by signal {number}")
        self.number = number


def on_signal(number, _frame):
    raise Stopped(number)


def workers():
    """The test workers of a run in this checkout: Gradle's test executors whose working directory is in it."""
    found = []
    for proc in Path("/proc").iterdir():
        try:
            cwd = os.readlink(proc / "cwd")
            if proc.name.isdigit() and (cwd == str(ROOT) or cwd.startswith(f"{ROOT}/")) and b"Gradle Test Executor" in (proc / "cmdline").read_bytes():
                found.append(int(proc.name))
        except OSError:
            continue
    return found


def stop(gradle):
    """[gradle]'s run stopped: the client told to stop, then killed — its daemon then cancels the build — and the run's
    test workers awaited until they have ended, a minute at most."""
    if gradle.poll() is None:
        for sent, wait in ((signal.SIGTERM, 30), (signal.SIGKILL, 10)):
            try:
                gradle.send_signal(sent)
            except ProcessLookupError:
                break
            try:
                gradle.wait(timeout=wait)
                break
            except subprocess.TimeoutExpired:
                continue
    until = time.monotonic() + 60
    while workers() and time.monotonic() < until:
        time.sleep(0.5)


def run_gradle(command):
    """[command] run to its end, its output as one text; stopped as [stop] says when a signal comes or its parent goes."""
    parent = os.getppid()
    gradle = subprocess.Popen(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        while True:
            try:
                output, _ = gradle.communicate(timeout=1)
                return subprocess.CompletedProcess(command, gradle.returncode, output)
            except subprocess.TimeoutExpired:
                if os.getppid() != parent:
                    raise Stopped(signal.SIGHUP)
    except Stopped:
        for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(number, signal.SIG_IGN)
        stop(gradle)
        raise


def cases(tasks):
    """Every spec case the tasks ran, from their JUnit reports: (module, spec, case, its failure's message or None)."""
    found = []
    for task in tasks:
        _, module, name = task.split(":")
        for report in sorted((ROOT / module / "build/test-results" / name).glob("TEST-*.xml")):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                if case.find("skipped") is None:
                    broke = case.find("failure") if case.find("failure") is not None else case.find("error")
                    why = None if broke is None else ((broke.get("message") or broke.text or "(no message)").strip())
                    found.append((module, case.get("classname").rsplit(".", 1)[-1], case.get("name"), why))
    return found


def main(arguments):
    if len(arguments) != 1:
        say(__doc__.strip().splitlines()[-1])
        return RUNS_NOTHING
    wanted = arguments[0]
    tasks, pattern = tasks_for(wanted)
    if not tasks:
        say(f"error: the filter '{wanted}' names no spec of any module — nothing was run (exit {RUNS_NOTHING})")
        return RUNS_NOTHING
    script = ROOT / "build/spec/narrowed.gradle"
    script.parent.mkdir(parents=True, exist_ok=True)
    script.write_text(NARROWED % (", ".join(map(repr, includes("hero", pattern))), ", ".join(map(repr, includes("test", pattern)))))
    command = ["./gradlew", "--console=plain", "--continue", "--init-script", str(script)]
    for task in tasks:
        command += [task, "--rerun", "--tests", pattern]
    # A task that does not run — its compile failed — leaves no report of an earlier run behind to be read as this one's.
    for task in tasks:
        _, module, name = task.split(":")
        shutil.rmtree(ROOT / module / "build/test-results" / name, ignore_errors=True)
    say(f"spec: running {' '.join(tasks)} --tests '{pattern}'")
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, on_signal)
    try:
        run = run_gradle(command)
    except Stopped as stopped:
        say(f"spec: {stopped}; its run stopped — no spec's verdict")
        return 128 + stopped.number
    ran = cases(tasks)
    if not ran:
        say(run.stdout[-6000:])
        others = [task for task in re.findall(r"> Task (\S+) FAILED", run.stdout) if task not in tasks]
        if others:
            say(f"error: the build failed before the specs reported: {', '.join(others)} (exit {DOES_NOT_BUILD})")
            return DOES_NOT_BUILD
        say(f"error: the filter '{wanted}' ran no spec (exit {RUNS_NOTHING})")
        return RUNS_NOTHING
    for module, spec, case, why in ran:
        say(f"{module} · {spec} — {case} — {'passed' if why is None else 'failed'}")
        if why is not None:
            say("\n".join("      " + line for line in why.splitlines()))
    failed = sum(1 for *_, why in ran if why is not None)
    specs = sorted({(module, spec) for module, spec, _, _ in ran})
    say(f"spec: {len(ran)} cases of {len(specs)} spec classes ran — {len(ran) - failed} passed, {failed} failed")
    if failed:
        return FAILED
    if run.returncode != 0:
        say(run.stdout[-6000:])
        say(f"error: the run failed, but no spec did (exit {DOES_NOT_BUILD})")
        return DOES_NOT_BUILD
    return PASSED


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
