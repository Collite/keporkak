set positional-arguments

# Install the fixed Python tool set once, inside ignored build output.
_python-env:
    python3 build-support/bootstrap_python.py

# Build every JVM module (including its checks). It goes on past a module whose specs fail and names each (S3.0 T4).
build: _python-env
    ./gradlew --console=plain --continue build

# Unit/component specs; all collaborators are fixtures. A suite that executes no test fails.
test: _python-env
    build/python/bin/python scripts/run-python-tests.py
    ./gradlew --console=plain --continue test

# S0.1 owns these scripts and generated personas. A spec asserts a refusal by its rule, or the list says why not (S2.8).
rules: _python-env
    build/python/bin/python scripts/check-rules.py --self-test
    build/python/bin/python scripts/check-rules.py
    build/python/bin/python build-support/check_refusal_assertions.py
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

# The acceptance specs of phase 1 (S1.6; architecture.md §7, §8): H-1′, H-2′ and H-3 through the real mailroom with seats
# scripted from rules/walks/, and the refusal sweep generated from the rules. Its report ends with the counts; a run that
# leaves no report fails.
hero:
    #!/usr/bin/env sh
    set -u
    ./gradlew --console=plain :kp-daemon:hero
    status=$?
    cat kp-daemon/build/hero/report.txt || status=1
    exit $status

# Engine fences are part of Gradle's check/build. Each step ends with the time it took, and the last line with the
# whole (S2.8) — beside the machine's load and the Gradle daemons at work on another build (S3.0 T4): a slowdown shows
# in the hand-off, and so does whether the machine was busy.
# The front door used by CI.
check:
    #!/usr/bin/env sh
    set -u
    began=$(date +%s)
    busy() {
        load=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || uptime | sed 's/.*average[s]*: //')
        daemons=$(./gradlew --status --console=plain 2>/dev/null | grep -cE '^ *[0-9]+ +BUSY')
        echo "load $load · Gradle daemons busy on another build: $daemons"
    }
    for step in rules changelog build test schema-check hero; do
        start=$(date +%s)
        "{{just_executable()}}" --justfile "{{justfile()}}" "$step"
        status=$?
        if [ "$status" -ne 0 ]; then
            echo "== just $step failed after $(( $(date +%s) - start )) s · $(busy)"
            exit "$status"
        fi
        echo "== just $step: $(( $(date +%s) - start )) s · $(busy)"
    done
    echo "== just check: $(( $(date +%s) - began )) s · $(busy)"

# S2.8, the retro of P1's candidate 8: rules/walks/ and fixtures are exempt; CI's checkout reaches the merge base.
# A change to rules/*.yaml, robots/, pool/ or docs/wow/ since the merge base with master names its decision in docs/wow/CHANGELOG.md.
changelog: _python-env
    build/python/bin/python build-support/check_changelog.py

# What the rulebook is owed (D-90 (4), S3.0 T2): each decision of <log> marked "→ rulebook" that no line of
# docs/wow/CHANGELOG.md names, with its line; exits 1 when it lists one. From P3's exit a phase does not exit while it does.
owed log *args: _python-env
    build/python/bin/python build-support/owed.py --from "{{invocation_directory()}}" "$@"

# Runs the specs <filter> names and no other, in the working tree (S3.0 T4): `just mutant`'s narrowing without a patch.
# Prints each case that ran, passed or failed with its whole message; exit 0 every case passed · 1 one failed · 3 the
# filter names no spec · 4 the build failed first. KP_SPEC_TIMEOUT sets a spec's default timeout (5m).
spec filter: _python-env
    build/python/bin/python build-support/spec.py "$1"

# Applies <patch> to a throwaway copy of the working tree, runs the specs <filter> names there with a forced rerun, and
# prints `red` and the specs that failed, or `green`; the working tree stays as it was (S2.8; architecture.md §7).
# Exit 0 red · 1 green · 2 the patch does not apply · 3 the filter runs no spec · 4 the patched copy does not build.
# Killed, it stops its run and removes its copy (S3.0 T4).
# Can a spec fail? `just mutant <patch> <spec filter>` — red or green, on a throwaway copy.
mutant patch filter: _python-env
    build/python/bin/python build-support/mutant.py --from "{{invocation_directory()}}" "$1" "$2"

