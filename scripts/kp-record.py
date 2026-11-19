#!/usr/bin/env python3
"""Append one hand-carried message to a project's hand log (contracts.md §3, §12).

Two ways in:
  kp-record.py <kind> --from <party> --to <party> [--stage <stage> | --effort <effort>] [--args key=value …] [--re <id>]
              [--subject "…"] [--body "…"] [--pointer <pointer>]…
      The recorder assigns what the mailroom would: the id m-hand-<6 digits> (unique across the project's hand logs),
      the thread (its own id, or the thread of the message it answers, which must be in the same hand log), the
      sequence number (running per hand log), `at` and `created`; `by` is hand:<the sender's role>. The subject is
      --subject, or else the kind and the stage. An --args value is JSON when it parses as JSON, otherwise a string.
  kp-record.py [--effort <effort>] < message.json
      One complete message, with its own ids, sequence number and timestamps.
Either way the message is validated before any path is created, appended once under a lock and synced to disk, and
nothing but the project's mail/<effort>/_hand/ is written.
What `kp check` would call a slip is refused, and nothing is written (contracts.md §12, Amendment 26): a sender whose
role is not the kind's, an addressee that is not the kind's `to`, `args` that break the kind's declaration — one it
does not declare, one it requires and lacks, a value outside its type or its list — and a reply kind given without
--re while a message it could answer is in the stage's hand log. A `review-request` or a `repair-done` with no
`started` before it in that log, or a `repair-done` with no verdict before it, is written with a warning. --project defaults to the office's one project checkout in
pool/offices.yaml, when the effort has a folder there (--office defaults to the short host name); `mail/` is at its root.
"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import fcntl
import json
import os
from pathlib import Path
import re
import runpy
import socket
import sys
from jsonschema import validators
import yaml

ROOT = Path(__file__).resolve().parents[1]
HAND_ID = re.compile(r"^m-hand-([0-9]{6,})$")
# The parties that are no seat (kp-rules' PARTIES): `a seat` is none of them.
PARTIES = {"bora", "daemon", "mailroom", "ledger", "board"}
STAGE = re.compile(r"([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\.[0-9]+)")

def integer(_checker, value):
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    if isinstance(value, Decimal) and value.is_finite():
        _, digits, exponent = value.as_tuple()
        return exponent >= 0 or all(digit == 0 for digit in digits[max(0, len(digits) + exponent):])
    return False

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result

def reject_constant(_value):
    raise ValueError("non-finite JSON number")

def compact(value):
    # Decimal keeps a fractional seq from being rounded into a schema-valid integer.
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(key) + ":" + compact(item) for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(map(compact, value)) + "]"
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"))

def destination(project, message, effort):
    if not project.is_dir():
        raise ValueError("project checkout must be an existing directory")
    stage = message.get("stage")
    if stage:
        match = re.fullmatch(r"^([A-Z][A-Z0-9-]*)/P([0-9]+)/S([0-9]+\.[0-9]+)$", stage)
        inferred, phase, number = match.groups()
        if effort and effort != inferred:
            raise ValueError("explicit effort differs from message stage")
        effort = inferred
        name = f"p{phase}-s{number}.jsonl"
    else:
        office = message["id"].split("-")[1]
        name = f"office-{office}-{message['at'][:7]}.jsonl"
    if not effort or not re.fullmatch(r"[A-Z][A-Z0-9-]*", effort):
        raise ValueError("an unscoped message requires --effort with an upper-case effort id")
    path = project / "mail" / effort / "_hand" / name
    if not path.resolve().is_relative_to(project.resolve()):
        raise ValueError("hand log must stay inside the project checkout")
    return path

def checked(message):
    schema = json.loads((ROOT / "schemas/message.schema.json").read_text())
    schema_tools = runpy.run_path(str(ROOT / "build-support/check_schemas.py"))
    contract_validator = schema_tools["ContractValidator"]
    checker = contract_validator.TYPE_CHECKER.redefine("integer", integer)
    validator = validators.extend(contract_validator, type_checker=checker)(schema, format_checker=schema_tools["contract_format_checker"]())
    if not validator.is_valid(message):
        raise ValueError("message does not validate against kp:message/1")
    if not message["by"].startswith("hand:"):
        raise ValueError("a carried message must be marked by hand:<role>")
    return (compact(message) + "\n").encode("utf-8")

def append(path, line):
    with path.open("a+b") as output:
        fcntl.flock(output, fcntl.LOCK_EX)
        if output.seek(0, os.SEEK_END):
            output.seek(-1, os.SEEK_END)
            if output.read(1) != b"\n":
                raise ValueError("existing hand log has an incomplete final line")
        output.write(line)
        output.flush()
        os.fsync(output.fileno())

def logged(path):
    if not path.exists():
        return []
    data = path.read_bytes()
    if data and not data.endswith(b"\n"):
        raise ValueError(f"existing hand log {path.name} has an incomplete final line")
    try:
        return [json.loads(line) for line in data.decode("utf-8").splitlines()]
    except json.JSONDecodeError:
        raise ValueError(f"existing hand log {path.name} has a line that is not JSON") from None

def assign(message, project, effort, answers):
    """What the mailroom would assign (contracts.md §3): the time, then — in the hand log that time and the stage name —
    the next hand id across the project's hand logs, the next seq in that log, and the thread. Returns that log."""
    message["at"] = message["created"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    path = destination(project, {**message, "id": "m-hand-000000"}, effort)
    numbers = [int(match.group(1)) for log in sorted(project.glob("mail/*/_hand/*.jsonl")) for line in logged(log) if (match := HAND_ID.match(str(line.get("id", ""))))]
    own = logged(path)
    message["id"] = f"m-hand-{max(numbers, default=0) + 1:06d}"
    message["seq"] = max([line["seq"] for line in own if isinstance(line.get("seq"), int)], default=0) + 1
    message["thread"] = message["id"]
    if answers:
        answered = next((line for line in own if line.get("id") == answers), None)
        if answered is None:
            raise ValueError(f"the message it answers, {answers}, is not in {path.name}")
        message["thread"] = answered.get("thread", answers)
    return path

def parsed(pairs):
    result = {}
    for pair in pairs:
        key, separator, raw = pair.partition("=")
        if not separator or not key:
            raise ValueError(f"--args takes key=value, not '{pair}'")
        if key in result:
            raise ValueError(f"--args names '{key}' twice")
        try:
            result[key] = json.loads(raw, parse_constant=reject_constant)
        except ValueError:
            result[key] = raw
    return result

def role_of(party):
    roles = yaml.safe_load((ROOT / "rules/engagement.yaml").read_text())["roles"]
    for role in (party, party.split("-", 1)[0]):
        if role in roles:
            return role
    raise ValueError(f"'{party}' is neither a role nor a seat of one (rules/engagement.yaml)")

def project_of(args, effort):
    """The office's one project checkout (contracts.md §9): `mail/` is at its root, whichever effort's folder the plan is in."""
    if args.project:
        return args.project
    offices = yaml.safe_load((args.pool / "offices.yaml").read_text())["offices"]
    project = (offices.get(args.office) or {}).get("project")
    project = project if isinstance(project, dict) else {}
    checkout, efforts = project.get("checkout"), project.get("efforts")
    if not isinstance(checkout, str) or not checkout or not isinstance(efforts, dict):
        raise ValueError(f"office '{args.office}' names no project checkout in pool/offices.yaml; pass --project")
    if effort not in efforts:
        raise ValueError(f"effort '{effort}' has no folder in office '{args.office}'s project checkout (pool/offices.yaml); pass --project")
    return Path(os.path.expanduser(checkout))

def kinds():
    return yaml.safe_load((ROOT / "rules/message-kinds.yaml").read_text())["kinds"]

def owns(role, party):
    """A party a role names (kp-engine's roleOwns): the role itself, or one of its seats, `<role>-…`."""
    return party == role or party.startswith(role + "-")

def described(destination):
    """A kind's `to` as a refusal quotes it (contracts.md §4.1): its parties, `{re: from}`, or `a seat`."""
    if isinstance(destination, dict):
        return "{" + ", ".join(f"re: {value}" for value in destination.values()) + "}"
    return destination

def typed(value, declaration):
    """An arg against its declaration, as the engine and both validators type it: its list, its type, its items, its fields."""
    if "enum" in declaration and not any(value == item and type(value) is type(item) for item in declaration["enum"]):
        return False
    kind = declaration.get("type")
    checks = {"string": lambda v: isinstance(v, str), "array": lambda v: isinstance(v, list), "object": lambda v: isinstance(v, dict),
              "boolean": lambda v: isinstance(v, bool), "integer": lambda v: isinstance(v, (int, Decimal)) and not isinstance(v, bool) and v == int(v),
              "number": lambda v: isinstance(v, (int, float, Decimal)) and not isinstance(v, bool)}
    if kind in checks and not checks[kind](value):
        return False
    if isinstance(value, list) and "items" in declaration and not all(typed(item, declaration["items"]) for item in value):
        return False
    if isinstance(value, dict) and not all(field in value for field in declaration.get("required_fields", [])):
        return False
    return True

def declared(declaration):
    """What an arg's declaration allows, as a refusal quotes it: `one of A | B`, else its type."""
    if "enum" in declaration:
        return "one of " + " | ".join(str(item) for item in declaration["enum"])
    return declaration.get("type", "valid")

def slips(message, logged_lines, log_name):
    """What `kp check` would call a slip in [message], recorded beside [logged_lines] of its hand log (contracts.md §12,
    Amendment 26): the first, as the engine words it — or None; and the warnings it is written with."""
    every = kinds()
    kind = every[message["kind"]]
    sender, to = message["from"], message["to"]
    answers = message.get("re")
    answered = next((line for line in logged_lines if line.get("id") == answers), None) if answers else None
    warnings = []
    if not any(owns(role.strip(), sender) for role in kind["from"].split("|")):
        return f"from: {kind['from']}", warnings
    destination = kind["to"]
    if isinstance(destination, dict):
        addressed = answered is None or all(answered.get(field) == to for field in destination.values())
    elif destination.strip() == "a seat":
        addressed = to not in PARTIES
    else:
        addressed = any(owns(name.strip(), to) for name in destination.split("|"))
    if not addressed:
        return f"to: {described(destination)}", warnings
    args = message.get("args") or {}
    declarations = kind.get("args") or {}
    for name in args:
        if name not in declarations:
            return f"args: '{name}' is not declared", warnings
    for name, declaration in declarations.items():
        if name not in args:
            if declaration.get("required"):
                return f"args: '{name}' is required", warnings
        elif not typed(args[name], declaration):
            return f"args: '{name}' is not {declared(declaration)}", warnings
    if not answers:
        asked = [other for other, definition in every.items() if message["kind"] in (definition.get("replies") or [])]
        open_ = next((line for line in logged_lines if line.get("kind") in asked), None)
        if open_ is not None:
            return f"re: a {message['kind']} answers a {open_['kind']}, and {open_['id']} is in {log_name} — give --re", warnings
    earlier = {line.get("kind") for line in logged_lines}
    if message["kind"] in ("review-request", "repair-done") and "started" not in earlier:
        warnings.append(f"no started before this {message['kind']} in {log_name}")
    if message["kind"] == "repair-done" and not earlier & {"verdict", "re-verdict"}:
        warnings.append(f"no verdict before this repair-done in {log_name}")
    return None, warnings

def unslipped(message, path):
    """Refuses a slip (ValueError, nothing written); prints each warning, and the message is written."""
    slip, warnings = slips(message, logged(path), path.name)
    if slip:
        raise ValueError(f"{slip} — kp check would call this line a slip; nothing was written")
    for warning in warnings:
        print(f"warning: {warning}; written all the same", file=sys.stderr)

def from_command_line(args):
    if args.kind not in kinds():
        raise ValueError(f"unknown kind '{args.kind}' (rules/message-kinds.yaml)")
    if not (args.sender and args.to):
        raise ValueError("a recorded message needs --from and --to")
    if args.stage and not STAGE.fullmatch(args.stage):
        raise ValueError(f"malformed --stage '{args.stage}': a stage is <EFFORT>/P<n>/S<n>.<n>, e.g. KP/P1/S1.0")
    message = {"v": 1, "seq": 1, "at": "", "id": "", "thread": "", "from": args.sender, "to": args.to, "kind": args.kind}
    if args.answers:
        message["re"] = args.answers
    if args.stage:
        message["stage"] = args.stage
    message["subject"] = args.subject if args.subject is not None else " ".join(filter(None, [args.kind, args.stage]))
    if args.body is not None:
        message["body"] = args.body
    if args.pointers:
        message["pointers"] = args.pointers
    if args.args:
        message["args"] = parsed(args.args)
    message["created"] = ""
    message["by"] = "hand:" + role_of(args.sender)
    effort = args.effort
    if args.stage:
        effort = STAGE.fullmatch(args.stage).group(1)
    project = project_of(args, effort)
    path = assign(message, project, args.effort, args.answers)
    checked(message)
    unslipped(message, path)
    # Validation finishes before the first filesystem mutation; the assignment, and with its time the hand log, is
    # redone under the lock. Every hand log of an effort is in the same directory.
    path.parent.mkdir(parents=True, exist_ok=True)
    mail = os.open(project / "mail", os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(mail, fcntl.LOCK_EX)
        path = assign(message, project, args.effort, args.answers)
        append(path, checked(message))
    finally:
        os.close(mail)
    return path

def from_stdin(args):
    message = json.loads(sys.stdin.read(), parse_int=Decimal, parse_float=Decimal, parse_constant=reject_constant, object_pairs_hook=unique_object)
    line = checked(message)
    if not args.project:
        stage = message.get("stage") or ""
        args.project = project_of(args, stage.split("/")[0] if stage else args.effort)
    path = destination(args.project, message, args.effort)
    if message.get("kind") not in kinds():
        raise ValueError(f"unknown kind '{message.get('kind')}' (rules/message-kinds.yaml)")
    unslipped(message, path)
    # Validation and serialization finish before the first filesystem mutation.
    path.parent.mkdir(parents=True, exist_ok=True)
    append(path, line)
    return path

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("kind", nargs="?", help="record from the command line; without it, one complete message is read from stdin")
    parser.add_argument("--from", dest="sender")
    parser.add_argument("--to")
    parser.add_argument("--stage")
    parser.add_argument("--args", nargs="+", action="extend", default=[], metavar="KEY=VALUE")
    parser.add_argument("--re", dest="answers", metavar="ID")
    parser.add_argument("--subject", help="default: the kind and the stage")
    parser.add_argument("--body")
    parser.add_argument("--pointer", dest="pointers", action="append", default=[], metavar="POINTER",
                        help="pr:<owner>/<name>#<n>, project:<path>, … (contracts.md §1); repeat for more than one")
    parser.add_argument("--project", type=Path)
    parser.add_argument("--effort", help="required only when the message has no stage")
    parser.add_argument("--pool", type=Path, default=ROOT / "pool")
    parser.add_argument("--office", default=socket.gethostname().split(".")[0])
    args = parser.parse_args()
    try:
        path = from_command_line(args) if args.kind else from_stdin(args)
        print(path)
        return 0
    except (ValueError, OSError, UnicodeError, KeyError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
