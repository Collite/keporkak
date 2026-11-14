#!/usr/bin/env python3
"""The office check (contracts.md §12, architecture.md §4): one line per item, each with one of three outcomes —
`ok` · `to do` (Bora's, with the exact command) · `later` (the stage that delivers it). Exit 1 while anything is to do.

It reads and reports only. It installs, creates and changes nothing, and never runs `sudo`: every command it runs is
a version or a read-only query, and `gh` runs with DO_NOT_TRACK so it writes no state file.
Usage: python3 scripts/office-check.py [--pool DIR] [--office NAME]   (default: the short host name)
"""
import argparse
import mmap
import os
from pathlib import Path
import pwd
import re
import shutil
import socket
import stat
import subprocess
import sys
import yaml

ROOT = Path(__file__).resolve().parents[1]
QUIET = {"DO_NOT_TRACK": "1", "GH_TELEMETRY": "false"}
ROOTS = ("worktrees", "scratch", "seats", "secrets")
# The secrets directory's layout (office/README.md): file → the stage that needs it, and what it is.
SECRETS = (
    (["claude-token"], "S2.1", "the seats' Claude token, put there by Bora before P2 (office/README.md)"),
    (["apps/kp-merger.pem", "apps/kp-coder.pem", "apps/kp-reviewer.pem", "apps/kp-gardener.pem"], "S3.2", "the four GitHub Apps' keys"),
)
TOOLS = (  # name, version command, Bora's command when it is missing (Panda: apt for system tools, mise for the rest)
    ("git", ["git", "--version"], "sudo apt install git"),
    ("gh", ["gh", "--version"], "sudo apt install gh"),
    ("just", ["just", "--version"], "mise use -g just"),
    ("jq", ["jq", "--version"], "sudo apt install jq"),
    ("tmux", ["tmux", "-V"], "sudo apt install tmux"),
    ("Python 3", ["python3", "--version"], "mise use -g python@3.13"),
)


class PoolLoader(yaml.SafeLoader):
    """YAML 1.2 booleans, as check-rules.py reads the same files: `off` is a word."""
PoolLoader.yaml_implicit_resolvers = {
    key: [(tag, pattern) for tag, pattern in resolvers if tag != "tag:yaml.org,2002:bool"]
    for key, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
PoolLoader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|false)$", re.I), list("tTfF"))


class Report:
    def __init__(self):
        self.lines, self.counts = [], {"ok": 0, "to do": 0, "later": 0}
    def add(self, outcome, item, detail=""):
        self.counts[outcome] += 1
        self.lines.append(f"{outcome:<6} {item}" + (f": {detail}" if detail else ""))
    def ok(self, item, detail=""): self.add("ok", item, detail)
    def todo(self, item, command): self.add("to do", item, command)
    def later(self, item, stage, why=""): self.add("later", item, f"{stage} {why}" if why else stage)


def query(command):
    """What a read-only command prints on stdout, then stderr, as lines; None when the tool is absent or fails."""
    tool = shutil.which(command[0])
    if tool is None:
        return None
    try:
        result = subprocess.run([tool] + command[1:], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30, env={**os.environ, **QUIET})
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return [line.strip() for line in result.stdout.splitlines() + result.stderr.splitlines() if line.strip()]


def run(command):
    """The first line a read-only command prints, or None when the tool is absent or fails."""
    lines = query(command)
    return None if lines is None else (lines[0] if lines else "")


def home(path):
    return Path(os.path.expanduser(path))


def owner(info):
    try:
        return pwd.getpwuid(info.st_uid).pw_name
    except KeyError:
        return str(info.st_uid)


def check_runtimes(report, roster):
    java = run(["java", "-XX:-UsePerfData", "-version"])
    if java and re.search(r'version "21[."]', java):
        report.ok("JDK 21", java)
    else:
        report.todo("JDK 21", ("found " + java + "; " if java else "") + "mise use -g java@temurin-21")
    node = run(["node", "--version"])
    if node:
        report.ok("Node", node)
    else:
        report.todo("Node", "mise use -g node@24")
    for vendor in roster["vendors"].values():
        report.later(f"Claude adapter {vendor['adapter']}", "S2.1", "pins it with the handshake test and installs it")


def check_claude(report, roster):
    models = list(dict.fromkeys(line["model"] for line in roster["order"]))
    path = shutil.which("claude")
    version = run(["claude", "--version"]) if path else None
    if version is None:
        report.todo("Claude CLI", "curl -fsSL https://claude.ai/install.sh | bash")
        return
    # The CLI prints no model list; its built-in catalog names each model as id:"<model>".
    binary = Path(os.path.realpath(path))
    with binary.open("rb") as handle:
        size = binary.stat().st_size
        catalog = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) if size else b""
        missing = [model for model in models if catalog.find(f'id:"{model}"'.encode()) == -1]
        if size:
            catalog.close()
    if missing:
        report.todo("Claude CLI", f"{version}; its catalog lacks {', '.join(missing)}: claude update, or correct pool/roster.yaml")
    else:
        report.ok("Claude CLI", f"{version}; its catalog has {', '.join(models)}")