# Record one carried message (contracts.md §12): a kind and its flags, or one complete message on stdin.
# Only the project's mail/<effort>/_hand/ is written; what `kp check` would call a slip is refused, and nothing is written.
record *args: _python-env
    build/python/bin/python scripts/kp-record.py "$@"

# What the office has and what Bora must still do (contracts.md §12); reads and reports, changes nothing.
office-check *args: _python-env
    build/python/bin/python scripts/office-check.py "$@"

# The office daemon (S1.5; architecture.md §4, §7). `just install` builds it, installs it and its systemd user unit for
# the office user, and prints the one step that is Bora's, with sudo — it runs none (office/README.md).
install: _python-env
    build/python/bin/python scripts/install.py

# The daemon as the systemd user service: start, stop, and follow its journal.
run:
    systemctl --user start keporkak.service

stop:
    systemctl --user stop keporkak.service

logs:
    journalctl --user --unit keporkak.service --lines 100 --follow

# The daemon on a throwaway project under build/rehearsal/: the seats scripted and the facts tabled from
# rules/walks/<walk>.yaml, pausing where the walk needs Bora — who answers with the real `kp` (`sudo kp approve <ticket>`).
# It listens on the office's socket: stop the service first (`just stop`). Ctrl-C ends it. `just rehearse <walk> --seats
# fake` plays the walk's seats with S2.1's fake agent: processes the daemon starts, wakes, re-seats and watches through
# the driver and the seat API, each with a pane in the tmux session `kp` (`tmux attach -t kp`). `--seats real` (S2.5) runs
# Claude seats on the adapter `just adapter` installed and the office's token: a walk that names `claude_seats` has their
# steps waited for, ends once the seats the last message woke have ended that turn, and leaves its evidence under the
# office's scratch root (`rehearsals/`). It spends tokens: run it on demand (office/README.md); CI never runs it.
rehearse walk *args:
    #!/usr/bin/env sh
    set -eu
    walk="$1"
    shift
    ./gradlew --console=plain -q :kp-daemon:installDist
    if [ "${1:-}" = "--seats" ] && [ "${2:-}" = "fake" ]; then
        ./gradlew --console=plain -q :kp-driver:fakeClasspath
        set -- "$@" --fake-classpath "$(cat kp-driver/build/fake.classpath)"
    fi
    exec kp-daemon/build/install/kp-daemon/bin/kp-daemon rehearse "rules/walks/$walk.yaml" --root "$PWD" "$@"

# Install the Claude adapter the roster pins, from office/adapter/package-lock.json and nothing else (contracts.md §9).
adapter:
    cd office/adapter && npm ci --ignore-scripts --no-audit --no-fund

# The adapter contract test (S2.1): the driver against the pinned adapter, on the office. It spends tokens: run it on
# demand, not as a habit; it is no part of `check`, and CI never runs it. Its evidence file is named on its last line.
# `just handshake models` runs only its models step — every roster model set and read back — and spends no tokens.
# `just handshake hooks` (S2.2) runs a seat the builder made for the coder's persona through its hooks and borders,
# with a poisoned HOME beside it: one prompt. `just handshake tools` (S2.3) runs a seat the builder made for the coder's
# persona in front of the seat API and a mailroom on a throwaway log, through the five tools and the stop gate: two
# prompts. None of them is part of `check`, and CI never runs them.
handshake *args:
    #!/usr/bin/env sh
    set -eu
    if [ "${1:-}" = tools ]; then
        shift
        ./gradlew --console=plain -q :kp-seat-api:handshakeClasspath
        exec java -cp "$(cat kp-seat-api/build/handshake.classpath)" kp.seatapi.handshake.ToolsHandshakeKt --root "$PWD" "$@"
    fi
    ./gradlew --console=plain -q :kp-driver:handshakeClasspath
    exec java -cp "$(cat kp-driver/build/handshake.classpath)" kp.driver.handshake.HandshakeKt --root "$PWD" "$@"


git-it:
    git -C ../project add .
    git -C ../project  commit -am "messages"
    git -C ../project  pull 
    git -C ../project  push 
    
