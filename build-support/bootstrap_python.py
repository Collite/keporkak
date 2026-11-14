#!/usr/bin/env python3
"""Build tools live in build/python; never install into the system Python."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
ROOT = Path(__file__).resolve().parents[1]
requirements = ROOT / "requirements.txt"
venv = ROOT / "build/python"
stamp = venv / ".requirements.sha256"
digest = hashlib.sha256(requirements.read_bytes()).hexdigest()
if not (venv / "bin/python").exists():
    python = os.environ.get("KP_PYTHON") or next((shutil.which(p) for p in ["python3.14", "python3.13", "python3.12", "python3.11"] if shutil.which(p)), None)
    if not python:
        raise SystemExit("Python 3.11–3.14 is required; set KP_PYTHON to its interpreter")
    subprocess.run([python, "-m", "venv", str(venv)], check=True)
if not stamp.exists() or stamp.read_text() != digest:
    subprocess.run([str(venv / "bin/python"), "-m", "pip", "install", "--disable-pip-version-check", "--only-binary=:all:", "-r", str(requirements)], check=True)
    subprocess.run([str(venv / "bin/python"), "-m", "pip", "check"], check=True)
    stamp.write_text(digest)
