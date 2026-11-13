#!/usr/bin/env python3
"""Reject unpinned catalog entries and a wrapper/catalog mismatch."""
import re
import tomllib
from pathlib import Path
root = Path(__file__).resolve().parents[1]
catalog = tomllib.loads((root / "gradle/libs.versions.toml").read_text())
for name, version in catalog["versions"].items():
    if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:[.][0-9]+)*(?:-[A-Za-z0-9.]+)?", version) or "SNAPSHOT" in version.upper():
        raise SystemExit(f"unfixed version: {name}={version}")
for section in ["libraries", "plugins"]:
    for name, entry in catalog[section].items():
        ref = entry.get("version", {}).get("ref")
        if ref not in catalog["versions"]:
            raise SystemExit(f"missing pinned version: {name}")
wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
assert f"gradle-{catalog['versions']['gradle']}-bin.zip" in wrapper, "wrapper differs from catalog"
assert re.search(r"^distributionSha256Sum=[0-9a-f]{64}$", wrapper, re.M), "missing distribution checksum"
print(f"PASS: {len(catalog['versions'])} fixed catalog versions; wrapper version and checksum checked")
