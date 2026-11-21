"""Support for the specs of S2.8, S3.0 and S3.8: a throwaway copy of the checkout, `just` and Gradle run in it, a main
source planted there and what the check that the daemon decides nothing reports of it, the inputs of its test tasks, the
processes at work in a folder, and a throwaway repository for the changelog check. Every folder is made under build/ and
removed when the test ends."""
import os
from pathlib import Path
import re
import shutil
import signal
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


def just(folder, *arguments, environment=None, timeout=1800):
    """`just` in [folder], its output and errors as one text. [environment] adds to the process's own; a name given None is
    taken out of it. Past [timeout] seconds the run is ended — its whole process group, `just` and what it started: told
    to stop, then killed (S3.8 T6) — and `subprocess.TimeoutExpired` is raised with what it printed."""
    run = start_just(folder, *arguments, environment=environment)
    try:
        output, _ = run.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        output = end_group(run)
        raise subprocess.TimeoutExpired(run.args, timeout, output=output)
    return subprocess.CompletedProcess(run.args, run.returncode, output)


def start_just(folder, *arguments, environment=None):
    """`just` in [folder] started in a process group of its own, its output and errors piped as one text; [environment] as
    for [just]."""
    env = {**os.environ, **{name: value for name, value in (environment or {}).items() if value is not None}}
    for name, value in (environment or {}).items():
        if value is None:
            env.pop(name, None)
    return subprocess.Popen([shutil.which("just") or "just", "--justfile", str(folder / "justfile"), "--working-directory", str(folder), *arguments],
                            cwd=folder, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)


def end_group(run, grace=60):
    """[run]'s process group told to stop (SIGTERM), then killed if it has not ended within [grace] seconds; what it printed."""
    for sent in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(run.pid, sent)
        except ProcessLookupError:
            break
        try:
            return run.communicate(timeout=grace)[0]
        except subprocess.TimeoutExpired:
            continue
    return run.communicate()[0]


# The packages of the modules the check covers, where a spec plants a main source.
PACKAGES = {"kp-driver": "kp.driver", "kp-daemon": "kp.daemon", "kp-seat-api": "kp.seatapi", "kp-engine": "kp.engine", "kp-ops": "kp.ops"}
HEAD = ["package {package}.planted", "", "import java.time.Clock", "import java.time.Duration", "import java.time.Instant", ""]
FINDING = re.compile(r"^\s*not on the allow-list: (kp-[\w-]+/src/main/kotlin/\S+\.kt):(\d+): (\w+) in (\S+): (.*)$")
ITS_LINE = re.compile(r"^\s*its line: (.*)$")


def plant(copy, module, name, lines):
    """A main source of [module] in [copy] holding [lines] after its head; returns its path and each line's number."""
    path = f"{module}/src/main/kotlin/{PACKAGES[module].replace('.', '/')}/planted/{name}.kt"
    head = [line.format(package=PACKAGES[module]) for line in HEAD]
    (copy / path).parent.mkdir(parents=True, exist_ok=True)
    (copy / path).write_text("\n".join(head + lines) + "\n")
    return path, [len(head) + i + 1 for i in range(len(lines))]


def findings(output):
    """Each finding the check reports no entry excuses: its path, line number, shape, declaration, code and line."""
    lines = output.splitlines()
    return [FINDING.match(line).groups() + (ITS_LINE.match(after).group(1),) for line, after in zip(lines, lines[1:] + [""]) if FINDING.match(line)]


def working_in(folder):
    """The processes whose working directory is in [folder] — a Gradle client, its daemon, a test worker of its run."""
    found = []
    for proc in Path("/proc").iterdir():
        try:
            if proc.name.isdigit() and os.readlink(proc / "cwd").startswith(str(folder)):
                found.append(f"{proc.name} {(proc / 'cmdline').read_bytes().replace(bytes(1), b' ')[:120].decode(errors='replace')}")
        except OSError:
            continue
    return found


def run_of(folder):
    """The processes of a run in [folder]: those at work there but a Gradle daemon that is idle — a daemon is shared
    between builds and outlives each, but one still busy with the run's build is of it."""
    idle = set()
    status = subprocess.run(["./gradlew", "--status", "--console=plain"], cwd=folder, text=True, capture_output=True)
    for line in status.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0].isdigit() and fields[1] == "IDLE":
            idle.add(fields[0])
    return [process for process in working_in(folder) if not ("GradleDaemon" in process and process.split()[0] in idle)]
