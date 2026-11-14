#!/usr/bin/env python3
"""Append one schema-valid hand-carried message from stdin to a project's hand log."""
import argparse
from decimal import Decimal
import fcntl
import json
import os
from pathlib import Path
import re
import runpy
import sys
from jsonschema import validators

ROOT = Path(__file__).resolve().parents[1]

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

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--effort", help="required only when the message has no stage")
    args = parser.parse_args()
    try:
        message = json.loads(sys.stdin.read(), parse_int=Decimal, parse_float=Decimal, parse_constant=reject_constant, object_pairs_hook=unique_object)
        schema = json.loads((ROOT / "schemas/message.schema.json").read_text())
        schema_tools = runpy.run_path(str(ROOT / "build-support/check_schemas.py"))
        contract_validator = schema_tools["ContractValidator"]
        checker = contract_validator.TYPE_CHECKER.redefine("integer", integer)
        validator = validators.extend(contract_validator, type_checker=checker)(schema, format_checker=schema_tools["contract_format_checker"]())
        if not validator.is_valid(message):
            raise ValueError("message does not validate against kp:message/1")
        if not message["by"].startswith("hand:"):
            raise ValueError("a carried message must be marked by hand:<role>")
        path = destination(args.project, message, args.effort)
        line = (compact(message) + "\n").encode("utf-8")
        # Validation and serialization finish before the first filesystem mutation.
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a+b") as output:
            fcntl.flock(output, fcntl.LOCK_EX)
            if output.seek(0, os.SEEK_END):
                output.seek(-1, os.SEEK_END)
                if output.read(1) != b"\n":
                    raise ValueError("existing hand log has an incomplete final line")
            output.write(line)
            output.flush()
            os.fsync(output.fileno())
        print(path)
        return 0
    except (ValueError, OSError, UnicodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
