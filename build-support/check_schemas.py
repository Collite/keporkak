#!/usr/bin/env python3
"""Check the two contract schemas and, when supplied, their literal source text."""
import argparse
import json
import re
import sys
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
ROOT = Path(__file__).resolve().parents[1]

def validators():
    result = {}
    for name in ("message", "record"):
        schema = json.loads((ROOT / f"schemas/{name}.schema.json").read_text())
        Draft202012Validator.check_schema(schema)
        result[name] = Draft202012Validator(schema, format_checker=FormatChecker())
    return result

def compare(path):
    found = {}
    for block in re.findall(r"^```json\n(.*?)^```", path.read_bytes().decode("utf-8"), re.M | re.S):
        value = json.loads(block)
        if value.get("$id") in ("kp:message/1", "kp:record/1"):
            if value["$id"] in found:
                raise ValueError("duplicate contract schema: " + value["$id"])
            found[value["$id"]] = block.encode()
    for name in ("message", "record"):
        key = f"kp:{name}/1"
        if key not in found:
            raise ValueError("missing contract schema: " + key)
        if (ROOT / f"schemas/{name}.schema.json").read_bytes() != found[key]:
            raise ValueError("schema differs from contract text: " + name)
    print("PASS: 2 schemas match contracts.md verbatim")

def check_examples():
    checks = validators()
    cases = json.loads((ROOT / "kp-model/src/test/resources/conformance.json").read_text())
    for case in cases:
        actual = checks[case["schema"]].is_valid(case["value"])
        if actual != case["accepted"]:
            raise ValueError(f"schema expectation differs: {case['name']}: {list(checks[case['schema']].iter_errors(case['value']))}")
    print(f"PASS: 2 Draft 2020-12 schemas; {len(cases)} shared examples (date-time format assertions enabled)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contracts", type=Path)
    args = parser.parse_args()
    try:
        if args.contracts: compare(args.contracts)
        check_examples()
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