def check_tools(report):
    for name, command, install in TOOLS:
        version = run(command)
        if version:
            report.ok(name, version)
        else:
            report.todo(name, install)


def check_roots(report, office):
    user = office["user"]
    for name in ROOTS:
        written = office["roots"][name]
        path = home(written)
        item = f"root {name} {written}"
        create = f"mkdir -p -m 0700 {written}" if name == "secrets" else f"mkdir -p {written}"
        if not path.exists():
            report.todo(item, create)
            continue
        info = path.stat()
        if not stat.S_ISDIR(info.st_mode):
            report.todo(item, f"it is not a directory; move it aside, then {create}")
            continue
        commands = []
        if owner(info) != user:
            commands.append(f"sudo chown {user}: {written}")
        if name == "secrets" and stat.S_IMODE(info.st_mode) != 0o700:
            commands.append(f"chmod 0700 {written}")
        elif name != "secrets" and info.st_mode & stat.S_IRWXU != stat.S_IRWXU:
            commands.append(f"chmod u+rwx {written}")
        if commands:
            report.todo(item, " && ".join(commands))
        else:
            report.ok(item)


def check_secrets(report, office):
    user, folder = office["user"], office["roots"]["secrets"]
    for names, stage, what in SECRETS:
        item = ("secret " if len(names) == 1 else "secrets ") + ", ".join(names)
        present = {name: home(folder).joinpath(name).stat() for name in names if home(folder).joinpath(name).is_file()}
        foreign = " ".join(f"{folder}/{name}" for name, info in present.items() if owner(info) != user)
        open_to_others = " ".join(f"{folder}/{name}" for name, info in present.items() if stat.S_IMODE(info.st_mode) != 0o600)
        if foreign or open_to_others:
            report.todo(item, " && ".join(([f"sudo chown {user}: {foreign}"] if foreign else []) + ([f"chmod 0600 {open_to_others}"] if open_to_others else [])))
        elif len(present) == len(names):
            report.ok(item)
        else:
            report.later(item, stage, "— " + what)


def check_sudo(report, office):
    user = office["user"]
    item = f"kp root-owned and not writable by {user}"
    path = shutil.which("kp")
    if path is None:
        report.later(item, "S1.5", "installs kp")
    else:
        real = os.path.realpath(path)
        writable = os.access(real, os.W_OK) or os.access(os.path.dirname(real), os.W_OK)
        if os.stat(real).st_uid == 0 and not writable:
            report.ok(item, real)
        else:
            report.todo(item, f"sudo install -o root -g root -m 0755 {real} /usr/local/bin/kp, and keep it the only kp on PATH")
    report.later("sudo asks every time for kp", "S1.5", "— Bora installs office/sudoers.d/zz-keporkak-kp before it (office/README.md)")
    report.later("seats' permissions deny kp and sudo", "S2.2", "compiles them")


def check_live_targets(report, office):
    # Reads the local kubeconfig only; no cluster is contacted.
    contexts = set(query(["kubectl", "config", "get-contexts", "-o", "name"]) or [])
    for target in office["live_targets"]:
        if target in contexts:
            report.ok(f"kubectl context {target}")
        else:
            report.later(f"kubectl context {target}", "S4.1")


def check_service(report, office):
    user = office["user"]
    if run(["systemctl", "--user", "show", "keporkak.service", "--property=LoadState", "--value"]) == "loaded":
        report.ok("systemd user service keporkak.service")
    else:
        report.later("systemd user service keporkak.service", "S1.5", "delivers it (template office/systemd/keporkak.service)")
    if run(["loginctl", "show-user", user, "--property=Linger", "--value"]) == "yes":
        report.ok(f"lingering for {user}")
    else:
        report.todo(f"lingering for {user}", f"sudo loginctl enable-linger {user}")
    report.later("no vendor global configuration reachable from a seat's config home", "S2.2")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pool", type=Path, default=ROOT / "pool")
    parser.add_argument("--office", default=socket.gethostname().split(".")[0])
    args = parser.parse_args()
    try:
        roster = yaml.load((args.pool / "roster.yaml").read_text(), Loader=PoolLoader)
        office = yaml.load((args.pool / "offices.yaml").read_text(), Loader=PoolLoader)["offices"][args.office]
    except (OSError, KeyError, TypeError, yaml.YAMLError) as error:
        print(f"office check: cannot read office '{args.office}' from {args.pool} ({error}); run `just rules`, or pass --office", file=sys.stderr)
        return 2
    report = Report()
    check_runtimes(report, roster)
    check_claude(report, roster)
    check_tools(report)
    check_roots(report, office)
    check_secrets(report, office)
    check_sudo(report, office)
    check_live_targets(report, office)
    check_service(report, office)
    print("\n".join(report.lines))
    counts = report.counts
    print(f"RESULT: {'NOT READY' if counts['to do'] else 'READY'} — {counts['to do']} to do, {counts['later']} later, {counts['ok']} ok")
    return 1 if counts["to do"] else 0


if __name__ == "__main__":
    sys.exit(main())
