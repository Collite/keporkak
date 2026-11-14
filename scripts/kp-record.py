#!/usr/bin/env python3
"""Append one hand-carried message to a project's hand log (contracts.md §3, §12).

Two ways in:
  kp-record.py <kind> --from <party> --to <party> [--stage <stage> | --effort <effort>] [--args key=value …] [--re <id>]
      The recorder assigns what the mailroom would: the id m-hand-<6 digits> (unique across the project's hand logs),
      the thread (its own id, or the thread of the message it answers, which must be in the same hand log), the
      sequence number (running per hand log), `at` and `created`; `by` is hand:<the sender's role>. The subject is
      the kind and the stage. An --args value is JSON when it parses as JSON, otherwise a string.
  kp-record.py [--effort <effort>] < message.json
      One complete message, with its own ids, sequence number and timestamps.
Either way the message is validated before any path is created, appended once under a lock and synced to disk, and
nothing but the project's mail/<effort>/_hand/ is written. --project defaults to the office's project checkout for
the effort in pool/offices.yaml (--office defaults to the short host name).
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

def assign(message, project, path, answers):
    """What the mailroom would assign (contracts.md §3): next hand id across the project's hand logs, next seq in this
    log, the thread, and the time."""
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
    message["at"] = message["created"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

def typed(pairs):
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
    if args.project:
        return args.project
    offices = yaml.safe_load((args.pool / "offices.yaml").read_text())["offices"]
    path = (offices.get(args.office) or {}).get("project", {}).get(effort)
    if not path:
        raise ValueError(f"no project checkout for {effort} on office '{args.office}' in pool/offices.yaml; pass --project")
    return Path(os.path.expanduser(path))

def from_command_line(args):
    kinds = yaml.safe_load((ROOT / "rules/message-kinds.yaml").read_text())["kinds"]
    if args.kind not in kinds:
        raise ValueError(f"unknown kind '{args.kind}' (rules/message-kinds.yaml)")
    if not (args.sender and args.to):
        raise ValueError("a recorded message needs --from and --to")
    message = {"v": 1, "seq": 1, "at": "", "id": "", "thread": "", "from": args.sender, "to": args.to, "kind": args.kind}
    if args.answers:
        message["re"] = args.answers
    if args.stage:
        message["stage"] = args.stage
    message["subject"] = " ".join(filter(None, [args.kind, args.stage]))
    if args.args:
        message["args"] = typed(args.args)
    message["created"] = ""
    message["by"] = "hand:" + role_of(args.sender)
    effort = args.effort
    if args.stage:
        match = re.fullmatch(r"([A-Z][A-Z0-9-]*)/P[0-9]+/S[0-9]+\.[0-9]+", args.stage)
        effort = match.group(1) if match else effort
    project = project_of(args, effort)
    path = destination(project, {**message, "id": "m-hand-000001", "at": "2000-01-01T00:00:00Z"}, args.effort)
    assign(message, project, path, args.answers)
    checked(message)
    # Validation finishes before the first filesystem mutation; the assignment is redone under the lock.
    path.parent.mkdir(parents=True, exist_ok=True)
    mail = os.open(project / "mail", os.O_RDONLY | os.O_DIRECTORY)
    try:
        fcntl.flock(mail, fcntl.LOCK_EX)
        assign(message, project, path, args.answers)
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
