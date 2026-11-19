#!/usr/bin/env python3
"""`just mutant <patch> <spec filter>` — can a spec fail? (retro of P1, candidate 3; S2.8 T5; architecture.md §7)

Applies the patch to a throwaway copy of the working tree (`build/mutant/`, removed at the end), runs there the specs
the filter names, with a forced rerun, and prints `red` with the specs that failed, or `green`. The working tree is not
touched: the copy is taken from it — committed or not, and the files git does not ignore — and the patch is applied
only to the copy.

The filter is what Gradle's `--tests` takes: a spec class by its name or its qualified name, `*` a wildcard; a module's
specs run in its `test` task, and the hero's (`kp-daemon`'s `kp/daemon/hero/`) in `hero`. `<module>:<filter>` keeps it
to one module.

Exit: 0 red · 1 green · 2 the patch does not apply · 3 the filter runs no spec · 4 the patched copy does not build, or
its run stops before its specs report. Neither 2, 3 nor 4 says red or green.
Usage: python3 build-support/mutant.py [--from DIR] <patch> <spec filter>
"""
from fnmatch import fnmatchcase
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[1]
RED, GREEN, DOES_NOT_APPLY, RUNS_NOTHING, DOES_NOT_BUILD = 0, 1, 2, 3, 4
HERO = "kp/daemon/hero/"
# Gradle's `--tests` with a leading wildcard (`*ProjectionsSpec`) does not keep Kotest from running a task's other specs —
# Gradle only checks afterwards that a test matched — so the copy's test tasks are narrowed to the named spec classes,
# as `test` and `hero` split theirs: `hero` keeps to its own, under kp/daemon/hero/ (issue #43).
NARROWED = """gradle.projectsEvaluated {
    allprojects { tasks.withType(Test).configureEach { setIncludes(name == 'hero' ? [%s] : [%s]) } }
}
"""


def say(text):
    print(text, flush=True)


def tasks_for(pattern):
    """The test tasks whose specs the filter names, found by the specs' file names: `:<module>:test` or `:kp-daemon:hero`."""
    module, _, pattern = pattern.rpartition(":") if re.match(r"^kp-[\w-]+:", pattern) else ("", "", pattern)
    tasks = set()
    for source in sorted(ROOT.glob("kp-*/src/test/kotlin/**/*.kt")):
        owner = source.relative_to(ROOT).parts[0]
        if module and owner != module:
            continue
        package = source.relative_to(ROOT / owner / "src/test/kotlin").with_suffix("")
        qualified, simple = ".".join(package.parts), package.name
        if fnmatchcase(qualified, pattern) or fnmatchcase(simple, pattern):
            tasks.add(f":{owner}:{'hero' if package.as_posix().startswith(HERO) else 'test'}")
    return sorted(tasks), pattern


def class_files(pattern):
    """The filter as a pattern of class files: `kp.engine.Fold*` is `kp/engine/Fold*.class`, `Fold*` is `**/Fold*.class`."""
    return (pattern.replace(".", "/") if "." in pattern else "**/" + pattern) + ".class"


def includes(task, pattern):
    """What a test task named [task] includes of the filter: `hero` only the classes under kp/daemon/hero/ — those it runs
    in the checkout — and every other task the filter's classes, which their own excludes still narrow."""
    files = class_files(pattern)
    if task != "hero":
        return [files]
    if files.startswith("**/"):
        return [HERO + files]
    return [files] if files.startswith(HERO) else [HERO + "no-spec-the-filter-names"]


def copy_of_the_working_tree(into):
    listed = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, check=True)
    for name in filter(None, listed.stdout.decode().split("\0")):
        source, target = ROOT / name, into / name
        if not os.path.lexists(source):
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_symlink():
            target.symlink_to(os.readlink(source))
        else:
            shutil.copy2(source, target)
    (into / "build").mkdir(exist_ok=True)
    (into / "build/python").symlink_to(ROOT / "build/python")


