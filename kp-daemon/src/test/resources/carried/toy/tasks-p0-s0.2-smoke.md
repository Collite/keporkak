# TOY-kp-toy-P0 · S0.2 — Smoke            (PR: feat/toy-kp-toy-p0-s0.2-smoke)
Realizes: D-40 (S-9), D-47, KP D-121 · Contract: toy/contracts.md §1 (Version); toy/architecture.md (Modules) · Pins: JVM 21 (master's at branch time)
rolls: none  ·  live: none  ·  touches: none

The first stage the daemon carries (KP S3.5). One behaviour, small: the library says which version it is, and
says the same wherever it says it. Six tasks — the least a stage has (KP D-128).

## Scope fence
- In scope: the build's version, `0.1.0`, said once, in `gradle.properties`; `LibraryInfo.version` in `lib`; the
  same version in the jar's manifest; a kept check that no version number is written out in `lib`'s sources; the
  README's lines on where the version is said.
- Out of scope: parsing and formatting (S1.1, S1.2); publishing anything; a second module; the docs standard (S0.3).
- Stop conditions — send `stop SC-n`, write the need under "Found while doing", push what compiles, end the turn:
  SC-1 another repo must change · SC-2 contracts.md must change · SC-3 a pin must move · SC-4 a table/endpoint not in
  contracts is needed · SC-5 a fix round the ladder has not granted · SC-6 the list exceeds 150 lines · SC-7 day 2 ·
  SC-8 a border beyond `touches:` · SC-9 the live target is unavailable · SC-10 the repository's CI cannot run

## Pre-flight
- [ ] you work in the stage's worktree, made for you from `kp-toy` master; `just check` is green there before
      you change anything
- [ ] the version is written nowhere yet: `gradle.properties` holds none and the build's is Gradle's `unspecified`
- [ ] `just check` runs Gradle's `check` and then `java checks/BuildRegressions.java` — S0.1's kept checks

## Tasks (tick right after landing; append one line `done <sha>` per task; edit nothing else)
- [ ] T1 — Specs. Seam: the build — what it hands to `lib`, and the jar it makes. `LibraryInfo.version` equals
      the version the build was made with, read by the spec from the build and not written out in it; it is
      not blank and not `unspecified`. The jar's manifest names the same version.
      done
- [ ] T2 — `gradle.properties` says `version=0.1.0`, and that is the build's version: said once, nowhere else.
      done
- [ ] T3 — The build hands its version to `lib` when it builds — a generated resource, not a source file.
      done
- [ ] T4 — `LibraryInfo.version` returns it. No version is written in a Kotlin source file.
      done
- [ ] T5 — The jar's manifest carries `Implementation-Title` and `Implementation-Version`, the build's own
      (toy/contracts.md §1).
      done
- [ ] T6 — Kept: `checks/BuildRegressions.java` fails when a version number is written out under `lib/src`.
      `README.md` says where the version is said and that it is changed there and nowhere else.
      done

## DONE (observable, all required)
- `just check` is green locally and in CI on the PR's head
- with `version=0.2.0` in `gradle.properties` and nothing else changed, the spec is still green,
  `LibraryInfo.version` is `0.2.0` and the jar's manifest says `0.2.0`; with the line taken out, the spec is red
- with `"0.1.0"` written into a Kotlin file under `lib/src`, `just check` is red at the kept check
- `README.md` names `gradle.properties` as the one place

## Exit
- [ ] PR opened as ready from the stage branch; CI green on head; the PR description is the hand-off
- [ ] review: PASS
- [ ] merged — by the daemon, as `kp-merger`, on the TeamLead's `merge`
- [ ] STATUS entry; register rebuilt; every Found line triaged

## Found while doing (one line each; the Principal triages — the executor never acts on these here)
-

## Retro notes (one line each: looked for and not found · a check that did not catch · an expensive tool call)
-
