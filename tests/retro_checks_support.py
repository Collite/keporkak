"""Support for S2.8's specs: a throwaway copy of the checkout, Gradle run in it, the inputs of its test tasks, and a
throwaway repository for the changelog check. Every folder is made under build/ and removed when the test ends."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

# Lists, per project, each `Test` task's input files: what makes Gradle run it again when one changes. Registered in
# every project, so each resolves its own configurations.
TEST_INPUTS = """
allprojects {
    tasks.register("kpTestInputs") {
        def tests = tasks.withType(Test)
        def root = rootDir.toPath()
        doLast {
            tests.each { test ->
                test.inputs.files.files.each { file -> println("INPUT ${test.path} ${root.relativize(file.toPath())}") }
            }
        }
    }
}
"""


def copy_of_the_checkout(test):
    """The working tree as git lists it — tracked files and the untracked ones it does not ignore — in a new folder under
    build/, with the Python tool set of `just _python-env` linked in."""
    copy = Path(tempfile.mkdtemp(prefix="s2.8-copy-", dir=ROOT / "build"))
    test.addCleanup(shutil.rmtree, copy, ignore_errors=True)
    listed = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, check=True)
    for name in filter(None, listed.stdout.decode().split("\0")):
        source, target = ROOT / name, copy / name
        if not os.path.lexists(source):
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_symlink():
            target.symlink_to(os.readlink(source))
        else:
            shutil.copy2(source, target)
    (copy / "build").mkdir(exist_ok=True)
    (copy / "build/python").symlink_to(ROOT / "build/python")
    return copy


def gradle(folder, *arguments, environment=None):
    """`./gradlew` in [folder], its output and errors as one text."""
    return subprocess.run(["./gradlew", "--console=plain", *arguments], cwd=folder, env={**os.environ, **(environment or {})},
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def test_task_inputs(copy):
    """Each `Test` task of the build in [copy], by path, with its input files under rules/, robots/, pool/ and agents/."""
    script = copy / "build/test-inputs.gradle"
    script.write_text(TEST_INPUTS)
    listed = gradle(copy, "--quiet", "--init-script", str(script), "kpTestInputs")
    if listed.returncode != 0:
        raise AssertionError(listed.stdout)
    inputs = {}
    for line in listed.stdout.splitlines():
        if line.startswith("INPUT "):
            _, task, path = line.split(" ", 2)
            files = inputs.setdefault(task, set())
            if re.match(r"(rules|robots|pool|agents)/", path):
                files.add(path)
    return inputs


class ThrowawayRepository:
    """A git repository under build/ whose master holds [files]; the changelog check is run in it."""

    def __init__(self, test, files):
        self.path = Path(tempfile.mkdtemp(prefix="s2.8-repository-", dir=ROOT / "build"))
        test.addCleanup(shutil.rmtree, self.path, ignore_errors=True)
        self.git("init", "--quiet", "--initial-branch", "master")
        self.commit(files)

    def git(self, *arguments):
        identity = ["-c", "user.name=spec", "-c", "user.email=spec@example.invalid", "-c", "commit.gpgsign=false"]
        subprocess.run(["git", *identity, *arguments], cwd=self.path, check=True, capture_output=True)

    def branch(self, name):
        self.git("checkout", "--quiet", "-b", name)

    def commit(self, files):
        for name, text in files.items():
            (self.path / name).parent.mkdir(parents=True, exist_ok=True)
            (self.path / name).write_text(text)
        self.git("add", "--all")
        self.git("commit", "--quiet", "--message", "edit " + ", ".join(files))

    def check(self):
        return subprocess.run([sys.executable, str(ROOT / "build-support/check_changelog.py")], cwd=self.path, text=True, capture_output=True)
