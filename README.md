# Keporkak

A rules-driven office for coding stages. S0.2 provides the build and wire models; the daemon and later-stage modules have no runtime behavior yet.

Requires JDK 21, `just`, and Python 3.11–3.14. The Gradle wrapper downloads its fixed, checksum-verified distribution. `just` creates an isolated Python environment under `build/python` from `requirements.txt`; use `KP_PYTHON=/path/to/python` to choose an interpreter before the first run.

```sh
just build          # build all eleven JVM modules and run their checks
just test           # Python checks and Kotlin unit/component specs
just rules          # check S0.1 rules and regenerate the seat personas
just check          # all of the above plus schema and version checks; CI entry point
just schema-check /path/to/project/contracts.md
just office-check   # on Panda: what the office has, and what Bora must still do (reads only)
```

The office is described in `pool/` (roster, offices, budgets; `check-rules.py` validates them), Bora's install steps
and templates are in `office/`, and the ecosystem profiles in `docs/profiles/`.

CI sees the two checked-in schemas. The last command additionally compares their bytes with the JSON blocks in the supplied contracts file and fails even on whitespace drift. Schema/model conformance uses one shared set of 265 examples with Draft 2020-12 validation and date-time format assertions enabled. Messages reject extra fields; records retain the extensions allowed by their schema. Wire models preserve absent fields separately from explicit nulls and retain integral JSON numbers without narrowing them to a machine integer.

The engine's build checks its declared production and test classpath dependencies against `kp-model`, `kp-rules` and the Kotlin standard library. A private decision constructor and module-internal factory are backed by class-file checks on compile tasks and builds, which reject construction references from other modules, including Java factory calls and Kotlin calls that suppress visibility checks. Negative tests inject forbidden dependencies and compile external Kotlin/Java callers in an ignored copy under `build/engine-fixture`, leaving normal build outputs alone.

All modules are in one Gradle build: `kp-model`, `kp-rules`, `kp-engine`, `kp-log`, `kp-facts`, `kp-ops`, `kp-driver`, `kp-seat-api`, `kp-mailroom`, `kp-board`, and `kp-daemon`. Only the model and the decision construction boundary have code in S0.2. The Python `kp` client, agent sessions, log, engine behavior and mailroom belong to later stages.

Library versions and their verification sources are recorded in `gradle/libs.versions.toml`; Python tooling versions and sources are in `requirements.txt`. The SDK API compilation spec checks both ACP 0.34.0 and MCP 0.16.0 with the selected Kotlin compiler. No check starts an agent or reaches a live target.

To record a message carried by Bora before the daemon exists (contracts.md §3, §12):

```sh
just record review-request --from coder --to teamlead --stage KP/P0/S0.3
just record verdict --from reviewer --to coder --stage KP/P0/S0.3 --args verdict=PASS --re m-hand-000004
# One complete message, with its own ids, sequence and timestamps, is a second way in:
just record < message.json
```

From the command line the recorder assigns what the mailroom would: the id `m-hand-<6 digits>` (unique across the project's hand logs), the thread (its own id, or that of the message `--re` answers, which must be in the same hand log), the sequence number (running per hand log), `at`, `created`, and `by: "hand:<the sender's role>"`; the subject is the kind and the stage. An `--args` value is JSON when it parses as JSON, otherwise a string. The project checkout defaults to the office's in `pool/offices.yaml` (`--project` overrides it). Either way the message is validated before any path is created. Stage traffic goes to `mail/<effort>/_hand/p<n>-s<n.m>.jsonl`; unscoped traffic (`--effort` instead of `--stage`) to `mail/<effort>/_hand/office-<office>-<yyyy>-<mm>.jsonl`. The recorder appends one UTF-8 JSON line under a lock and syncs it to disk; it never writes the daemon's log. Invalid input leaves existing log bytes unchanged.
