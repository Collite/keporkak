#!/usr/bin/env python3
"""A change to the rules carries its changelog line (retro of P1, candidate 8; S2.8 T6).

A branch that changes `rules/*.yaml`, `robots/`, `pool/` or `docs/wow/` against its merge base with master, and adds no
line to `docs/wow/CHANGELOG.md` that names a decision (`D-<n>`), fails, with the changed files named. A line is added
when the changelog has more lines naming a decision than at the merge base: an old line reworded is no new one (issue
#43). `rules/walks/`,
fixtures and the changelog itself are exempt. The working tree is compared — committed or not, and files git does not
track yet — so a change fails here before it is pushed. CI's checkout must reach the merge base (`fetch-depth: 0`): a
master that cannot be found, or no merge base with it, fails too — never passes unchecked.
Usage: python3 build-support/check_changelog.py [--base REF]      default: origin/master, else master
"""
import re
import subprocess
import sys

CHANGELOG = "docs/wow/CHANGELOG.md"
DECISION = re.compile(r"\bD-\d+\b")


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def governed(path):
    """Whether a change to [path] needs a changelog line: the rules' four files, the robots, the pool, the rulebook."""
    parts = path.split("/")
    if path == CHANGELOG or "fixtures" in parts:
        return False
    if parts[0] == "rules":
        return len(parts) == 2 and path.endswith(".yaml")
    return parts[0] in ("robots", "pool") or path.startswith("docs/wow/")


def main():
    candidates = [sys.argv[sys.argv.index("--base") + 1]] if "--base" in sys.argv else ["origin/master", "master"]
    base = next((ref for ref in candidates if git("rev-parse", "--verify", "--quiet", ref + "^{commit}").returncode == 0), None)
    if base is None:
        print(f"FAIL no master to compare with ({' or '.join(candidates)}): fetch it — CI's checkout needs fetch-depth: 0")
        return 1
    merge_base = git("merge-base", "HEAD", base)
    if merge_base.returncode != 0:
        print(f"FAIL no merge base with {base}: the history does not reach it — CI's checkout needs fetch-depth: 0")
        return 1
    since = merge_base.stdout.strip()
    changed = git("diff", "--name-only", "--no-renames", since).stdout.splitlines()
    untracked = git("ls-files", "--others", "--exclude-standard").stdout.splitlines()
    rules = sorted(path for path in set(changed + untracked) if governed(path))
    if not rules:
        print(f"ok   no change to the rules, the robots, the pool or the rulebook since {since[:12]} ({base})")
        return 0
    if CHANGELOG in untracked:
        added = open(CHANGELOG, encoding="utf-8").read().splitlines()
    else:
        diff = git("diff", "--no-renames", "-U0", since, "--", CHANGELOG).stdout.splitlines()
        added = [line[1:] for line in diff if line.startswith("+") and not line.startswith("+++")]
    named = [line for line in added if DECISION.search(line)]
    shown = git("show", f"{since}:{CHANGELOG}")
    before = sum(1 for line in shown.stdout.splitlines() if DECISION.search(line)) if shown.returncode == 0 else 0
    now = sum(1 for line in open(CHANGELOG, encoding="utf-8").read().splitlines() if DECISION.search(line))
    if not named or now <= before:
        reworded = " — a reworded line is no new one" if named else ""
        print(f"FAIL {CHANGELOG} gains no line naming a decision (D-<n>){reworded}, and these change the rules since {since[:12]} ({base}): "
              + ", ".join(rules))
        return 1
    print(f"ok   {len(rules)} changed file(s) of the rules carry {len(named)} changelog line(s) naming a decision: " + ", ".join(rules))
    return 0


if __name__ == "__main__":
    sys.exit(main())
