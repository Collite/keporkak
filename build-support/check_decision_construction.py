#!/usr/bin/env python3
"""Reject constructor/factory references to Decision from another build module."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

METHOD = re.compile(r'^\s*#\d+\s*=\s*(?:InterfaceMethodref|Methodref)\s+.*//\s+(kp/engine/Decision(?:\$Companion)?)\.([^:]+):')

def check(module, directories):
    if module == "kp-engine":
        return
    files = sorted({path for directory in directories for path in directory.rglob("*.class")})
    if not files:
        return
    java_home = os.environ.get("JAVA_HOME")
    javap = Path(java_home) / "bin/javap" if java_home else None
    if not javap or not javap.is_file():
        javap = shutil.which("javap")
    if not javap:
        raise ValueError("the decision construction check requires JDK javap")
    result = subprocess.run([str(javap), "-v", "-p", *map(str, files)], text=True, capture_output=True)
    if result.returncode:
        raise ValueError("javap could not inspect compiled classes: " + result.stderr.strip())
    source = ""
    violations = []
    for line in result.stdout.splitlines():
        if line.startswith("Classfile "):
            source = line.removeprefix("Classfile ")
        match = METHOD.match(line)
        if match:
            owner, method = match.groups()
            if method.strip('"') == "<init>" or method == "create" or method.startswith("create$"):
                violations.append(f"{source} references {owner}.{method}")
    if violations:
        raise ValueError(f"Decision construction outside kp-engine is forbidden: {module}: " + "; ".join(violations))
    print(f"PASS: {module} decision construction fence ({len(files)} class files)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", required=True)
    parser.add_argument("--classes", type=Path, nargs="+", required=True)
    args = parser.parse_args()
    try:
        check(args.module, args.classes)
    except (ValueError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