def reports(copy, tasks):
    """Every spec case the tasks ran, from their JUnit reports: (spec, case, the first line of its failure or None)."""
    cases = []
    for task in tasks:
        _, module, name = task.split(":")
        for report in sorted((copy / module / "build/test-results" / name).glob("TEST-*.xml")):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                if case.find("skipped") is None:
                    broke = case.find("failure") if case.find("failure") is not None else case.find("error")
                    why = None if broke is None else ((broke.get("message") or broke.text or "").strip().splitlines() or ["(no message)"])[0][:240]
                    cases.append((case.get("classname"), case.get("name"), why))
    return cases


def main(arguments):
    origin = Path(arguments.pop(arguments.index("--from") + 1)) if "--from" in arguments else Path.cwd()
    arguments = [a for a in arguments if a != "--from"]
    if len(arguments) != 2:
        say(__doc__.strip().splitlines()[-1])
        return DOES_NOT_APPLY
    patch, wanted = (origin / arguments[0]).resolve(), arguments[1]
    if not patch.is_file():
        say(f"error: no patch at {patch} — nothing was run (exit {DOES_NOT_APPLY})")
        return DOES_NOT_APPLY
    tasks, pattern = tasks_for(wanted)
    if not tasks:
        say(f"error: the filter '{wanted}' names no spec of any module — nothing was run (exit {RUNS_NOTHING})")
        return RUNS_NOTHING
    (ROOT / "build/mutant").mkdir(parents=True, exist_ok=True)
    copy = Path(tempfile.mkdtemp(prefix="copy-", dir=ROOT / "build/mutant"))
    try:
        copy_of_the_working_tree(copy)
        # A repository of its own: git applies the patch here, never to the working tree around it.
        subprocess.run(["git", "init", "--quiet"], cwd=copy, check=True)
        applies = subprocess.run(["git", "apply", "--check", str(patch)], cwd=copy, capture_output=True, text=True)
        if applies.returncode != 0:
            say(f"error: the patch does not apply to the working tree — nothing was run (exit {DOES_NOT_APPLY})\n" + applies.stderr.strip())
            return DOES_NOT_APPLY
        subprocess.run(["git", "apply", str(patch)], cwd=copy, check=True)
        (copy / "build/narrowed.gradle").write_text(NARROWED % (", ".join(map(repr, includes("hero", pattern))), ", ".join(map(repr, includes("test", pattern)))))
        command = ["./gradlew", "--console=plain", "--continue", "--init-script", "build/narrowed.gradle"]
        for task in tasks:
            command += [task, "--rerun", "--tests", pattern]
        say(f"mutant: {patch.name} on a copy of the working tree; running {' '.join(tasks)} --tests '{pattern}'")
        run = subprocess.run(command, cwd=copy, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        cases = reports(copy, tasks)
        if not cases:
            say(run.stdout[-4000:])
            # No spec reported: the filter named none — a task with no class left, or one that executed no test — unless
            # a task before the specs failed: then the copy did not build.
            others = [task for task in re.findall(r"> Task (\S+) FAILED", run.stdout) if task not in tasks]
            if not others:
                say(f"error: the filter '{wanted}' ran no spec — neither red nor green (exit {RUNS_NOTHING})")
                return RUNS_NOTHING
            say(f"error: the patched copy does not build, or stopped before its specs reported — neither red nor green (exit {DOES_NOT_BUILD})")
            return DOES_NOT_BUILD
        failed = sorted({f"{spec} — {case}\n      {why}" for spec, case, why in cases if why is not None})
        others = [task for task in re.findall(r"> Task (\S+) FAILED", run.stdout) if task not in tasks]
        if failed:
            say("red — the specs that failed:")
            for line in failed:
                say("  " + line)
            if others:
                say("  (and these tasks failed before their specs could run: " + ", ".join(others) + ")")
            return RED
        if run.returncode != 0:
            say(run.stdout[-4000:])
            say(f"error: the run failed, but no spec did — neither red nor green (exit {DOES_NOT_BUILD})")
            return DOES_NOT_BUILD
        say(f"green — {len(cases)} spec cases ran and passed")
        return GREEN
    finally:
        shutil.rmtree(copy, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
