#!/usr/bin/env python3
"""The Python suite, as `just test` runs it: every `test_*.py` under tests/ (or the folder given).

A suite that executes no test fails (retro of P0, candidate 3): unittest itself exits 0 on an empty discovery in the
Python versions the office runs. The last line says how many tests ran, for CI's log (issue #2).
Usage: python3 scripts/run-python-tests.py [folder]
"""
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
folder = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "tests"
suite = unittest.defaultTestLoader.discover(str(folder), pattern="test_*.py", top_level_dir=str(folder))
result = unittest.TextTestRunner().run(suite)
sys.stderr.flush()
if result.testsRun == 0:
    print("FAIL the Python suite executed no test")
    sys.exit(1)
print(f"the Python suite executed {result.testsRun} tests")
sys.exit(0 if result.wasSuccessful() else 1)
