# TOY-kp-toy-P0 · S0.1 — The skeleton            (PR: feat/toy-kp-toy-p0-s0.1-skeleton)
Realizes: D-40 (S-9), D-47 · Contract: toy/architecture.md (Modules, Stack and flow) · Pins: JVM 21 (master's at branch time)
rolls: none  ·  live: none  ·  touches: none

## Scope fence
- In scope: `Collite/kp-toy` becomes an empty Kotlin library that builds and tests with one command and in CI.
- Out of scope: any parsing or formatting (S1.1, S1.2); the `consumer` and `demo` modules (S1.2, S1.3); publishing
  anything; a container image.
- Stop conditions — send `stop SC-n`, write the need under "Found while doing", push what compiles, end the turn:
  SC-1 another repo must change · SC-2 contracts.md must change · SC-3 a pin must move · SC-4 a table/endpoint not in
  contracts is needed · SC-5 a fix round the ladder has not granted · SC-6 the list exceeds 150 lines · SC-7 day 4 ·
  SC-8 a border beyond `touches:` · SC-9 the live target is unavailable · SC-10 the repository's CI cannot run
  (Actions disabled or no runner) — that is Bora's to switch on

## Pre-flight
- [x] branch from `kp-toy` master (one commit: the README); JDK 21 and `just` are on the machine
  done 12889255ee3e6b96353546dd4895e162d6850b4e

## Tasks (tick right after landing; append one line `done <sha>` per task; edit nothing else)
- [x] T1 — Specs, each named as its sentence:
      · the library builds into one jar
      · the placeholder spec runs and passes
  done e584d245311089804813c436a0ab22c38e7317d3
- [x] T2 — The build: one Gradle build with the module `lib`, JVM 21, a version catalog with every version pinned;
      where `keporkak`'s catalog pins the same library, the same version.
  done bfe22f678212c06ed1ebe8e4ee52578d2cd0a8c0
- [x] T3 — `just` recipes `build`, `test` and `check`; `check` is what CI runs.
  done 9e1780115b62b82eda1301e8e0998422149fea28
- [x] T4 — CI: `just check` on every pull request and on master.
  done de3ff6ba9801da58a564408e8b2c7af92cbfa53e
- [x] T5 — The README says what the toy is for and how to build it; a short `AGENTS.md` points at the plan
      (`keporkak-project`, folder `toy/`) and at Keporkak's rulebook, and says nothing else.
  done 5d1a7961f450b02339f8a464e544b3ef53f55d9f
- [x] T6 — A clean clone builds: the Gradle wrapper is committed, a `.gitignore` keeps build output out, and
      nothing but JDK 21 and `just` is needed.
  done 14024311a28d6027148612808100e30556e48dc4

## DONE (observable, all required)
- `just check` is green locally and in CI on the PR's head
- after the merge, CI is green on master
- the version catalog has no dynamic version

## Exit
- [x] PR opened as ready from the stage branch; CI green on head; the PR description is the hand-off
  done 14024311a28d6027148612808100e30556e48dc4
- [x] review-NNN: PASS (fix rounds per the ladder: two on the verdict · a third on the Principal's grant · a fourth on Bora's ruling)
  done 028e9e2dece9c94f65f3c0da144e6f2ce3c9f013
- [x] merged — by Bora, by hand (KP plan.md §4.2); this stage rolls nothing and has no live target
  done 25d4f09bb1b5041db31fe59eb68e07463248280e
- [x] STATUS entry (≤ 5 lines: PR, merge sha, CI run, counts); register rebuilt; every Found line triaged
  done 25d4f09bb1b5041db31fe59eb68e07463248280e (by the Planner with Bora, 2026-11-14; no register exists until KP S3.3)

## Found while doing (one line each; the Principal triages — the executor never acts on these here)
-

## Retro notes (one line each: looked for and not found · a check that did not catch · an expensive tool call)
- Context7 tools were unavailable; version compatibility was checked in the official Kotlin, Gradle and JUnit documentation.
- The sandbox could not resolve services.gradle.org; the checksum request succeeded with network escalation.
- setup-just uses action.yaml rather than action.yml; its input was verified at the resolved v3 commit.
- actionlint was not installed; the workflow is verified by its GitHub Actions run.
- Review-001 exposed checks the original two passing specs missed: disabled discovery and an unused dynamic catalog pin (B1/B2).
