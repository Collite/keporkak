set positional-arguments

# Install the fixed Python tool set once, inside ignored build output.
_python-env:
    python3 build-support/bootstrap_python.py

# Build every JVM module (including its checks).
build: _python-env
    ./gradlew --console=plain build

# Unit/component specs; all collaborators are fixtures. A suite that executes no test fails.
test: _python-env
    build/python/bin/python scripts/run-python-tests.py
    ./gradlew --console=plain test

# S0.1 owns these scripts and generated personas.
rules: _python-env
    build/python/bin/python scripts/check-rules.py --self-test
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

# Record one carried message (contracts.md §12): a kind and its flags, or one complete message on stdin.
# Only the project's mail/<effort>/_hand/ is written.
record *args: _python-env
    build/python/bin/python scripts/kp-record.py "$@"

# What the office has and what Bora must still do (contracts.md §12); reads and reports, changes nothing.
office-check *args: _python-env
    build/python/bin/python scripts/office-check.py "$@"

# Install the Claude adapter the roster pins, from office/adapter/package-lock.json and nothing else (contracts.md §9).
adapter:
    cd office/adapter && npm ci --ignore-scripts --no-audit --no-fund

# The adapter contract test (S2.1): the driver against the pinned adapter, on the office. It spends tokens: run it on
# demand, not as a habit; it is no part of `check`, and CI never runs it. Its evidence file is named on its last line.
# `just handshake models` runs only its models step — every roster model set and read back — and spends no tokens.
# `just handshake hooks` (S2.2) runs a seat the builder made for the coder's persona through its hooks and borders,
# with a poisoned HOME beside it: one prompt. Neither is part of `check`, and CI never runs them.
handshake *args:
    ./gradlew --console=plain -q :kp-driver:handshakeClasspath
    java -cp "$(cat kp-driver/build/handshake.classpath)" kp.driver.handshake.HandshakeKt --root "$PWD" "$@"
