#!/usr/bin/env python3
"""A spec asserts a refusal by the rule that refused and by what failed (retro of P1, candidate 1; S2.8 T3).

A spec file — `*Spec.kt` under a module's `src/test/` — that asserts a refusal in any of its shapes (the engine's
`Outcome.REFUSE`, the mailroom's `Status.REFUSED` or `REFUSED`, `refusal()`, a `Refused` record, a tool result's
`"status": "refused"`, `refusedBy` itself) and never calls `refusedBy(rule, failed)` fails, unless the list beside this script names it
with its reason — as the sweep lists its own exceptions. The list is kept true: an entry with no reason, for a file
that is gone, asserts no refusal, or calls `refusedBy` after all, fails too.
Usage: python3 build-support/check_refusal_assertions.py [--root DIR]
"""
from pathlib import Path
import re
import sys

import yaml

ROOT = Path(sys.argv[sys.argv.index("--root") + 1]).resolve() if "--root" in sys.argv else Path(__file__).resolve().parents[1]
LIST = ROOT / "build-support/refusals-asserted-otherwise.yaml"
REFUSAL = re.compile(r'Outcome\.REFUSE\b|\bREFUSED\b|\.refusal\(\)|\bRefused\b|"status":\s*"refused"|\brefusedBy\(')
BY_RULE = re.compile(r"\brefusedBy\(")


def main():
    listed = (yaml.safe_load(LIST.read_text()) if LIST.is_file() else None) or {}
    specs = sorted(path.relative_to(ROOT).as_posix() for path in ROOT.glob("kp-*/src/test/**/*Spec.kt"))
    asserting = [spec for spec in specs if REFUSAL.search((ROOT / spec).read_text())]
    by_rule = [spec for spec in asserting if BY_RULE.search((ROOT / spec).read_text())]
    failures = [f"{spec}: asserts a refusal without refusedBy(rule, failed), and the list does not name it"
                for spec in asserting if spec not in by_rule and spec not in listed]
    for spec, reason in listed.items():
        if not isinstance(reason, str) or not reason.strip():
            failures.append(f"{LIST.name}: {spec} has no reason")
        elif spec not in specs:
            failures.append(f"{LIST.name}: {spec} is no spec file")
        elif spec not in asserting:
            failures.append(f"{LIST.name}: {spec} asserts no refusal")
        elif spec in by_rule:
            failures.append(f"{LIST.name}: {spec} asserts its refusals by refusedBy — it needs no entry")
    for failure in failures:
        print("FAIL", failure)
    print(f"spec files asserting a refusal: {len(asserting)} · by refusedBy: {len(by_rule)} · listed, each with its reason: "
          f"{len([spec for spec in listed if spec in asserting and spec not in by_rule])}")
    for spec, reason in sorted(listed.items()):
        print(f"  {spec} — {reason}")
    print("RESULT:", "FAIL" if failures else "PASS", f"({len(failures)} failures)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
