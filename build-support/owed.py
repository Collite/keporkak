#!/usr/bin/env python3
"""`just owed <decision log>` — what the rulebook is owed (retro of P2, candidate 7; D-90 (4); S3.0 T2).

A decision of the log whose entry says "→ rulebook" changes a seat's instructions; it is owed until a line of the
rulebook's changelog (`docs/wow/CHANGELOG.md`) names it in its decision field — the last ` · ` field, `KP D-83` or
`KP D-39, D-52`. Each owed decision is listed with its title and its line in the log; the last line says how many.

An entry is a line of the log that begins `- <date> · **D-<n>** ·` and the indented lines that follow it.
Exit: 0 nothing owed · 1 one or more owed · 2 the log or the changelog cannot be read, or the log holds no entry.
Usage: python3 build-support/owed.py [--from DIR] <decision log> [--changelog FILE]
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ENTRY = re.compile(r"^- \d{4}-\d{2}-\d{2} · \*\*(D-\d+)\*\* · (?:\*\*(.+?)\*\*)?")
MARKED = re.compile(r"(?:→|->) rulebook")
DECISION = re.compile(r"\bD-\d+\b")


def entries(text):
    """Each entry of the log: (its id, its title, its first line's number, its text)."""
    found, current = [], None
    for number, line in enumerate(text.splitlines(), 1):
        head = ENTRY.match(line)
        if head:
            current = [head.group(1), (head.group(2) or "").strip(), number, line]
            found.append(current)
        elif current is not None and line[:1].isspace() and line.strip():
            current[3] += "\n" + line
        else:
            current = None
    return found


def named(changelog):
    """The decisions the changelog's lines name in their decision field."""
    names = set()
    for line in changelog.splitlines():
        if line.startswith("- ") and " · " in line:
            names |= set(DECISION.findall(line.rsplit(" · ", 1)[1]))
    return names


def main(arguments):
    origin = Path(arguments.pop(arguments.index("--from") + 1)) if "--from" in arguments else Path.cwd()
    arguments = [a for a in arguments if a != "--from"]
    changelog = ROOT / "docs/wow/CHANGELOG.md"
    if "--changelog" in arguments:
        changelog = origin / arguments.pop(arguments.index("--changelog") + 1)
        arguments.remove("--changelog")
    if len(arguments) != 1:
        print(__doc__.strip().splitlines()[-1])
        return 2
    log = origin / arguments[0]
    try:
        logged, lines = entries(log.read_text()), named(changelog.read_text())
    except OSError as error:
        print(f"error: {error}")
        return 2
    if not logged:
        print(f"error: {log} holds no decision entry (`- <date> · **D-<n>** · …`) — is it the decision log?")
        return 2
    owed = [(ident, title, number) for ident, title, number, text in logged if MARKED.search(text) and ident not in lines]
    for ident, title, number in owed:
        print(f"owed: {ident} — {title or 'untitled'} ({log.name} line {number})")
    marked = sum(1 for *_, text in logged if MARKED.search(text))
    print(f"the rulebook is owed {len(owed)} of the {marked} decisions marked → rulebook in {log.name} ({len(logged)} entries); "
          f"the changelog is {changelog}")
    return 1 if owed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
