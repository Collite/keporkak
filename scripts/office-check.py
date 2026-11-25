#!/usr/bin/env python3
"""The office check (contracts.md §12, architecture.md §4): one line per item, each with one of three outcomes —
`ok` · `to do` (Bora's, with the exact command) · `later` (the stage that delivers it). Exit 1 while anything is to do.

It reads and reports only. It installs and changes nothing on the office, and never runs `sudo`: every command it
runs is a version or a read-only query, `gh` runs with DO_NOT_TRACK so it writes no state file — and, for the seats'
permissions, kp-driver's builder makes every persona's home in a temporary folder it then removes (Gradle compiles
into the checkout's build/ first), and hooks/deny reads each home's permissions.json.
Usage: python3 scripts/office-check.py [--pool DIR] [--adapter DIR] [--office NAME] [--homes DIR] [--sudoers DIR]
       --office: default the short host name · --homes: read the homes built in DIR instead of building them
       --sudoers: the folder of sudo's drop-ins, default /etc/sudoers.d
"""
import argparse
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import yaml

ROOT = Path(__file__).resolve().parents[1]
QUIET = {"DO_NOT_TRACK": "1", "GH_TELEMETRY": "false"}
ROOTS = ("worktrees", "scratch", "seats", "secrets")
# The secrets directory's layout (office/README.md): files → the stage that needs them (None: required now), and
# what they are — for one required now, Bora's command when it is missing.
SECRETS = (
    (["claude-token"], None, "claude setup-token, then: install -m 0600 /dev/stdin {folder}/claude-token (office/README.md)"),
    (["apps/kp-merger.pem", "apps/kp-coder.pem", "apps/kp-reviewer.pem", "apps/kp-gardener.pem"], "S3.2", "the four GitHub Apps' keys"),
)
TOOLS = (  # name, version command, Bora's command when it is missing (apt for system tools, mise for the rest)
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


def check_runtimes(report, roster, adapter):
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
    check_adapter(report, roster, adapter)


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def check_adapter(report, roster, folder):
    """The pinned adapter (contracts.md §9, §12): installed at the version the roster and its lockfile name. Whether
    it accepts every roster model is `just handshake models`'s to check: that needs a session."""
    for vendor in roster["vendors"].values():
        name, pinned = vendor["adapter"], vendor.get("adapter_version")
        item = f"Claude adapter {name}"
        lockfile = read_json(folder / "package-lock.json") or {}
        locked = lockfile.get("packages", {}).get(f"node_modules/{name}", {}).get("version")
        installed = (read_json(folder / "node_modules" / name / "package.json") or {}).get("version")
        if pinned is None or pinned != locked:
            report.todo(item, f"pool/roster.yaml names {pinned or 'no version'} and office/adapter/package-lock.json {locked or 'none'}: correct the repository so they name one version (contracts.md §9)")
        elif installed != locked:
            report.todo(item, f"{'installed ' + installed if installed else 'not installed'}, the roster and the lockfile name {locked}: just adapter")
        else:
            bundled = (read_json(folder / "node_modules/@anthropic-ai/claude-agent-sdk/package.json") or {}).get("claudeCodeVersion")
            report.ok(item, f"{installed}, as the roster and the lockfile name" + (f"; it bundles Claude Code {bundled}" if bundled else ""))


def check_claude(report):
    version = run(["claude", "--version"])
    if version is None:
        report.todo("Claude CLI", "curl -fsSL https://claude.ai/install.sh | bash")
    else:
        report.ok("Claude CLI", version)


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


def check_project(report, office):
    """The one project checkout and each effort's folder in it (contracts.md §9); the hand log's `mail/` is at its root."""
    project = office["project"]
    checkout = project["checkout"]
    if not (home(checkout) / ".git").exists():
        report.todo(f"project checkout {checkout}", f"clone the project repository to {checkout} (docs/profiles/ names it)")
        return
    report.ok(f"project checkout {checkout}")
    for effort, folder in project["efforts"].items():
        item = f"project folder {effort} " + (checkout if folder == "." else f"{checkout.rstrip('/')}/{folder}")
        if (home(checkout) / folder).is_dir():
            report.ok(item)
        else:
            report.todo(item, f"git -C {checkout} pull --ff-only")


def check_secrets(report, office):
    user, folder = office["user"], office["roots"]["secrets"]
    for names, stage, what in SECRETS:
        item = ("secret " if len(names) == 1 else "secrets ") + ", ".join(names)
        present = {name: home(folder).joinpath(name).stat() for name in names if home(folder).joinpath(name).is_file()}
        foreign = " ".join(f"{folder}/{name}" for name, info in present.items() if owner(info) != user)
        open_to_others = " ".join(f"{folder}/{name}" for name, info in present.items() if stat.S_IMODE(info.st_mode) != 0o600)
        if foreign or open_to_others:
            report.todo(item, " && ".join(([f"sudo chown {user}: {foreign}"] if foreign else []) + ([f"chmod 0600 {open_to_others}"] if open_to_others else [])))
        elif len(present) == len(names) and stage is None and (lines := one_line(home(folder), names)):
            report.todo(item, f"{lines}; " + what.format(folder=folder))
        elif len(present) == len(names):
            report.ok(item)
        elif stage is None:
            report.todo(item, what.format(folder=folder))
        else:
            report.later(item, stage, "— " + what)


def one_line(folder, names):
    """For a secret that stands alone on one line (office/README.md), what is wrong with its form, if anything. Only
    its lines are counted: the value is never shown."""
    for name in names:
        try:
            lines = len(folder.joinpath(name).read_text().strip().splitlines())
        except (OSError, UnicodeDecodeError):
            return f"{name} cannot be read"
        if lines != 1:
            return f"it holds {lines} lines, not one"
    return None


# Where kp is installed, and the sudo rule that names it there (office/sudoers.d/zz-keporkak-kp, contracts.md §6).
KP_TARGET = "/usr/local/bin/kp"
DROP_IN = "zz-keporkak-kp"
# What the sudo package puts in /etc/sudoers.d: comments only.
SUDO_README = "README"


def check_kp(report, office):
    """kp is the checkout's scripts/kp, at the path the sudo rule names, root-owned and not writable by the office user."""
    user = office["user"]
    item = f"kp root-owned and not writable by {user}"
    install = f"sudo install -o root -g root -m 0755 {ROOT}/scripts/kp {KP_TARGET}"
    path = shutil.which("kp")
    if path is None:
        report.todo(item, f"no kp on PATH: just install, then {install} (office/README.md §3)")
        return
    real = os.path.realpath(path)
    writable = os.access(real, os.W_OK) or os.access(os.path.dirname(real), os.W_OK)
    if real != KP_TARGET:
        report.todo(item, f"the kp on PATH is {real}, not {KP_TARGET}, where the sudo rule names it: {install}, and keep it the only kp on PATH")
    elif os.stat(real).st_uid != 0 or writable:
        report.todo(item, f"{install}, and keep it the only kp on PATH")
    elif Path(real).read_bytes() != (ROOT / "scripts/kp").read_bytes():
        report.todo(item, f"{KP_TARGET} is not this checkout's scripts/kp: {install}")
    else:
        report.ok(item, real)


def check_sudo_rule(report, folder, user):
    """The sudo conditions of contracts.md §6 (D-43): Bora's sudo asks every time for kp — no NOPASSWD, no cached
    ticket. The drop-in that makes it ask for kp is installed, root's, sorts last. A NOPASSWD rule anywhere else would
    let anything running as the office user, a seat among them, run `sudo sh -c 'kp …'` without a password, which the
    daemon takes as Bora's signature. Every rule's content needs sudo to read, which this check never runs; what it
    reads is the folder's listing and the drop-in's owner, mode and size, set against the template's. So any other rule
    sudo reads there — but the sudo package's README — may say NOPASSWD, and is to do. /etc/sudoers itself it cannot
    read: `sudo -l` is Bora's (office/README.md §2)."""
    item = "sudo asks every time for kp"
    template = ROOT / "office/sudoers.d" / DROP_IN
    install = f"sudo visudo -cf {template} && sudo install -o root -g root -m 0440 {template} {folder}/{DROP_IN} (office/README.md §2)"
    path = Path(folder) / DROP_IN
    try:
        names = os.listdir(folder)
    except OSError as error:
        report.todo(item, f"{folder} cannot be read ({error.strerror}): {install}")
        return
    # sudo reads each file of the folder, in order, but those whose name has a dot or ends in ~.
    read = sorted(name for name in names if "." not in name and not name.endswith("~"))
    others = [name for name in read if name not in (DROP_IN, SUDO_README)]
    found = []
    if others:
        found.append(f"{', '.join(f'{folder}/{name}' for name in others)} {'is a rule' if len(others) == 1 else 'are rules'} sudo reads that only sudo can read"
                     f" — {'it' if len(others) == 1 else 'each'} may say NOPASSWD for {user}, which D-43 forbids: "
                     + "; ".join(f"sudo cat {folder}/{name}" for name in others) + f", and sudo rm {'it' if len(others) == 1 else 'each'} if it does (office/README.md §2)")
    try:
        info = path.stat()
    except FileNotFoundError:
        report.todo(item, "; ".join([f"{path} is not there: {install}"] + found))
        return
    except OSError as error:
        report.todo(item, "; ".join([f"{path} cannot be read ({error.strerror}): {install}"] + found))
        return
    problems = []
    if (info.st_uid, info.st_gid) != (0, 0):
        problems.append("it is not root:root's")
    if stat.S_IMODE(info.st_mode) != 0o440:
        problems.append(f"its mode is {stat.S_IMODE(info.st_mode):04o}, not 0440")
    if info.st_size != template.stat().st_size:
        problems.append("it is not the size of the template")
    # For kp, the last rule that matches wins: nothing may sort after the drop-in.
    later = [name for name in read if name > DROP_IN]
    if later:
        problems.append(f"{', '.join(later)} sort{'s' if len(later) == 1 else ''} after it")
    if problems:
        found.insert(0, f"{path}: " + "; ".join(problems) + f" — {install}")
    if found:
        report.todo(item, "; ".join(found))
    else:
        report.ok(item, f"{path}: root:root, 0440, sorts last, the size of office/sudoers.d/{DROP_IN}; no other rule beside sudo's README"
                        f" (their content, and /etc/sudoers, need sudo to read: sudo -l, office/README.md §2)")


# Bora's signed command, as a seat would try it (contracts.md §6).
SEAT_CALLS = ("kp approve 1", "sudo kp approve 1")
# The PATH a seat's hooks run with (kp-driver gives the seat a fixed PATH).
HOOK_PATH = "/usr/local/bin:/usr/bin:/bin"


def build_homes(folder):
    """Every persona's home, for every profile, made by kp-driver's builder into folder — no session, no token. None
    when they are made; otherwise what went wrong, in one line."""
    try:
        gradle = subprocess.run([str(ROOT / "gradlew"), "--console=plain", "-q", ":kp-driver:handshakeClasspath"], cwd=ROOT,
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=900)
        if gradle.returncode != 0:
            return "./gradlew :kp-driver:handshakeClasspath failed: " + ((gradle.stderr or gradle.stdout).strip().splitlines() or ["no output"])[-1]
        classpath = (ROOT / "kp-driver/build/handshake.classpath").read_text().strip()
        built = subprocess.run(["java", "-XX:-UsePerfData", "-cp", classpath, "kp.driver.handshake.HomesKt", "--root", str(ROOT), "--out", str(folder)],
                               cwd=ROOT, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"the builder did not run: {error}"
    if built.returncode != 0:
        return "the builder made no home: " + ((built.stderr or built.stdout).strip().splitlines() or ["no output"])[0]
    return None


def denial(permissions, command):
    """What hooks/deny answers to a Bash call of command, read against permissions: the border that denies it, or None."""
    call = json.dumps({"hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_input": {"command": command}})
    try:
        out = subprocess.run(["/bin/sh", str(ROOT / "hooks/deny"), str(permissions)], input=call, capture_output=True, text=True, timeout=30,
                             env={"PATH": HOOK_PATH, "LANG": "C.UTF-8"}).stdout
        reason = json.loads(out)["hookSpecificOutput"]["permissionDecisionReason"] if out.strip() else ""
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        return None
    match = re.search(r"border ([a-z][a-z-]*)", reason)
    return match.group(1) if match else None


def check_seat_permissions(report, homes):
    """contracts.md §6: seats' compiled permissions deny `kp` and `sudo`. Reads the permissions.json of every home —
    built by kp-driver's builder in a temporary folder, or found in homes — and puts a `kp …` and a `sudo kp …` call
    through hooks/deny with each, as a seat's Claude Code does: each must be denied by a border, not by a hook that
    cannot read its table."""
    item = "seats' permissions deny kp and sudo"
    with tempfile.TemporaryDirectory(prefix="kp-office-homes-") as temporary:
        if homes is None:
            folder, how = Path(temporary), "built from this checkout's personas and profiles"
            problem = build_homes(folder)
            if problem:
                report.todo(item, problem + " — then just office-check again")
                return
            folder = folder / "seats"
        else:
            folder, how = homes, f"read from {homes}"
        tables = sorted(folder.glob("*/permissions.json")) if folder.is_dir() else []
        if not tables:
            report.todo(item, f"no home's permissions.json to read in {folder} — a home is built by kp-driver (office/README.md §5)")
            return
        borders, through = set(), []
        for table in tables:
            for command in SEAT_CALLS:
                border = denial(table, command)
                if border:
                    borders.add(border)
                else:
                    through.append(f"{table.parent.name} does not deny `{command}` by a border")
    if through:
        report.todo(item, "; ".join(through) + " — a persona's deny: or the packaging data changes through a stage (office/README.md §5)")
    else:
        report.ok(item, f"{len(tables)} home{'s' if len(tables) != 1 else ''} {how}; each denies `{SEAT_CALLS[0]}` and `{SEAT_CALLS[1]}`, by border {', '.join(sorted(borders))}")


def check_live_targets(report, office):
    # Reads the local kubeconfig only; no cluster is contacted.
    contexts = set(query(["kubectl", "config", "get-contexts", "-o", "name"]) or [])
    for target in office["live_targets"]:
        if target in contexts:
            report.ok(f"kubectl context {target}")
        else:
            report.later(f"kubectl context {target}", "S4.1")


def check_service(report, office):
    """The daemon's systemd user unit is loaded, and the launcher it starts is there (`just install`)."""
    user = office["user"]
    item = "systemd user service keporkak.service"
    if run(["systemctl", "--user", "show", "keporkak.service", "--property=LoadState", "--value"]) != "loaded":
        report.todo(item, "just install (office/README.md §3)")
    else:
        launcher = re.search(r"path=([^ ;]+)", run(["systemctl", "--user", "show", "keporkak.service", "--property=ExecStart", "--value"]) or "")
        if launcher and not Path(launcher.group(1)).exists():
            report.todo(item, f"its launcher {launcher.group(1)} is not there: just install (office/README.md §3)")
        else:
            report.ok(item, f"loaded; it starts {launcher.group(1)}" if launcher else "loaded")
    if run(["loginctl", "show-user", user, "--property=Linger", "--value"]) == "yes":
        report.ok(f"lingering for {user}")
    else:
        report.todo(f"lingering for {user}", f"sudo loginctl enable-linger {user}")


def check_vendor_configuration(report, office):
    """What S2.2's probe showed (contracts.md §10, §12): a seat's Claude Code, given its config home, reads nothing of
    the office user's ~/.claude or ~/.claude.json — only the home, where Claude Code also keeps its own global file,
    .claude.json. So nothing of the office user's configuration reaches a seat while the seats' root is not ~/.claude
    or inside it, and no home's .claude.json names an MCP server. Reads only; a home is never edited here."""
    item = "no vendor global configuration reachable from a seat's config home"
    written = office["roots"]["seats"]
    seats, vendor = home(written).resolve(), home("~/.claude").resolve()
    if seats == vendor or vendor in seats.parents:
        report.todo(item, f"roots.seats {written} is ~/.claude or inside it: name a folder outside it in pool/offices.yaml")
        return
    named = []
    for file in sorted(seats.glob("*/.claude.json")) if seats.is_dir() else []:
        data = read_json(file) or {}
        servers = list(data.get("mcpServers") or {}) + [name for project in (data.get("projects") or {}).values() for name in ((project or {}).get("mcpServers") or {})]
        if servers:
            named.append(f"{written}/{file.parent.name}/.claude.json names {', '.join(servers)}")
    if named:
        report.todo(item, "; ".join(named) + " — remove them: a seat's home is built at every start, never edited by hand (office/README.md)")
    else:
        report.ok(item, f"a seat reads its config home under {written}, not ~/.claude (S2.2's probe); no home names an MCP server")


def netrc_names(text):
    """The machines a netrc file names, lower case, and whether it has a `default` entry — read by the names only: the value
    of a `login`, a `password` or an `account`, and a macro's body, are passed over and kept nowhere. A name may be quoted,
    as curl reads it."""
    tokens = list(re.finditer(r'"(?:[^"\\\n]|\\.)*"|\S+', text))
    names, default, at = [], False, 0
    while at < len(tokens):
        word = tokens[at].group()
        if word == "machine" and at + 1 < len(tokens):
            names.append(tokens[at + 1].group().strip('"').lower())
            at += 2
        elif word == "default":
            default, at = True, at + 1
        elif word in ("login", "password", "account"):
            at += 2
        elif word == "macdef":
            # A macro's body runs to the first empty line after its name's line.
            end = text.find("\n\n", tokens[min(at + 1, len(tokens) - 1)].end())
            at = len(tokens) if end < 0 else next((n for n in range(at + 1, len(tokens)) if tokens[n].start() > end), len(tokens))
        else:
            at += 1
    return names, default


def check_netrc(report):
    """Amendment 47 (contracts.md §4.4, §12): a seat runs as the office user, with that user's HOME (D-115), and git reads
    $HOME/.netrc for every https request before it asks the seat's credential helper — nothing in a seat's environment turns
    that off. So the office user's ~/.netrc names no github.com machine, and has no default entry, which git takes for any
    machine. Read by the machines' names only; no login or password is printed. Of a ~/.netrc that names neither — or of
    none — nothing is said."""
    try:
        text = home("~/.netrc").read_text(errors="replace")
    except OSError:
        return
    names, default = netrc_names(text)
    why = "remove that entry: a seat's git would read it before the seat's helper, and act as you (office/README.md §6)"
    if "github.com" in names:
        report.todo("no github.com in ~/.netrc", f"~/.netrc names the machine github.com — {why}")
    elif default:
        report.todo("no github.com in ~/.netrc", f"~/.netrc has a default entry, which git takes for github.com — {why}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pool", type=Path, default=ROOT / "pool")
    parser.add_argument("--adapter", type=Path, default=ROOT / "office/adapter", help="the adapter's folder: its lockfile and node_modules")
    parser.add_argument("--office", default=socket.gethostname().split(".")[0])
    parser.add_argument("--homes", type=Path, help="read the homes built in this folder instead of building them")
    parser.add_argument("--sudoers", type=Path, default=Path("/etc/sudoers.d"), help="the folder sudo reads its drop-ins from")
    args = parser.parse_args()
    try:
        roster = yaml.load((args.pool / "roster.yaml").read_text(), Loader=PoolLoader)
        office = yaml.load((args.pool / "offices.yaml").read_text(), Loader=PoolLoader)["offices"][args.office]
    except (OSError, KeyError, TypeError, yaml.YAMLError) as error:
        print(f"office check: cannot read office '{args.office}' from {args.pool} ({error}); run `just rules`, or pass --office", file=sys.stderr)
        return 2
    report = Report()
    check_runtimes(report, roster, args.adapter)
    check_claude(report)
    check_tools(report)
    check_roots(report, office)
    check_project(report, office)
    check_secrets(report, office)
    check_kp(report, office)
    check_sudo_rule(report, args.sudoers, office["user"])
    check_seat_permissions(report, args.homes)
    check_live_targets(report, office)
    check_service(report, office)
    check_vendor_configuration(report, office)
    check_netrc(report)
    print("\n".join(report.lines))
    counts = report.counts
    print(f"RESULT: {'NOT READY' if counts['to do'] else 'READY'} — {counts['to do']} to do, {counts['later']} later, {counts['ok']} ok")
    return 1 if counts["to do"] else 0


if __name__ == "__main__":
    sys.exit(main())
