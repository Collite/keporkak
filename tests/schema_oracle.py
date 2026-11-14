"""Independent Draft 2020-12 answers for the Kotlin shared-example spec."""
import json
import runpy
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
validators = runpy.run_path(str(ROOT / "build-support/check_schemas.py"))["validators"]()
cases = json.loads((ROOT / "kp-model/src/test/resources/conformance.json").read_text())
print(json.dumps({case["name"]: validators[case["schema"]].is_valid(case["value"]) for case in cases}))
