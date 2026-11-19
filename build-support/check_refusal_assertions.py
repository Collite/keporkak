#!/usr/bin/env python3
"""A spec asserts a refusal by the rule that refused and by what failed (retro of P1, candidate 1; S2.8 T3).

The check reads each line, not each file (review of S2.8, B1): a line of a spec file — `*Spec.kt` under a module's
`src/test/` — that names a refusal in any of its shapes (the engine's `Outcome.REFUSE`, the mailroom's
`Status.REFUSED` or `REFUSED`, `refusal()`, a `Refused` record, a tool result's `"status": "refused"`) asserts it by
`refusedBy(rule, failed)` on that line, or the list beside this script names the line by its text, with its reason —
as the sweep lists its own exceptions. A file that calls `refusedBy` elsewhere excuses none of its other lines. An
import names a refusal and asserts nothing; it is not read — but an outcome it imports by name (`import
kp.engine.Outcome.REFUSE`, an alias, or `Outcome.*`) is then a refusal's shape in that file, written bare (issue #43). The list is kept true: an entry with no reason, for a file
that is gone, for a line the file does not have, that calls `refusedBy` or names no refusal, fails too, and a line the
file has twice needs two entries.
Usage: python3 build-support/check_refusal_assertions.py [--root DIR]
"""
from collections import Counter
from pathlib import Path
import re
import sys

import yaml

ROOT = Path(sys.argv[sys.argv.index("--root") + 1]).resolve() if "--root" in sys.argv else Path(__file__).resolve().parents[1]
LIST = ROOT / "build-support/refusals-asserted-otherwise.yaml"
REFUSAL = re.compile(r'Outcome\.REFUSE\b|\bREFUSED\b|\.refusal\(\)|\bRefused\b|"status":\s*"refused"')
BY_RULE = re.compile(r"\brefusedBy\(")


# An outcome imported by name: `import kp.engine.Outcome.REFUSE`, `import kp.mailroom.Status.REFUSED as NO`, `….Outcome.*`.
IMPORTED = re.compile(r"^\s*import\s+[\w.]*\b(?:Outcome|Status)\.(REFUSE|REFUSED|\*)(?:\s+as\s+(\w+))?\s*$")


def refusal_lines(text):
    """(number, text) of each line that names a refusal or calls refusedBy, imports left out — the names the file imports
    for a refusal's outcome among the shapes."""
    names = set()
    for line in text.splitlines():
        imported = IMPORTED.match(line)
        if imported:
            names |= {imported.group(2)} if imported.group(2) else ({"REFUSE", "REFUSED"} if imported.group(1) == "*" else {imported.group(1)})
    bare = re.compile(r"\b(?:" + "|".join(sorted(map(re.escape, names))) + r")\b") if names else None
    return [(number, line.strip()) for number, line in enumerate(text.splitlines(), 1)
            if (REFUSAL.search(line) or BY_RULE.search(line) or (bare and bare.search(line))) and not line.lstrip().startswith("import ")]


def entries(listed, failures):
    """The list as (spec, line, reason); an entry that is not a line and a reason fails."""
    found = []
    for spec, lines in listed.items():
        for entry in lines if isinstance(lines, list) else [lines]:
            if not isinstance(entry, dict) or not isinstance(entry.get("line"), str) or not entry["line"].strip():
                failures.append(f"{LIST.name}: {spec} — an entry names no line: {entry!r}")
            elif not isinstance(entry.get("reason"), str) or not entry["reason"].strip():
                failures.append(f"{LIST.name}: {spec} — {entry['line'].strip()} — has no reason")
            else:
                found.append((spec, entry["line"].strip(), entry["reason"].strip()))
    return found


def main():
    failures = []
    listed = entries((yaml.safe_load(LIST.read_text()) if LIST.is_file() else None) or {}, failures)
    specs = sorted(path.relative_to(ROOT).as_posix() for path in ROOT.glob("kp-*/src/test/**/*Spec.kt"))
    lines = {spec: refusal_lines((ROOT / spec).read_text()) for spec in specs}
    named = Counter((spec, line) for spec, line, _ in listed)
    unlisted = Counter()
    for spec in specs:
        for number, line in lines[spec]:
            if BY_RULE.search(line):
                continue
            unlisted[(spec, line)] += 1
            if unlisted[(spec, line)] > named[(spec, line)]:
                failures.append(f"{spec}:{number}: asserts a refusal without refusedBy(rule, failed), and the list does not name the line — {line}")
    for (spec, line), count in sorted(named.items()):
        has = [text for _, text in lines.get(spec, [])].count(line)
        if spec not in specs:
            failures.append(f"{LIST.name}: {spec} is no spec file")
        elif BY_RULE.search(line) and has:
            failures.append(f"{LIST.name}: {spec} — {line} — asserts by refusedBy; it needs no entry")
        elif not has:
            failures.append(f"{LIST.name}: {spec} — {line} — the file has no such line naming a refusal")
        elif count > has:
            failures.append(f"{LIST.name}: {spec} — {line} — named {count} times, the file has it {has}")
    for failure in failures:
        print("FAIL", failure)
    every = [(spec, line) for spec in specs for _, line in lines[spec]]
    by_rule = [line for _, line in every if BY_RULE.search(line)]
    files = {spec for spec, _ in every}
    print(f"lines naming a refusal: {len(every)}, in {len(files)} spec files · by refusedBy: {len(by_rule)} · "
          f"listed, each with its reason: {len(listed)}")
    for spec, line, reason in sorted(listed):
        print(f"  {spec} — {line}\n      {reason}")
    print("RESULT:", "FAIL" if failures else "PASS", f"({len(failures)} failures)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
