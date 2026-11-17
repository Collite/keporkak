#!/usr/bin/env python3
"""`just install` (architecture.md §7; S1.5 T7): the office daemon as a systemd user service of the office user.

1. builds the daemon's launcher: ./gradlew :kp-daemon:installDist (or takes one built already, --dist);
2. copies it to <data home>/keporkak/daemon, replacing what is there (the data home is $XDG_DATA_HOME or ~/.local/share);
3. checks that the unit's PATH — office/systemd/keporkak.service, mise's shims first — finds a JDK 21;
4. writes the unit, its launcher and working folder made real, to <config home>/systemd/user/keporkak.service;
5. systemctl --user daemon-reload — `just run`, `just stop` and `just logs` drive it from then on;
6. prints the one step that is Bora's, with sudo: kp, root-owned, where the sudo rule of office/sudoers.d names it.

It runs no sudo and writes nothing as root. It installs from the checkout it runs in: the daemon reads that checkout's
rules, so run it from the office's checkout (pool/offices.yaml: repos.keporkak), again after a pull that changes it.
Usage: python3 scripts/install.py [--dist DIR]
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "office/systemd/keporkak.service"
KP_TARGET = "/usr/local/bin/kp"


def home():
    return Path(os.path.expanduser("~"))


def data_home():
    return Path(os.environ.get("XDG_DATA_HOME") or home() / ".local/share")


def config_home():
    return Path(os.environ.get("XDG_CONFIG_HOME") or home() / ".config")


def unit_path_finds_jdk21(template):
    """The JDK the unit's PATH finds (a user service does not read the login shell): None when it is a JDK 21, else why not."""
    match = re.search(r"^Environment=PATH=(.+)$", template, re.M)
    if not match:
        return "the unit sets no PATH"
    folders = [folder.replace("%h", str(home())) for folder in match.group(1).split(":")]
    java = shutil.which("java", path=os.pathsep.join(folders))
    if java is None:
        return f"no java on the unit's PATH ({match.group(1)}): mise use -g java@temurin-21"
    try:
        version = subprocess.run([java, "-XX:-UsePerfData", "-version"], capture_output=True, text=True, timeout=60, env={"PATH": os.pathsep.join(folders), "HOME": str(home())})
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"{java} did not run: {error}"
    first = (version.stderr or version.stdout).strip().splitlines()[:1]
    if not first or not re.search(r'version "21[."]', first[0]):
        return f"{java} on the unit's PATH is {first[0] if first else 'no JDK'}, not 21: mise use -g java@temurin-21"
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dist", type=Path, help="a daemon distribution built already (bin/kp-daemon, lib/)")
    args = parser.parse_args()

    template = TEMPLATE.read_text()
    problem = unit_path_finds_jdk21(template)
    if problem:
        print(f"install: {problem} — nothing was installed", file=sys.stderr)
        return 1

    dist = args.dist
    if dist is None:
        built = subprocess.run([str(ROOT / "gradlew"), "--console=plain", "-q", ":kp-daemon:installDist"], cwd=ROOT, stdin=subprocess.DEVNULL)
        if built.returncode != 0:
            print("install: ./gradlew :kp-daemon:installDist failed — nothing was installed", file=sys.stderr)
            return 1
        dist = ROOT / "kp-daemon/build/install/kp-daemon"
    if not (dist / "bin/kp-daemon").is_file():
        print(f"install: {dist} holds no bin/kp-daemon — nothing was installed", file=sys.stderr)
        return 1

    target = data_home() / "keporkak/daemon"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(dist, target)
    launcher = target / "bin/kp-daemon"
    print(f"ok     the daemon: {launcher}")

    unit = template.replace("@DAEMON_LAUNCHER@", f"{launcher} run --root {ROOT}").replace("@DAEMON_WORKING_DIRECTORY@", str(ROOT))
    units = config_home() / "systemd/user"
    units.mkdir(parents=True, exist_ok=True)
    (units / "keporkak.service").write_text(unit)
    print(f"ok     the unit: {units / 'keporkak.service'} (the daemon reads the rules of {ROOT})")

    reloaded = subprocess.run(["systemctl", "--user", "daemon-reload"], stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if reloaded.returncode != 0:
        print(f"install: systemctl --user daemon-reload failed: {(reloaded.stderr or reloaded.stdout).strip()}", file=sys.stderr)
        return 1
    print("ok     systemctl --user daemon-reload — `just run` starts the daemon, `just stop` stops it, `just logs` follows it")

    print("Bora's one step, with sudo — kp, root-owned and not writable by the office user, where the sudo rule names it:")
    print(f"  sudo install -o root -g root -m 0755 {ROOT / 'scripts/kp'} {KP_TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
