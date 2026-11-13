# Keporkak

A rules-driven office for coding stages. S0.2 provides the build and wire models; the daemon and later-stage modules have no runtime behavior yet.

Requires JDK 21, `just`, and Python 3.11–3.14. The Gradle wrapper downloads its fixed, checksum-verified distribution. `just` creates an isolated Python environment under `build/python` from `requirements.txt`; use `KP_PYTHON=/path/to/python` to choose an interpreter before the first run.

```sh
just build          # build all eleven JVM modules and run their checks
just test           # Python checks and Kotlin unit/component specs
just rules          # check S0.1 rules and regenerate the seat personas
just check          # all of the above plus schema and version checks; CI entry point
just schema-check /path/to/project/contracts.md
```

CI sees the two checked-in schemas. The last command additionally compares their bytes with the JSON blocks in the supplied contracts file and fails even on whitespace drift. Schema/model conformance uses one shared set of 224 examples with Draft 2020-12 validation and date-time format assertions enabled. Messages reject extra fields; records retain the extensions allowed by their schema. Wire models preserve absent fields separately from explicit nulls and retain integral JSON numbers without narrowing them to a machine integer.

The engine's build checks its declared production and test classpath dependencies against `kp-model`, `kp-rules` and the Kotlin standard library. A private decision constructor and module-internal factory keep construction inside the engine. Negative tests inject forbidden dependencies and compile external Kotlin/Java callers in an ignored copy under `build/engine-fixture`, leaving normal build outputs alone.

All modules are in one Gradle build: `kp-model`, `kp-rules`, `kp-engine`, `kp-log`, `kp-facts`, `kp-ops`, `kp-driver`, `kp-seat-api`, `kp-mailroom`, `kp-board`, and `kp-daemon`. Only the model and the decision construction boundary have code in S0.2. The Python `kp` client, agent sessions, log, engine behavior and mailroom belong to later stages.

Library versions and their verification sources are recorded in `gradle/libs.versions.toml`; Python tooling versions and sources are in `requirements.txt`. The SDK API compilation spec checks both ACP 0.34.0 and MCP 0.16.0 with the selected Kotlin compiler. No check starts an agent or reaches a live target.

To record a message carried by Bora before the daemon exists:

```sh
just record --project /path/to/project < message.json
# For a message without a stage:
just record --project /path/to/project --effort KP < message.json
```

The input is one complete message, including its existing id, sequence, timestamps and `by: "hand:<role>"`. It is validated before any path is created. Stage traffic goes to `mail/<effort>/_hand/p<n>-s<n.m>.jsonl`; unscoped traffic goes to `mail/<effort>/_hand/office-<office>-<yyyy>-<mm>.jsonl`. The recorder appends one UTF-8 JSON line under a file lock and syncs it to disk; it never writes the daemon's log or issues ids. Invalid input leaves existing log bytes unchanged.
