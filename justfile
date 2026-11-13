set positional-arguments

# Install the fixed Python tool set once, inside ignored build output.
_python-env:
    python3 build-support/bootstrap_python.py

# Build every JVM module (including its checks).
build: _python-env
    ./gradlew --console=plain build

# Unit/component specs; all collaborators are fixtures.
test: _python-env
    build/python/bin/python -m unittest discover -s tests -p 'test_*.py'
    ./gradlew --console=plain test

# S0.1 owns these scripts and generated personas.
rules: _python-env
    build/python/bin/python scripts/check-rules.py
    build/python/bin/python scripts/render-agents.py

# CI checks the stored schemas; pass contracts.md for the verbatim source comparison.
schema-check contracts="": _python-env
    #!/usr/bin/env sh
    set -eu
    if [ -n "$1" ]; then
        build/python/bin/python build-support/check_schemas.py --contracts "$1"
    else
        build/python/bin/python build-support/check_schemas.py
    fi

# The front door used by CI. Engine fences are part of Gradle's check/build.
check: rules build test schema-check
