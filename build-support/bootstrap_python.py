#!/usr/bin/env python3
"""Build tools live in build/python; never install into the system Python.

Where the interpreter cannot make a virtual environment — Ubuntu's python3.X without its python3.X-venv package makes
the folder, then finds no ensurepip — what it left is removed, and one line says what is missing and what to do
(KP/P3/S3.12 T5): no traceback, and the next run starts clean.
"""
import hashlib
import os
from pathlib import Path
import re
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
    made = subprocess.run([python, "-m", "venv", str(venv)], capture_output=True, text=True)
    if made.returncode != 0:
        shutil.rmtree(venv, ignore_errors=True)
        said = made.stdout + made.stderr
        if "ensurepip" not in said and "No module named venv" not in said:
            raise SystemExit(said.rstrip() + f"\nbuild/python: `{python} -m venv` failed (exit {made.returncode})")
        version = re.search(r"\b3\.\d+", subprocess.run([python, "--version"], capture_output=True, text=True).stdout)
        package = f"python{version.group(0)}-venv" if version else "python3-venv"
        raise SystemExit(f"build/python: {python} cannot make a virtual environment: its venv module or ensurepip is missing."
                         f" Set KP_PYTHON to a Python 3.11–3.14 that has both, or install the system's package: sudo apt install {package}")
if not stamp.exists() or stamp.read_text() != digest:
    subprocess.run([str(venv / "bin/python"), "-m", "pip", "install", "--disable-pip-version-check", "--only-binary=:all:", "-r", str(requirements)], check=True)
    subprocess.run([str(venv / "bin/python"), "-m", "pip", "check"], check=True)
    stamp.write_text(digest)
